package dev.solvit.market

import dev.solvit.core.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Retains native review values. Scripts can select handles but cannot supply Git revisions. */
class GitPageBridge(service: WorkspaceGitService, chatId: String, activeChat: () -> String?) : PageGitAccess {
    private class Authority(val verify: suspend () -> Unit, val write: Boolean, val network: Boolean = false) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Authority>
    }
    private val mutex = Mutex()
    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationLock = Any()
    private data class Operation(val id: String, val operation: String, val status: String,
        val result: JsonElement? = null, val error: String? = null)
    private var operation: Operation? = null
    private var operationJob: Job? = null
    @Volatile private var closed = false
    private var review: Pair<String, WorkspaceGitSession.Diff>? = null
    private var index: Pair<String, WorkspaceGitSession.Revision>? = null
    private var pushReview: Pair<String, WorkspaceRemoteGitSession.PushReview>? = null
    private var pullReview: Pair<String, WorkspaceRemoteGitSession.PullReview>? = null
    private val session = if (service is WorkspaceRemoteGitService) {
        service.bindRemote(chatId, activeChat, { verify(false) }, { verify(true) }, {
            verify(true)
            check(currentCoroutineContext()[Authority]?.network == true) { "Git网络操作需要已确认的原生控件" }
        })
    } else service.bind(chatId, activeChat, { verify(false) }, { verify(true) })

    private fun remote(): WorkspaceRemoteGitSession = session as? WorkspaceRemoteGitSession
        ?: error("Git服务不支持远程操作")

    override suspend fun editCredentials(endpoint: GitRemoteEndpoint, prompt: PageGitCredentialPrompt,
        authorize: suspend () -> Unit): GitCredentialOutcome = withContext(Authority(authorize, true, true)) {
        verify(true)
        val authority = checkNotNull(currentCoroutineContext()[Authority])
        check(synchronized(operationLock) { operationJob?.isCompleted != false }) { "请等待Git传输结束后修改凭据" }
        val configured = remote().credentialsConfigured(endpoint)
        prompt.edit(endpoint, configured, { withContext(authority) { verify(true) } },
            save = { username, secret -> withContext(authority) { verify(true); remote().saveCredentials(endpoint, username, secret); verify(true) } },
            remove = { withContext(authority) { verify(true); remote().removeCredentials(endpoint); verify(true) } }).also { verify(true) }
    }

    private suspend fun verify(write: Boolean) {
        check(!closed) { "Git页面已关闭" }
        val authority = checkNotNull(currentCoroutineContext()[Authority]) { "缺少Git页面授权" }
        check(!write || authority.write) { "Git只读查询不能执行写入" }
        authority.verify()
        check(!closed) { "Git页面已关闭" }
    }
    private fun arguments(json: String): JsonObject {
        require(json.length <= 8192)
        return Json.parseToJsonElement(json).jsonObject
    }
    private fun JsonObject.text(key: String, max: Int = 1024): String = getValue(key).jsonPrimitive
        .also { require(it.isString && it.content.length in 1..max) }.content
    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive
        .also { require(!it.isString) }.int
    private fun remember(revision: WorkspaceGitSession.Revision): JsonObject {
        val id = UUID.randomUUID().toString()
        index = id to revision
        return buildJsonObject { put("revisionId", id) }
    }
    private fun status(value: WorkspaceGitSession.Status) = buildJsonObject {
        put("branch", value.branch?.let(::JsonPrimitive) ?: JsonNull)
        put("head", value.head?.let(::JsonPrimitive) ?: JsonNull)
        put("total", value.total)
        put("entries", buildJsonArray { value.entries.forEach { entry -> add(buildJsonObject {
            put("path", entry.path); put("staged", entry.staged?.let(::JsonPrimitive) ?: JsonNull)
            put("working", entry.working?.let(::JsonPrimitive) ?: JsonNull); put("conflict", entry.conflict)
        }) } })
    }

    override suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit): String {
        if (operation == "remoteOperation") return withContext(Authority(authorize, false)) {
            verify(false)
            val args = arguments(argumentsJson)
            require(args.keys == setOf("operationId"))
            remote().transferProgress() // Checks binding without waiting behind the repository lock.
            val retained = synchronized(operationLock) { checkNotNull(this@GitPageBridge.operation) }
            check(retained.id == args.text("operationId")) { "远程操作记录已变化" }
            val progress = remote().transferProgress()
            verify(false)
            buildJsonObject {
                put("operationId", retained.id); put("operation", retained.operation); put("status", retained.status)
                retained.result?.let { put("result", it) }
                retained.error?.let { put("error", it) }
                progress?.let { put("progress", buildJsonObject {
                    put("task", it.task); put("completed", it.completed); put("total", it.total)
                }) }
            }.toString()
        }
        if (operation == "transferProgress") return withContext(Authority(authorize, false)) {
            verify(false); require(arguments(argumentsJson).isEmpty())
            val value = remote().transferProgress()
            verify(false)
            (value?.let { buildJsonObject {
                put("operation", it.operation); put("task", it.task); put("completed", it.completed); put("total", it.total)
            } } ?: JsonNull).toString()
        }
        return mutex.withLock { withContext(Authority(authorize, false)) {
            verify(false)
            val args = arguments(argumentsJson)
            val result = when (operation) {
                "status" -> {
                    require(args.keys == setOf("offset", "limit"))
                    status(session.status(args.number("offset"), args.number("limit")))
                }
                "diff" -> {
                    require(args.keys == setOf("path", "staged"))
                    val staged = args.getValue("staged").jsonPrimitive.also { require(!it.isString) }.boolean
                    val diff = session.diff(args.text("path"), staged)
                    require(diff.patch.length <= 256 * 1024)
                    val id = UUID.randomUUID().toString()
                    review = id to diff
                    buildJsonObject { put("reviewId", id); put("path", diff.path); put("staged", diff.staged); put("total", diff.patch.length) }
                }
                "diffChunk" -> {
                    require(args.keys == setOf("reviewId", "offset"))
                    session.reviewIndex() // Recheck the captured native binding even for cached text.
                    val retained = checkNotNull(review)
                    check(retained.first == args.text("reviewId")) { "差异审阅已变化，请重新读取" }
                    val patch = retained.second.patch
                    val offset = args.number("offset").also { require(it in 0..patch.length) }
                    require(offset == 0 || offset == patch.length || !patch[offset].isLowSurrogate())
                    var end = minOf(offset + 4096, patch.length)
                    if (end < patch.length && patch[end - 1].isHighSurrogate()) end--
                    buildJsonObject { put("text", patch.substring(offset, end)); put("offset", offset); put("nextOffset", end); put("total", patch.length) }
                }
                "reviewCommit" -> { require(args.isEmpty()); remember(session.reviewIndex()) }
                "history" -> {
                    require(args.keys == setOf("limit"))
                    buildJsonArray { session.history(args.number("limit")).forEach { commit -> add(buildJsonObject {
                        put("id", commit.id); put("message", commit.message); put("author", commit.author); put("timeSeconds", commit.timeSeconds)
                    }) } }
                }
                else -> error("未开放的Git页面查询")
            }
            verify(false)
            result.toString().also { require(it.length <= 16 * 1024) { "Git查询结果过大，请减少每页条数" } }
        } }
    }

    override suspend fun perform(control: PageWorkspaceControl, authorize: suspend () -> Unit): String {
        if (control.operation == "startRemote") return withContext(Authority(authorize, true)) {
            verify(true)
            val args = arguments(control.argumentsJson)
            require(args.keys == setOf("operation", "arguments"))
            val target = args.text("operation", 32)
            require(target in setOf("clone", "fetch", "reviewPush", "reviewPull", "push", "merge"))
            val metadata = buildJsonObject {
                put("kind", "git.perform"); put("operation", target); put("arguments", args.getValue("arguments").jsonObject)
            }
            val native = dev.solvit.plugins.ScriptPageHostAction.git(metadata)
            remote().transferProgress()
            val id = UUID.randomUUID().toString()
            val job = operationScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = Json.parseToJsonElement(perform(native, authorize))
                    synchronized(operationLock) { operation = Operation(id, target, "SUCCEEDED", result) }
                } catch (_: CancellationException) {
                    synchronized(operationLock) { operation = Operation(id, target, "CANCELLED") }
                } catch (error: Exception) {
                    // Host messages only; credentials never enter this adapter.
                    synchronized(operationLock) { operation = Operation(id, target, "FAILED", error = error.message?.take(1000) ?: "Git操作失败") }
                }
            }
            try { synchronized(operationLock) {
                check(!closed) { "Git页面已关闭" }
                check(operationJob?.isCompleted != false) { "远程Git操作仍在进行" }
                operation = Operation(id, target, "RUNNING")
                operationJob = job
                job.start()
            } } catch (error: Exception) { job.cancel(); throw error }
            buildJsonObject { put("operationId", id) }.toString()
        }
        if (control.operation == "cancelTransfer") return withContext(Authority(authorize, true)) {
            verify(true); require(arguments(control.argumentsJson).isEmpty())
            synchronized(operationLock) { operationJob?.cancel() }
            remote().cancelTransfer()
            verify(true)
            buildJsonObject { put("cancelRequested", true) }.toString()
        }
        return mutex.withLock { withContext(Authority(authorize, true,
            control.operation in setOf("clone", "fetch", "reviewPush", "push", "reviewPull"))) {
            verify(true)
            val args = arguments(control.argumentsJson)
            val result = when (control.operation) {
                "clone", "fetch", "reviewPush", "reviewPull" -> {
                    require(args.keys == setOf("url", "branch"))
                    val endpoint = GitRemoteEndpoint.parse(args.text("url", 2048))
                    val branch = args.text("branch", 256)
                    when (control.operation) {
                        "clone" -> remote().clone(endpoint, branch).let { fetched -> buildJsonObject {
                            put("branch", fetched.branch); put("remoteHead", fetched.head)
                        } }
                        "fetch" -> remote().fetch(endpoint, branch).let { fetched -> buildJsonObject {
                            put("branch", fetched.branch); put("remoteHead", fetched.head)
                        } }
                        "reviewPush" -> {
                            pushReview = null
                            val retained = remote().reviewPush(endpoint, branch)
                            val id = UUID.randomUUID().toString()
                            pushReview = id to retained
                            buildJsonObject {
                                put("reviewId", id); put("url", endpoint.url); put("branch", branch)
                                put("localHead", retained.revision.head); put("remoteHead", retained.remoteHead)
                            }
                        }
                        else -> {
                            pullReview = null
                            val retained = remote().reviewPull(endpoint, branch)
                            val id = UUID.randomUUID().toString()
                            pullReview = id to retained
                            buildJsonObject {
                                put("reviewId", id); put("url", endpoint.url); put("branch", branch)
                                put("localHead", retained.revision.head); put("remoteHead", retained.remoteHead)
                            }
                        }
                    }
                }
                "push" -> {
                    require(args.keys == setOf("reviewId"))
                    val retained = checkNotNull(pushReview)
                    check(retained.first == args.text("reviewId")) { "推送审阅已变化，请重新审阅" }
                    pushReview = null
                    remote().push(retained.second).let { buildJsonObject {
                        put("branch", it.branch); put("head", it.head); put("status", it.status)
                    } }
                }
                "merge" -> {
                    require(args.keys == setOf("reviewId"))
                    val retained = checkNotNull(pullReview)
                    check(retained.first == args.text("reviewId")) { "拉取审阅已变化，请重新审阅" }
                    pullReview = null
                    remote().merge(retained.second).let { buildJsonObject {
                        put("status", it.status); put("head", it.head)
                        put("conflicts", buildJsonArray { it.conflicts.forEach { path -> add(path) } })
                    } }
                }
                "initialize" -> { require(args.isEmpty()); remember(session.initialize()) }
                "stage" -> {
                    require(args.keys == setOf("reviewId"))
                    val retained = checkNotNull(review)
                    check(retained.first == args.text("reviewId")) { "差异审阅已变化，请重新读取" }
                    remember(session.stage(retained.second)).also { review = null }
                }
                "unstage" -> {
                    require(args.keys == setOf("reviewId"))
                    val retained = checkNotNull(review)
                    check(retained.first == args.text("reviewId") && retained.second.staged)
                    remember(session.unstage(retained.second.path, retained.second.revision)).also { review = null }
                }
                "commit" -> {
                    require(args.keys == setOf("revisionId", "message", "author", "email"))
                    val retained = checkNotNull(index)
                    check(retained.first == args.text("revisionId")) { "暂存区审阅已变化，请重新确认" }
                    val commit = session.commit(retained.second, args.text("message", 4096), args.text("author", 80), args.text("email", 254))
                    index = null; review = null
                    buildJsonObject { put("id", commit.id); put("message", commit.message) }
                }
                else -> error("未开放的Git页面操作")
            }
            verify(true)
            result.toString()
        } }
    }

    override suspend fun close() = withContext(NonCancellable) {
        closed = true
        operationScope.cancel()
        session.close() // Cancels native transport before waiting behind the page operation.
        val owned = synchronized(operationLock) { operationJob }
        owned?.join()
        mutex.withLock { review = null; index = null; pushReview = null; pullReview = null }
        synchronized(operationLock) { operation = null; operationJob = null }
    }
}
