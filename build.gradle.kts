plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.dependency.management) apply false
}

val otelAgent: Configuration by configurations.creating { isTransitive = false }
val otelExtensions: Configuration by configurations.creating { isTransitive = false }

dependencies {
    otelAgent(libs.otel.javaagent)
    otelExtensions(libs.otel.baggage.processor)
}

/** Copies the OTel Java agent and the baggage-processor extension into otel/ for the Docker images. */
tasks.register<Copy>("downloadOtelAgent") {
    from(otelAgent) { rename { "opentelemetry-javaagent.jar" } }
    from(otelExtensions) { rename { "baggage-processor-extension.jar" } }
    into(layout.projectDirectory.dir("otel"))
}

allprojects {
    group = "com.demo.workloads"
    version = "0.1.0"
    repositories { mavenCentral() }
}

subprojects {
    if (path == ":load-tests") return@subprojects
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }
    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports { mavenBom("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.spring.boot.get()}") }
    }
    tasks.withType<JavaCompile> { options.compilerArgs.add("-parameters") }
    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}

/** Every service module: Spring Boot app depending on libs:common. */
configure(subprojects.filter { it.path.startsWith(":services:") }) {
    apply(plugin = "org.springframework.boot")
    dependencies {
        "implementation"(project(":libs:common"))
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testImplementation"("org.springframework.boot:spring-boot-testcontainers")
        "testImplementation"("org.testcontainers:junit-jupiter")
        "testImplementation"(rootProject.libs.wiremock)
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { archiveFileName.set("app.jar") }
    tasks.named<Jar>("jar") { enabled = false }
}
