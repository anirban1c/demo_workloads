plugins {
    java
    alias(libs.plugins.gatling)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

/** Black-box tests against the running stack (make up): one pass per journey + journey traces in the trace store. */
val e2eTest: SourceSet by sourceSets.creating

dependencies {
    gatlingImplementation(libs.datafaker)
    "e2eTestImplementation"(platform("org.junit:junit-bom:5.13.4"))
    "e2eTestImplementation"("org.junit.jupiter:junit-jupiter")
    "e2eTestImplementation"("org.assertj:assertj-core:3.27.6")
    "e2eTestImplementation"("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    "e2eTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

// Knobs passed on the Gradle command line (-Dtps=80 ...) are forwarded into the forked Gatling / test JVMs.
val knobs = listOf("gateway", "tps", "duration", "journey", "vendor", "faultDelay", "faultPct", "wiremock",
    "users", "maxTps", "spikeTps", "pollTimeout", "profile", "stepSeconds", "tempo", "jaeger")
fun forwarded(): Map<String, Any> =
    knobs.mapNotNull { k -> System.getProperty(k)?.let { k to it } }.toMap()

tasks.withType<io.gatling.gradle.GatlingRunTask>().configureEach {
    systemProperties = forwarded()
    nonInteractive = true
}

/** Seeds mock users through the public API and writes src/gatling/resources/users.csv for the simulations. */
tasks.register<JavaExec>("seed") {
    group = "load"
    description = "Create mock users/profiles/accounts via the gateway (Datafaker) and write users.csv"
    classpath = sourceSets["gatling"].runtimeClasspath
    mainClass.set("demo.load.Seed")
    systemProperties(forwarded())
    args(layout.projectDirectory.file("src/gatling/resources/users.csv").asFile.absolutePath)
}

tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "Runs every journey against the running stack and asserts journey-tagged traces"
    testClassesDirs = e2eTest.output.classesDirs
    classpath = e2eTest.runtimeClasspath
    useJUnitPlatform()
    systemProperties(forwarded())
    outputs.upToDateWhen { false }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
