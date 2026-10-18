import org.gradle.api.tasks.bundling.Jar

// The mod toolchain of this workspace: applying cn.enaium.fabricpvz.loom supplies the game
// distribution (the game, the API modules, the real Fabric Loader and Mixin, ASM, SLF4J/Log4j and
// LWJGL, on the compile, test and launch class paths), the launcher class path and JUnit for the
// the end-to-end test, the runClient task and the publication of the mod jar under mavenLocal
// coordinates. A mod never declares any of those artifacts by hand; see loom.
//
// The version is the plugin's own (every artifact of this workspace is released under one number); the
// plugin itself resolves from the Gradle Plugin Portal, which serves it (settings.gradle.kts lists the
// portal first and keeps mavenLocal after it as an optional fallback for a version the portal does not
// serve yet).
plugins {
    id("cn.enaium.fabricpvz.loom") version "1.0.8"
}

dependencies {
    // Which game distribution this mod is built against: the version package of the game's release,
    // i.e. the asset pvz-portable-<version>-online.zip of the GitHub release of FabricPvZ/pvz-portable.
    // The toolchain downloads it once into ~/.gradle/caches/pvz (and records its SHA-256 next to it) and
    // unpacks it into build/pvz/dist - the game jar and the version manifest next to it. The game jar is
    // a file of the class paths (no repository holds it), and every library the manifest names - the
    // loader, Mixin, ASM, the logging stack and LWJGL with the natives of this machine - is resolved as
    // a Maven coordinate. The version is stated even though it is the toolchain's default: the line says
    // against which game this mod was built. While working on the game's own code, point it at the
    // distribution that build writes instead (pvz(files("../PvZ-Portable/build/distributions")) or
    // pvz(files("../PvZ-Portable/build/mcversion/PvZ-Portable-<version>"))).
    pvz("cn.enaium.fabricpvz:pvz-portable:1.2.4")
}

group = "cn.enaium.fabricpvz"
version = "1.1.0"
description = "Content mod for the PvZ port: red peashooter, repeater and gatling pea plants"

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "PvZ Content Mod",
            "Implementation-Version" to project.version,
            "Fabric-Mod-Id" to "pvz-content-mod",
        )
    }
}
