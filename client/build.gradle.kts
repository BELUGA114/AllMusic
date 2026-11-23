subprojects {
    val shadowImplementation by configurations.getting

    dependencies {
        shadowImplementation(project(":client"))
        shadowImplementation(project(":codec"))
    }

    tasks {
        shadowJar {
            configurations = listOf(project.configurations.shadow.get())
        }
    }
}

tasks {
    clean {
        delete("$projectDir/../build")
    }
}

dependencies {
    shadowImplementation(project(":codec"))

    shadowImplementation("org.apache.httpcomponents.client5:httpclient5:${Versions.httpclient5}")
    shadowImplementation("org.apache.httpcomponents.core5:httpcore5:${Versions.httpcore5}")
    shadowImplementation("org.apache.httpcomponents.core5:httpcore5-h2:${Versions.httpcore5_h2}")

    compileOnly("com.google.code.gson:gson:${Versions.gson}")
    compileOnly("org.lwjgl:lwjgl:${Versions.lwjgl}")
    compileOnly("org.lwjgl:lwjgl-openal:${Versions.lwjgl}")
    compileOnly("org.apache.logging.log4j:log4j-core:${Versions.log4j}")
    testImplementation("com.google.code.gson:gson:${Versions.gson}")
    testImplementation("org.apache.logging.log4j:log4j-core:${Versions.log4j}")
    testImplementation("io.netty:netty-buffer:${Versions.netty}")
    testRuntimeOnly("org.lwjgl:lwjgl:${Versions.lwjgl}")
    testRuntimeOnly("org.lwjgl:lwjgl-openal:${Versions.lwjgl}")
}

val verifyPlayerBlock by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.coloryr.allmusic.client.core.PlayerBlockFeatureTest")
    val results = layout.buildDirectory.dir("player-block-tests")
    args(results.get().asFile.absolutePath)
    doFirst { results.get().asFile.mkdirs() }
}

tasks.check { dependsOn(verifyPlayerBlock) }

val verifyClientCompatibility by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.coloryr.allmusic.client.core.ClientCompatibilityTest")
    val results = layout.buildDirectory.dir("compatibility-tests")
    args(results.get().asFile.absolutePath)
    doFirst { results.get().asFile.mkdirs() }
}

val verifyFlac by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.coloryr.allmusic.client.core.FlacPlaybackTest")
}

tasks.check { dependsOn(verifyClientCompatibility, verifyFlac) }

// 可执行回归检查由专用任务运行，不交给默认的 JUnit 扫描器。
tasks.test {
    exclude("**/PlayerBlockFeatureTest*.class", "**/ClientCompatibilityTest*.class", "**/FlacPlaybackTest*.class", "**/HudResourcesTest*.class")
}

tasks.register("buildClient") {
    group = "build"
    dependsOn(rootProject.subprojects.filter { it.path.startsWith(":client:") }
        .map { it.tasks.named("build") })
}
