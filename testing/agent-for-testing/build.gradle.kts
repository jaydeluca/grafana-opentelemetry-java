import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow")
}

val versions = rootProject.extra["versions"] as Map<*, *>

// Inline the relocatePackages function from shadow.gradle
fun ShadowJar.relocatePackages() {
    // rewrite dependencies calling Logger.getLogger
    relocate("java.util.logging.Logger", "io.opentelemetry.javaagent.bootstrap.PatchLogger")

    // prevents conflict with library instrumentation, since these classes live in the bootstrap class loader
    relocate("io.opentelemetry.instrumentation", "io.opentelemetry.javaagent.shaded.instrumentation") {
        // Exclude resource providers since they live in the agent class loader
        exclude("io.opentelemetry.instrumentation.resources.*")
        exclude("io.opentelemetry.instrumentation.spring.resources.*")
    }

    // relocate(OpenTelemetry API) since these classes live in the bootstrap class loader
    relocate("io.opentelemetry.api", "io.opentelemetry.javaagent.shaded.io.opentelemetry.api")
    relocate("io.opentelemetry.semconv", "io.opentelemetry.javaagent.shaded.io.opentelemetry.semconv")
    relocate("io.opentelemetry.context", "io.opentelemetry.javaagent.shaded.io.opentelemetry.context")
    relocate("io.opentelemetry.extension.incubator", "io.opentelemetry.javaagent.shaded.io.opentelemetry.extension.incubator")

    // relocate the OpenTelemetry extensions that are used by instrumentation modules
    // these extensions live in the AgentClassLoader, and are injected into the user's class loader
    // by the instrumentation modules that use them
    relocate("io.opentelemetry.extension.aws", "io.opentelemetry.javaagent.shaded.io.opentelemetry.extension.aws")
    relocate("io.opentelemetry.extension.kotlin", "io.opentelemetry.javaagent.shaded.io.opentelemetry.extension.kotlin")
}

val bootstrapLibs by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val javaagentLibs by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val upstreamAgent by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    // and finally include everything from otel agent for testing
    upstreamAgent("io.opentelemetry.javaagent:opentelemetry-agent-for-testing:${versions["opentelemetryJavaagentAlpha"]}")
}

fun isolateClasses(jars: Iterable<File>): CopySpec {
    return copySpec {
        jars.forEach { jar ->
            from(zipTree(jar)) {
                into("inst")
                rename("^(.*)\\.class\$", "\$1.classdata")
                // Rename LICENSE file since it clashes with license dir on non-case sensitive FSs (i.e. Mac)
                rename("^LICENSE\$", "LICENSE.renamed")
                exclude("META-INF/INDEX.LIST")
                exclude("META-INF/*.DSA")
                exclude("META-INF/*.SF")
            }
        }
    }
}

tasks {
    jar {
        enabled = false
    }

    // building the final javaagent jar is done in 3 steps:

    // 1. all distro specific javaagent libs are relocated
    val relocateJavaagentLibs by registering(ShadowJar::class) {
        configurations = listOf(project.configurations.getByName("javaagentLibs"))

        archiveFileName.set("javaagentLibs-relocated.jar")

        duplicatesStrategy = DuplicatesStrategy.FAIL
        mergeServiceFiles()
        // mergeServiceFiles requires that duplicate strategy is set to include
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }

        exclude("**/module-info.class")
        relocatePackages()

        // exclude known bootstrap dependencies - they can't appear in the inst/ directory
        dependencies {
            exclude(dependency("io.opentelemetry:opentelemetry-api"))
            exclude(dependency("io.opentelemetry:opentelemetry-common"))
            exclude(dependency("io.opentelemetry:opentelemetry-context"))
            exclude(dependency("io.opentelemetry.semconv:opentelemetry-semconv"))
            exclude(dependency("io.opentelemetry.semconv:opentelemetry-semconv-incubating"))
            // events API and metrics advice API
            exclude(dependency("io.opentelemetry:opentelemetry-api-incubator"))
        }
    }

    // 2. the distro javaagent libs are then isolated - moved to the inst/ directory
    // having a separate task for isolating javaagent libs is required to avoid duplicates with the upstream javaagent
    // duplicatesStrategy in shadowJar won't be applied when adding files with with(CopySpec) because each CopySpec has
    // its own duplicatesStrategy
    val isolateJavaagentLibs by registering(Copy::class) {
        dependsOn(relocateJavaagentLibs)
        with(isolateClasses(relocateJavaagentLibs.get().outputs.files))

        into(layout.buildDirectory.dir("isolated/javaagentLibs"))
    }

    // 3. the relocated and isolated javaagent libs are merged together with the bootstrap libs (which undergo relocation
    // in this task) and the upstream javaagent jar; duplicates are removed
    shadowJar {
        configurations = listOf(project.configurations.getByName("bootstrapLibs"), project.configurations.getByName("upstreamAgent"))

        dependsOn(isolateJavaagentLibs)
        from(isolateJavaagentLibs.get().outputs)

        archiveClassifier.set("")

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        mergeServiceFiles("inst/META-INF/services")
        // mergeServiceFiles requires that duplicate strategy is set to include
        filesMatching("inst/META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }

        exclude("**/module-info.class")
        relocatePackages()

        manifest {
            attributes(
                "Main-Class" to "io.opentelemetry.javaagent.OpenTelemetryAgent",
                "Agent-Class" to "io.opentelemetry.javaagent.OpenTelemetryAgent",
                "Premain-Class" to "io.opentelemetry.javaagent.OpenTelemetryAgent",
                "Can-Redefine-Classes" to "true",
                "Can-Retransform-Classes" to "true",
                "Implementation-Vendor" to "Grafana Labs",
                "Implementation-Version" to "${versions["opentelemetryJavaagent"]}"
            )
        }
    }

    assemble {
        dependsOn(shadowJar)
    }
}
