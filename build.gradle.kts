import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.1" apply false
}

// 统一项目配置
allprojects {
    // 应用插件到子项目
    apply(plugin = "java")
    apply(plugin = "com.gradleup.shadow")

    group = "com.coloryr"
    version = "4.2.5"

    // 设置项目JDK版本
    java.sourceCompatibility = JavaVersion.VERSION_1_8
    java.targetCompatibility = JavaVersion.VERSION_1_8

    val shadowImplementation = configurations.maybeCreate("shadowImplementation")
    configurations.named("shadow").get().extendsFrom(shadowImplementation)
    configurations.named("implementation").get().extendsFrom(shadowImplementation)

    repositories {
        mavenCentral()
        maven("https://maven.maxhenkel.de/repository/public")
    }

    dependencies {
        compileOnly("io.netty:netty-buffer:${Versions.netty}")
    }

    tasks.withType<ShadowJar> {
        mutableListOf(
            "org.apache.hc.core5",
            "org.apache.hc.client5",
            "org.slf4j",
        ).forEach { relocate(it, "com.coloryr.allmusic.libs.$it") }
    }

    tasks.named("jar") {
        dependsOn("shadowJar")
    }
}

tasks.register("buildAll") {
    group = "build"
    dependsOn(rootProject.subprojects.filter { it.path.startsWith(":client:") }
        .map { it.tasks.named("build") })
    dependsOn(rootProject.subprojects.filter { it.path.startsWith(":server:") }
        .map { it.tasks.named("build") })
    dependsOn(rootProject.subprojects.filter { it.path.startsWith(":onejar:") }
        .map { it.tasks.named("build") })
}
