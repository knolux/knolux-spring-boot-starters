description = "Redis Starter for Spring Boot, supports Standalone, Sentinel and Cluster"

dependencies {
    implementation(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.data.redis)
    compileOnly(libs.spring.boot.starter.actuator)
    // Azure Entra ID token 驗證為選用能力：比照 Actuator 採 compileOnly，
    // 未啟用 knolux.redis.azure.entra-id 的使用者不必背這串傳遞依賴
    // （msal4j、azure-identity、java-jwt），下游解析出的依賴集合也不會變動。
    // 啟用但缺 jar 時由 EntraIdTokenAuthConfigFactory fail-fast 並印出座標。
    compileOnly(libs.redis.authx.entraid)
    compileOnly(libs.lombok)
    annotationProcessor(libs.spring.boot.configuration.processor)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.redis)
    testImplementation(libs.redis.authx.entraid)
    testRuntimeOnly(libs.junit.platform.launcher)
}
