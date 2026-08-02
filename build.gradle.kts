import com.knolux.build.depgate.task.CheckDependencyBaselineTask
import com.knolux.build.depgate.task.CheckDependencyCompatibilityTask
import com.knolux.build.depgate.task.DependencyChangeReportTask
import com.knolux.build.depgate.task.DependencyGateTask
import com.knolux.build.depgate.task.ResolveModuleDependenciesTask
import com.knolux.build.depgate.task.UpdateDependencyBaselineTask
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.knolux"
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
        withSourcesJar()
    }

    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<Javadoc> {
        (options as StandardJavadocDocletOptions).apply {
            charSet("UTF-8")
            encoding("UTF-8")
            docEncoding("UTF-8")
            locale("zh_TW")
            addStringOption("Xdoclint:none", "-quiet")
        }
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                versionMapping {
                    usage("java-api") { fromResolutionOf("runtimeClasspath") }
                    usage("java-runtime") { fromResolutionResult() }
                }
                pom {
                    name.set(project.name)
                    description.set(project.description ?: "")
                    url.set("https://github.com/knolux/knolux-spring-boot-starters")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/knolux/knolux-spring-boot-starters")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: ""
                    password = System.getenv("GITHUB_TOKEN") ?: ""
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 傳遞依賴破壞性變更閘門（specs/001-dep-breaking-change-gate）
//
// 兩個 starter 都以 api scope 曝露核心依賴，傳遞依賴的 major 變動等同於對下游的
// 破壞性變更——即使自有原始碼一行未改。這些任務在合併前攔截該類變動。
//
// 四個閘門任務只註冊於根專案，**不**每模組各註冊一份：同名任務會讓
// `./gradlew checkDependencyCompatibility` 同時觸發根與各子專案，且每模組各自拋出
// 例外會使「所有模組所有差異一次算完才決定成敗」（FR-012）在未加 --continue 時失效。
// 每模組只有一個純產出資料、不做判定的 resolveDependencySnapshot（見下方說明）。
// 模組層的 build.gradle.kts 仍維持零變更——任務由此處統一註冊。
//
// 一律 MUST NOT 掛在 check 之下：check 由 build 觸發，而閘門需要 git 歷史與遠端 ref，
// 掛上去會讓淺層 clone 或無 git 環境（如 source tarball）的一般建置直接失敗。
// ---------------------------------------------------------------------------

/**
 * 解析各模組的外溢依賴，寫成快照供根專案的閘門任務讀取。
 *
 * 解析任務註冊在**模組**而非根專案，是因為根專案的任務去解析 `:<module>:runtimeClasspath`
 * 屬跨專案解析，Gradle 要求解析方持有該 configuration 的 exclusive lock，根專案拿不到，
 * 於是在 `--parallel` 下失敗：「Resolution of the configuration
 * ':<module>:runtimeClasspath' was attempted without an exclusive lock」。
 *
 * 這個錯誤**只在 parallel 模式出現**——本機序列建置完全看不到，是 CI 的 GRADLE_OPTS
 * 帶 `-Dorg.gradle.parallel=true` 才抓出來的。跨專案解析同時也是 Gradle isolated
 * projects 要禁止的模式，改掉不只是為了繞過眼前這個錯誤。
 *
 * 曾試過的替代方案：讓根專案自己宣告一個 configuration 去消費 `project(":<module>")`。
 * 該方案在解析階段就失敗——版本管理（Spring Boot BOM、AWS SDK BOM）是套在**模組**的
 * configuration 上的，換根專案解析就拿不到，模組裡不寫版號的依賴會解析成空版本。
 * 換句話說，能忠實代表「下游拿到什麼」的解析，只能發生在模組自己身上。
 */
val moduleSnapshotTasks = subprojects.associate { module ->
    module.name to module.tasks.register<ResolveModuleDependenciesTask>("resolveDependencySnapshot") {
        moduleName.set(module.name)
        rootComponent.set(
            module.configurations.named("runtimeClasspath")
                .flatMap { it.incoming.resolutionResult.rootComponent },
        )
        snapshotFile.set(module.layout.buildDirectory.file("dependency-gate/${module.name}.txt"))
    }
}

fun DependencyGateTask.configureGateDefaults() {
    moduleSnapshotTasks.forEach { (moduleName, snapshotTask) ->
        // 顯式 dependsOn：快照檔為 @Internal（閘門必須每次都執行，不接受 up-to-date 跳過），
        // 因此 Gradle 不會從屬性推導出任務依賴。
        dependsOn(snapshotTask)
        moduleSnapshots.put(moduleName, snapshotTask.flatMap { it.snapshotFile })
    }
    baselineDir.set(layout.projectDirectory.dir("gradle/dependency-baseline"))
    approvalsFile.set(layout.projectDirectory.file("gradle/dependency-approvals.toml"))
    repoDir.set(layout.projectDirectory)
}

tasks.register<CheckDependencyCompatibilityTask>("checkDependencyCompatibility") {
    configureGateDefaults()
    reportFile.set(layout.buildDirectory.file("reports/dependency-gate/gate-report.md"))
}

tasks.register<CheckDependencyBaselineTask>("checkDependencyBaseline") {
    configureGateDefaults()
}

tasks.register<UpdateDependencyBaselineTask>("updateDependencyBaseline") {
    configureGateDefaults()
}

tasks.register<DependencyChangeReportTask>("dependencyChangeReport") {
    configureGateDefaults()
    reportFile.set(layout.buildDirectory.file("reports/dependency-gate/change-report.md"))
}
