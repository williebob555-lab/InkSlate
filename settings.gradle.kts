pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "InkSlate"
include(":core")
include(":app")
include(":desktop")
// InkSheets, the sheet-music app built on InkSlate: its library, setlists and instruments.
include(":sheets-core")
include(":sheets-desktop")
