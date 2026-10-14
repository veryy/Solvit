package dev.solvit.linux

/** Official, immutable Linux archive. Neither URL nor checksum comes from plugin code. */
object NodeCatalog {
    const val VERSION = "24.21.0"
    data class Source(val url: String, val sha256: String, val archivePrefix: String)
    fun forAbi(abi: String): Source? = when (abi) {
        "arm64-v8a" -> Source("https://nodejs.org/dist/v$VERSION/node-v$VERSION-linux-arm64.tar.xz",
            "6ad1325edbdb5649c379b75a237147a666c95d4f9ae8d340fef2d1575d289ad2", "node-v$VERSION-linux-arm64")
        "x86_64" -> Source("https://nodejs.org/dist/v$VERSION/node-v$VERSION-linux-x64.tar.xz",
            "fd8e59d5a511510f6a298afb548f18c7d2b1be404d8b4a27d94fbe49f56cb2d6", "node-v$VERSION-linux-x64")
        else -> null
    }
}
