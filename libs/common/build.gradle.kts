plugins { `java-library` }

dependencies {
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.kafka:spring-kafka")
    api(libs.resilience4j.spring.boot3)
    api(libs.resilience4j.micrometer)
    api("org.springframework.boot:spring-boot-starter-aop")
    api("io.opentelemetry:opentelemetry-api")
    api(libs.otel.annotations)
    compileOnly("org.springframework.boot:spring-boot-starter-jdbc")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.wiremock)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
