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
        maven("https://jitpack.io")
    }
}
rootProject.name = "RetroTV"

// ---- Moonlight (PC game streaming engine) ----
// Downloaded automatically the first time you build, so the RetroTV folder stays small.
val moonlightVersion = "v12.1"
val moonlightDir = file("third_party/moonlight")
if (!file("third_party/moonlight/app/src/main/jni/moonlight-core/moonlight-common-c/enet/host.c").exists()) {
    println("RetroTV: downloading Moonlight $moonlightVersion streaming engine...")
    moonlightDir.deleteRecursively()
    moonlightDir.parentFile.mkdirs()
    val clone = ProcessBuilder(
        "git", "clone", "--depth", "1", "--branch", moonlightVersion,
        "--recurse-submodules", "--shallow-submodules",
        "https://github.com/moonlight-stream/moonlight-android.git", moonlightDir.absolutePath,
    ).inheritIO().start()
    if (clone.waitFor() != 0) {
        throw GradleException("Couldn't download Moonlight. Check the internet connection and that Git is installed.")
    }
}

include(":app")
include(":moonlight")
