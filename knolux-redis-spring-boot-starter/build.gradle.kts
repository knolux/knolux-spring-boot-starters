description = "Redis Starter for Spring Boot, supports Sentinel and Standalone"

dependencies {
    implementation(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.data.redis)
    compileOnly(libs.spring.boot.starter.actuator)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.redis)
    testRuntimeOnly(libs.junit.platform.launcher)
}
