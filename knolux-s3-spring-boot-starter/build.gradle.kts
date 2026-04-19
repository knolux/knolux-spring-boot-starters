description = "S3 Starter for Spring Boot, supports Path Style (SeaweedFS / MinIO compatible)"

dependencies {
    implementation(platform(libs.awssdk.bom))
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.awssdk.s3)
    implementation(libs.awssdk.netty.nio.client)
    compileOnly(libs.spring.boot.starter.actuator)
    compileOnly(libs.lombok)
    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}
