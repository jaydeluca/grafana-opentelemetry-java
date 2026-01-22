group = "com.grafana"
version = "10.0.0-SNAPSHOT" // the version of the actual release is set during the release process in build-release.sh

val otelInstrumentationVersion = "2.24.0"

buildscript {
    repositories {
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }
    dependencies {
        classpath("com.diffplug.spotless:spotless-plugin-gradle:8.1.0")
        classpath("com.gradleup.shadow:shadow-gradle-plugin:9.3.1")
        classpath("io.opentelemetry.instrumentation:gradle-plugins:2.24.0-alpha")
    }
}

// Define versions and deps at root level so subprojects can access them
val versions = mapOf(
    "opentelemetryJavaagent" to otelInstrumentationVersion,
    "opentelemetryJavaagentAlpha" to "$otelInstrumentationVersion-alpha",
    "bytebuddy" to "1.18.4",
    "autoservice" to "1.1.1",
    "junit" to "6.0.2",
    "logUnit" to "2.0.0",
    "assertj" to "3.27.6"
)

val deps = mapOf(
    "bytebuddy" to "net.bytebuddy:byte-buddy-dep:${versions["bytebuddy"]}",
    "autoservice" to listOf(
        "com.google.auto.service:auto-service:${versions["autoservice"]}",
        "com.google.auto.service:auto-service-annotations:${versions["autoservice"]}"
    )
)

// Make versions and deps available to subprojects via extra properties
extra["versions"] = versions
extra["deps"] = deps

subprojects {
    version = rootProject.version

    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    repositories {
        mavenCentral()
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat()
            licenseHeaderFile(rootProject.file("buildscripts/spotless.license.java"), "(package|import|public)")
            target("src/**/*.java")
        }

        format("misc") {
            // not using "**/..." to help keep spotless fast
            target(
                ".gitignore",
                ".gitattributes",
                ".gitconfig",
                ".editorconfig",
                "*.md",
                "src/**/*.md",
                "docs/**/*.md",
                "*.sh",
                "src/**/*.properties"
            )
            leadingTabsToSpaces()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    val subprojectVersions = rootProject.extra["versions"] as Map<*, *>

    dependencies {
        // these serve as a test of the instrumentation boms
        add("implementation", platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom-alpha:${subprojectVersions["opentelemetryJavaagentAlpha"]}"))

        add("testImplementation", "org.assertj:assertj-core:${subprojectVersions["assertj"]}")

        add("testImplementation", enforcedPlatform("org.junit:junit-bom:${subprojectVersions["junit"]}"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter-api:${subprojectVersions["junit"]}")
        add("testImplementation", "org.junit.jupiter:junit-jupiter-params:${subprojectVersions["junit"]}")
        add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine:${subprojectVersions["junit"]}")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:${subprojectVersions["junit"]}")

        add("testImplementation", "io.github.netmikey.logunit:logunit-core:${subprojectVersions["logUnit"]}")
        add("testRuntimeOnly", "io.github.netmikey.logunit:logunit-jul:${subprojectVersions["logUnit"]}")
    }

    tasks.named<Test>("test") {
        useJUnitPlatform()
    }

    tasks.named<JavaCompile>("compileJava") {
        options.release.set(8)
        options.compilerArgs.addAll(listOf("-Werror", "-Xlint:-options")) //Java 8 is deprecated as of JDK 21
    }

    tasks.named<JavaCompile>("compileTestJava") {
        options.release.set(17)
    }
}
