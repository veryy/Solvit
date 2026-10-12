package dev.solvit.chat

import dev.solvit.core.AgentRole
import dev.solvit.core.DEFAULT_SYSTEM_PROMPT

/** Named contributions with one identity owner, inspired by DSH prompt sections. */
internal data class ChatPromptSection(val name: String, val source: String, val text: String)

internal object ChatContextComposer {
    fun sections(role: AgentRole?, globalInstructions: String): List<ChatPromptSection> {
        val name = role?.name ?: "Solvit"
        // Like Operit's replacement of BEGIN_SELF_INTRODUCTION_SECTION, a
        // character replaces the built-in introduction. Explicit user rules
        // remain intact; don't try to rewrite arbitrary user-authored prompts.
        val userRules = globalInstructions.takeUnless {
            role != null && it.trim() == DEFAULT_SYSTEM_PROMPT
        }.orEmpty()
        return listOf(
            ChatPromptSection("identity", role?.id ?: "builtin.default-role",
                "你在本次对话中的角色名称是“$name”。这是当前生效的角色身份。角色信息就在本段指令中，不需要搜索、查询记忆或调用工具获取。" +
                    "当用户问你是谁、你的角色或名字时，明确回答这个角色名称，并按角色设定交流。" +
                    "角色设定优先于全局提示词中的默认身份与历史自我介绍；角色是对话设定，不改变实际模型来源。"),
            ChatPromptSection("user-rules", "model-settings", userRules),
            ChatPromptSection("role-instructions", role?.id ?: "builtin.default-role",
                role?.prompt ?: "你是 Solvit，一个帮助用户聊天、编写代码和按授权执行任务的 AI 助手。"),
        ).filter { it.text.isNotBlank() }
    }

    fun render(sections: List<ChatPromptSection>): String {
        require(sections.map { it.name }.distinct().size == sections.size) { "Duplicate prompt section" }
        return sections.joinToString("\n\n") { "[${it.name}]\n${it.text}" }
    }
}
