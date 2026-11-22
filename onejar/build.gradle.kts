subprojects {
    val shadowImplementation by configurations.getting

    dependencies {
        shadowImplementation(project(":client"))
        shadowImplementation(project(":codec"))
        shadowImplementation(project(":server"))
    }

    tasks {
        shadowJar {
            configurations = listOf(project.configurations.shadow.get())
        }
    }
}

tasks.register("buildOneJar") {
    group = "build"
    dependsOn(rootProject.subprojects.filter { it.path.startsWith(":onejar:") }
        .map { it.tasks.named("build") })
}

tasks {
    clean {
        delete("$projectDir/../build")
    }
}
