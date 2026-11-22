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
    testRuntimeOnly("com.google.code.gson:gson:${Versions.gson}")
    testRuntimeOnly("org.apache.logging.log4j:log4j-core:${Versions.log4j}")
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

// 可执行回归检查由专用任务运行，不交给默认的 JUnit 扫描器。
tasks.test { exclude("**/PlayerBlockFeatureTest*.class") }

tasks.register("buildClient") {
    group = "build"
    dependsOn(rootProject.subprojects.filter { it.path.startsWith(":client:") }
        .map { it.tasks.named("build") })
}
