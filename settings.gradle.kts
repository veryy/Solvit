pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Solvit"
include(":core", ":provider-openai", ":chat-runtime", ":storage-sqlite", ":app")
include(":release-updates")
include(":plugin-runtime")
include(":plugin-market")
include(":system-runtime")
include(":workspace-android")
include(":terminal-android")

include(":provider-http", ":provider-anthropic")
include(":web-android")

include(":git-android")

// Opt-in remote native compatibility probe, never a dependency of the shipped app.
if (providers.gradleProperty("linuxProbe").orNull == "true") include(":linux-probe")
include(":linux-runtime")
include(":linux-android")
