package dev.solvit.market

import dev.solvit.core.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Native identities come from the host, never from HTML request fields. */
class TerminalPageBridge(private val workspace: WorkspaceService, private val terminals: TerminalService,
    private val chatId: String, private val owner: String, private val allowModelSessions: Boolean = false,
    private val activeChat: () -> String?) : PageTerminalAccess {
    private val binding = checkNotNull(workspace.state.value.bindings[chatId]) { "当前聊天没有工作区" }
    private val mutex = Mutex()
    private val granted = mutableSetOf<String>()
    private suspend fun verify(authorize: suspend () -> Unit) {
        authorize()
        check(activeChat() == chatId && workspace.state.value.bindings[chatId] == binding) { "聊天或工作区已切换，请重新打开终端" }
    }
    override suspend fun request(operation: String, argumentsJson: String, confirmed: Boolean,
        authorize: suspend () -> Unit): String = mutex.withLock {
        require(argumentsJson.toByteArray().size <= 40_000)
        val args = JSONObject(argumentsJson)
        val expected = when (operation) {
            "snapshot" -> emptySet(); "open" -> setOf("shellId")
            "createShell" -> setOf("name"); "input" -> setOf("id", "text")
            "resize" -> setOf("id", "rows", "columns")
            "status", "interrupt", "close", "attach" -> setOf("id")
            else -> error("Unknown terminal page operation")
        }
        require(args.keys().asSequence().toSet() == expected)
        suspend fun authority() = verify(authorize)
        authority()
        fun string(key: String, max: Int): String = (args.get(key) as? String ?: error("Invalid $key")).also {
            require(it.isNotEmpty() && it.length <= max && '\u0000' !in it)
        }
        val context = TerminalPageAuthority(owner, chatId, granted.toSet()) { target -> check(target == binding); authority() }
        val result = withContext(context) {
            when (operation) {
                "snapshot" -> JSONObject().put("shells", JSONArray(workspace.state.value.shells.filter { it.workspaceId == binding }
                    .map { JSONObject().put("id",it.id).put("name",it.name) }))
                    .put("terminals", JSONArray(terminals.sessions.value.filter { session -> session.active && session.chatId == chatId &&
                        session.workspaceId == binding && (allowModelSessions || session.owner == owner) }
                        .map { JSONObject().put("id",it.id).put("shellId",it.shellId).put("owner",it.owner)
                            .put("roleId",it.roleId ?: JSONObject.NULL).put("human",it.human) })).toString()
                "createShell" -> {
                    check(confirmed) { "Shell creation requires native confirmation" }
                    val name = string("name",80)
                    withContext(WorkspacePageAuthority { ws, chat ->
                        check((ws == null || ws == binding) && (chat == null || chat == chatId)); authority()
                    }) { workspace.createShell(binding,name) }
                    JSONObject().put("ok",true).toString()
                }
                "attach" -> {
                    check(confirmed) { "Attaching a terminal requires native confirmation" }
                    val id=string("id",128)
                    val candidate=terminals.sessions.value.single { it.id==id }
                    check(candidate.active && candidate.chatId==chatId && candidate.workspaceId==binding &&
                        (allowModelSessions || candidate.owner==owner)) { "终端不属于当前聊天" }
                    granted+=id
                    try {
                        withContext(TerminalPageAuthority(owner,chatId,granted.toSet()) { target -> check(target==binding);authority() }) {
                            json(terminals.status(binding,id)).toString()
                        }
                    } catch (error:Throwable) { granted-=id;throw error }
                }
                "open" -> {
                    check(confirmed) { "Opening a terminal requires native confirmation" }
                    val session = terminals.open(binding, string("shellId",128))
                    authority(); granted += session.id
                    json(session).toString()
                }
                else -> {
                    val id = string("id",128)
                    check(id in granted) { "此页面尚未获准控制此终端" }
                    when(operation) {
                        "status" -> json(terminals.status(binding,id)).toString()
                        "input" -> json(terminals.input(binding,id,string("text",32_768))).toString()
                        "resize" -> {
                            fun integer(key: String, range: IntRange): Int = (args.get(key) as? Number ?: error("Invalid size")).let {
                                val n=it.toInt(); require(n.toDouble()==it.toDouble() && n in range); n
                            }
                            json(terminals.resize(binding,id,integer("rows",2..300),integer("columns",2..500))).toString()
                        }
                        "interrupt" -> json(terminals.interrupt(binding,id)).toString()
                        "close" -> { terminals.close(binding,id); granted -= id; JSONObject().put("ok",true).toString() }
                        else -> error("Unsupported terminal operation")
                    }
                }
            }
        }
        authority(); result
    }
    private fun json(session: TerminalSession) = JSONObject().put("id",session.id).put("shellId",session.shellId)
        .put("active",session.active).put("output",session.output).put("outputOffset",session.outputOffset)
        .put("revision",session.revision).put("rows",session.rows).put("columns",session.columns)
        .put("exitCode",session.exitCode ?: JSONObject.NULL).put("failure",session.failure ?: JSONObject.NULL)
}
