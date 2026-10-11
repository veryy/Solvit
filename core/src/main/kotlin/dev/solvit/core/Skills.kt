package dev.solvit.core

data class SkillSummary(val name: String, val description: String, val resources: List<String>)
data class SkillContent(val summary: SkillSummary, val instructions: String)
data class SkillBundle(val directory: String?, val files: Map<String, String>)
interface SkillLibraryService {
    fun preview(bytes: ByteArray): SkillBundle
    suspend fun list(): List<SkillSummary>
    suspend fun load(name: String): SkillContent
    suspend fun importSkill(bundle: SkillBundle, replace: Boolean)
    suspend fun remove(name: String)
    suspend fun export(name: String): String
}
val SkillLibraryServiceKey = ServiceKey("skills.library", SkillLibraryService::class)
