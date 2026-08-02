plugins {
    `kotlin-dsl`
}

dependencies {
    // 核准檔 gradle/dependency-approvals.toml 的剖析器。
    // 核准項含多欄位與繁體中文 reason，手寫剖析器在「專為確保正確性而生」的
    // 程式碼裡風險過高。此依賴僅存在於建置邏輯，不進入任何發布的 artifact。
    implementation(libs.tomlj)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
