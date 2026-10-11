package dev.solvit.core

/** Durable source anchor for post-turn work. Contains no prompt, message body or credentials. */
data class CompletedChatTurn(
    val id: String,
    val sessionId: String,
    val userMessageId: String,
    val assistantMessageId: String,
    val roleId: String?,
    val roleName: String?,
    val allowedPluginIds: Set<String>?,
    val requestedProfileId: String?,
    val requestedProviderId: String,
    val requestedModel: String,
    val createdAt: Long,
) {
    fun validate() {
        require(id == assistantMessageId && id.isNotBlank() && id.length <= 128)
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        require(userMessageId.isNotBlank() && userMessageId.length <= 128 && userMessageId != assistantMessageId)
        require((roleId == null) == (roleName == null) && (roleId == null) == (allowedPluginIds == null))
        if (roleId != null) AgentRole(roleId, roleName!!, pluginIds = allowedPluginIds!!).validate()
        require(requestedProfileId == null || requestedProfileId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(requestedProviderId.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
        require(requestedModel.isNotBlank() && requestedModel.length <= 256 && !requestedModel.any(Char::isISOControl))
        require(createdAt >= 0)
    }
    fun allows(pluginId: String) = allowedPluginIds == null || pluginId in allowedPluginIds
}

data class RecordedChatTurn(val sequence: Long, val turn: CompletedChatTurn)
