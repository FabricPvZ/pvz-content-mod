pluginManagement {
    repositories {
        // The mod toolchain plugin (cn.enaium.fabricpvz.loom) comes from the Gradle Plugin Portal,
        // which serves the version this build states (1.0.1, via `(cd ../loom && ./gradlew
        // publishPlugins)`).
        gradlePluginPortal()
        // An optional fallback, deliberately *after* the portal: a version the portal does not serve
        // yet - one built from a local checkout with `(cd ../loom && ./gradlew publishToMavenLocal)` -
        // can still be resolved from mavenLocal. A version the portal does serve, 1.0.1 among them,
        // always resolves from the portal and never from here.
        mavenLocal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        // The API modules and the loader resolve net.fabricmc:fabric-loader and
        // net.fabricmc:sponge-mixin from Fabric's maven (neither is on Maven Central); the API's own
        // event bus is in-tree.
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
    }
}

rootProject.name = "pvz-content-mod"
