import org.gradle.api.tasks.bundling.Jar

// The mod toolchain of this workspace: applying cn.enaium.fabricpvz.loom supplies the game
// distribution (the game, the API modules, the real Fabric Loader and Mixin, ASM, SLF4J/Log4j and
// LWJGL, on the compile, test and launch class paths), the launcher class path and JUnit for the
// the end-to-end test, the runClient task and the publication of the mod jar under mavenLocal
// coordinates. A mod never declares any of those artifacts by hand; see loom.
//
// The version is the plugin's own (every artifact of this workspace is released under 1.0.0); the
// plugin itself comes from the Gradle Plugin Portal (settings.gradle.kts lists the local fallback
// used while the first publication is under review, and says when to remove it).
plugins {
    id("cn.enaium.fabricpvz.loom") version "1.0.0"
}

dependencies {
    // Which game distribution this mod is built against: the release of the game, i.e. the asset
    // pvz-portable-<version>.zip of the GitHub release of FabricPvZ/pvz-portable. The toolchain
    // downloads it once into ~/.gradle/caches/pvz (and records its SHA-256 next to it) and unpacks it
    // into build/pvz/dist, so this mod is built against exactly the archive the release attaches, with
    // nothing of the game's build directory in play. The version is stated even though it is the
    // toolchain's default: the line says against which game this mod was built. While working on the
    // game's own code, point it at the distribution that build writes instead
    // (pvz(files("../PvZ-Portable/build/distributions"))).
    pvz("cn.enaium.fabricpvz:pvz-portable:1.0.0")
}

group = "cn.enaium.fabricpvz"
version = "1.0.0"
description = "Content mod for the PvZ port: two custom plants and one custom zombie"

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "PvZ Content Mod",
            "Implementation-Version" to project.version,
            "Fabric-Mod-Id" to "pvz-content-mod",
        )
    }
}
