package dev.solvit.plugins

import dev.solvit.core.*
import kotlinx.serialization.json.*

/** Native metadata is read from the displayed button, never supplied by a JS host call. */
object ScriptPageHostAction {
    fun validate(value: JsonObject) {
        if (value["kind"]?.jsonPrimitive?.content?.startsWith("document.") == true) document(value)
        else if (value["kind"] == JsonPrimitive("workspace.perform")) workspace(value)
        else if (value["kind"] == JsonPrimitive("git.perform")) git(value)
        else if (value["kind"] == JsonPrimitive("git.credentials")) gitCredentials(value)
        else parse(value)
    }
    fun gitCredentials(value: JsonObject): GitRemoteEndpoint {
        require(value.keys == setOf("kind", "url") && value["kind"] == JsonPrimitive("git.credentials"))
        return GitRemoteEndpoint.parse(value.getValue("url").jsonPrimitive.also { require(it.isString) }.content)
    }
    fun git(value: JsonObject): PageWorkspaceControl {
        require(value.keys == setOf("kind", "operation", "arguments") && value["kind"] == JsonPrimitive("git.perform"))
        val operation = value.getValue("operation").jsonPrimitive.also { require(it.isString) }.content
        val args = value.getValue("arguments").jsonObject
        fun text(key: String, max: Int) = args.getValue(key).jsonPrimitive.also {
            require(it.isString && it.content.isNotBlank() && it.content.length <= max && '\u0000' !in it.content)
        }.content
        val keys = when (operation) {
            "startRemote" -> {
                val target = text("operation", 32)
                require(target in setOf("clone", "fetch", "reviewPush", "reviewPull", "push", "merge"))
                git(buildJsonObject { put("kind", "git.perform"); put("operation", target); put("arguments", args.getValue("arguments").jsonObject) })
                setOf("operation", "arguments")
            }
            "initialize" -> emptySet()
            "stage", "unstage" -> { text("reviewId", 128); setOf("reviewId") }
            "clone", "fetch", "reviewPush", "reviewPull" -> {
                GitRemoteEndpoint.parse(text("url", 2048)); text("branch", 256)
                setOf("url", "branch")
            }
            "push", "merge" -> { text("reviewId", 128); setOf("reviewId") }
            "cancelTransfer" -> emptySet()
            "commit" -> {
                text("revisionId", 128); text("message", 4096); text("author", 80); text("email", 254)
                setOf("revisionId", "message", "author", "email")
            }
            else -> error("Unsupported Git page control")
        }
        require(args.keys == keys)
        return PageWorkspaceControl(operation, args.toString())
    }
    fun workspace(value: JsonObject): PageWorkspaceControl {
        require(value.keys == setOf("kind", "operation", "arguments") && value["kind"] == JsonPrimitive("workspace.perform"))
        val operation = value.getValue("operation").jsonPrimitive.also { require(it.isString) }.content
        val args = value.getValue("arguments").jsonObject
        fun text(key: String, max: Int, empty: Boolean = false): String = args.getValue(key).jsonPrimitive.also {
            require(it.isString && it.content.length <= max && (empty || it.content.isNotBlank()) && '\u0000' !in it.content)
        }.content
        val keys = when (operation) {
            "create", "createShell" -> { text("name", 80); setOf("name") }
            "createLinuxShell" -> { text("name", 80); setOf("name") }
            "installEnvironment", "cancelEnvironment" -> emptySet()
            "installSoftware" -> { LinuxSoftware.valueOf(text("software", 16)); setOf("software") }
            "bind" -> { text("id", 128); setOf("id") }
            "importFile", "importReviewed", "startImport" -> {
                text("id",128);text("path",1024);require(text("sha256",64).matches(Regex("[a-f0-9]{64}")))
                if(operation in setOf("importReviewed","startImport")) { text("reviewId",128);setOf("id","path","sha256","reviewId") }
                else setOf("id","path","sha256")
            }
            "cancelImport" -> { text("operationId",128);setOf("operationId") }
            "dropImport" -> { text("id",128);setOf("id") }
            "selectImport" -> emptySet()
            "selectChatAttachment" -> {
                text("messageId",4096)
                val id=text("attachmentId",36)
                require(runCatching { java.util.UUID.fromString(id).toString()==id }.getOrDefault(false))
                require(text("sha256",64).matches(Regex("[a-f0-9]{64}")))
                setOf("messageId","attachmentId","sha256")
            }
            "makeDirectory", "deleteEntry" -> { text("path",1024);setOf("path") }
            "moveEntry" -> { text("path",1024);text("destination",1024);setOf("path","destination") }
            "unbind" -> emptySet()
            "write" -> {
                text("path", 1024); text("text", 4096, true)
                val expected = args.getValue("expectedSha256")
                require(expected == JsonNull || expected.jsonPrimitive.let { it.isString && it.content.matches(Regex("[a-f0-9]{64}")) })
                setOf("path", "text", "expectedSha256")
            }
            "installDraft" -> { text("id", 128); require(text("sha256", 64).matches(Regex("[a-f0-9]{64}"))); setOf("id", "sha256") }
            "writeDraft" -> {
                text("draftId",128);text("path",1024)
                require(text("sha256",64).matches(Regex("[a-f0-9]{64}")))
                setOf("draftId","path","sha256")
            }
            else -> error("Unsupported workspace page control")
        }
        require(args.keys == keys)
        return PageWorkspaceControl(operation, args.toString())
    }
    fun document(value: JsonObject): PageDocumentControl {
        fun text(key: String) = value.getValue(key).jsonPrimitive.also { require(it.isString) }.content
        val max = value.getValue("maxBytes").jsonPrimitive.let { require(!it.isString); requireNotNull(it.intOrNull) }
        return when (text("kind")) {
            "document.open" -> {
                require(value.keys == setOf("kind", "mimeTypes", "maxBytes"))
                PageDocumentControl.Open(value.getValue("mimeTypes").jsonArray.map { it.jsonPrimitive.also { p -> require(p.isString) }.content }, max)
            }
            "document.save" -> {
                require(value.keys == setOf("kind", "name", "mimeType", "maxBytes"))
                PageDocumentControl.Save(text("name"), text("mimeType"), max)
            }
            else -> error("Unsupported page document control")
        }
    }
    fun parse(value: JsonObject): PostTurnTaskControl {
        fun text(key: String, max: Int): String = value.getValue(key).jsonPrimitive.let {
            require(it.isString && it.content.isNotBlank() && it.content.length <= max)
            it.content
        }
        fun attempt(): Int = value.getValue("attempt").jsonPrimitive.let {
            require(!it.isString); requireNotNull(it.intOrNull).also { count -> require(count in 0..3) }
        }
        return when (text("kind", 32)) {
            "postTurn.retry" -> {
                require(value.keys == setOf("kind", "jobId", "attempt"))
                PostTurnTaskControl.Retry(text("jobId", 256), attempt().also { require(it in 1..2) })
            }
            "postTurn.delete" -> {
                require(value.keys == setOf("kind", "jobId", "attempt", "status"))
                val status = PostTurnJobStatus.valueOf(text("status", 16))
                require(status != PostTurnJobStatus.RUNNING)
                PostTurnTaskControl.Delete(text("jobId", 256), attempt(), status)
            }
            "postTurn.stop" -> {
                require(value.keys == setOf("kind", "jobId", "attempt"))
                PostTurnTaskControl.Stop(text("jobId", 256), attempt().also { require(it in 1..3) })
            }
            "postTurn.cleanup" -> {
                require(value.keys == setOf("kind")); PostTurnTaskControl.Cleanup
            }
            else -> error("Unsupported page host action")
        }
    }
}
