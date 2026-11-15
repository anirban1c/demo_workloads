dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:redpanda")
    testImplementation("org.awaitility:awaitility")
}
