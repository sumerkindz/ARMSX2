pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Use the Maven Central CDN directly as the primary endpoint. The canonical
        // mavenCentral() shorthand resolves through repo.maven.apache.org; some CI
        // runner routes can return false 404s for a broad set of published artifacts.
        maven {
            name = "MavenCentralCDN"
            url = uri("https://repo1.maven.org/maven2")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Direct Maven Central CDN endpoint, before the shorthand repository.
        maven {
            name = "MavenCentralCDN"
            url = uri("https://repo1.maven.org/maven2")
        }
        mavenCentral()
        // Discord Social SDK ships as a local .aar. Consumed as a dependency rather than unpacked
        // by hand: hand-staging silently dropped its consumer proguard rules (crash at boot), its
        // <queries> entry, and its androidx.browser dependency (sign-in completed and the result
        // was discarded). An .aar is its manifest and dependency graph too, not just a .so.
        // The SDK .aar itself is NOT in this repository: Discord's terms permit shipping it inside
        // a working application but not republishing the raw SDK, so private release CI supplies it
        // through DISCORD_SDK_DIR. app/libs stays on the list for any other local .aar and for
        // developers who keep their own copy there.
        val discordSdkDir = providers.environmentVariable("DISCORD_SDK_DIR").orNull
            ?: providers.gradleProperty("DISCORD_SDK_DIR").orNull
        flatDir { dirs(listOfNotNull("app/libs", discordSdkDir)) }
    }
}

rootProject.name = "ARMSX2"
include(":app")
 