rootProject.name = "AllMusic"

include(":codec")

include(":client")

include(":client:fabric_26_3")

include(":server")

include(":server:fabric_26_3")

include(":onejar")

include(":onejar:fabric_26_3")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
}
