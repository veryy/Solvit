package dev.solvit.git

import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

/** A transport-only config without global config, rewrites, headers, cookies or credential helpers. */
internal class GitTransportRepository(builder: FileRepositoryBuilder) : FileRepository(builder) {
    private var isolated: FileBasedConfig? = null
    init {
        val original = super.getConfig()
        val clean = FileBasedConfig(original.file, fs)
        clean.fromText(original.toText())
        for (section in clean.sections.toList()) {
            if (section.lowercase() in setOf("http", "https", "credential", "url", "remote", "include", "includeif", "filter")) {
                clean.getSubsections(section).toList().forEach { clean.unsetSection(section, it) }
                clean.unsetSection(section, null)
            }
        }
        clean.setBoolean("http", null, "sslVerify", true)
        clean.setString("http", null, "followRedirects", "false")
        clean.setInt("http", null, "maxRedirects", 0)
        clean.setString("http", null, "cookieFile", "")
        clean.setBoolean("http", null, "saveCookies", false)
        clean.setStringList("http", null, "extraHeader", emptyList())
        clean.setInt("http", null, "postBuffer", 1024 * 1024)
        clean.setInt("protocol", null, "version", 2)
        isolated = clean
    }
    override fun getConfig(): FileBasedConfig = isolated ?: super.getConfig()
}
