pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "BootTV"
include(":app")
// Baseline Profile generator and startup / scrolling benchmarks (run on a device or the emulator).
include(":baselineprofile")
