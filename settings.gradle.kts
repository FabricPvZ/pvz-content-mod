pluginManagement {
    repositories {
        // The mod toolchain plugin (cn.enaium.fabricpvz.loom) comes from the Gradle Plugin Portal.
        gradlePluginPortal()
        mavenCentral()
        // Temporary: the plugin's first publication is queued for review on the portal
        // (https://plugins.gradle.org/plugin/cn.enaium.fabricpvz.loom), so until plugins.gradle.org
        // serves it, the copy `loom`'s `./gradlew publishToMavenLocal` writes is the bootstrap
        // fallback. Remove this line once the review has accepted the plugin - the portal resolves it
        // on its own then, and nothing has to be published locally.
        mavenLocal()
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
