dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation(libs.bucket4j.core)
    implementation(libs.bucket4j.lettuce)
    testImplementation("org.testcontainers:redpanda")
    testImplementation("org.awaitility:awaitility")
}
