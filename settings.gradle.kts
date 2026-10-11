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
