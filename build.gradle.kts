import com.knolux.build.depgate.task.CheckDependencyBaselineTask
import com.knolux.build.depgate.task.CheckDependencyCompatibilityTask
import com.knolux.build.depgate.task.DependencyGateTask
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
// 任務只註冊於根專案，**不**在 subprojects {} 每模組各註冊一份：同名任務會讓
// `./gradlew checkDependencyCompatibility` 同時觸發根與各子專案，且每模組各自拋出
// 例外會使「所有模組所有差異一次算完才決定成敗」（FR-012）在未加 --continue 時失效。
// 模組層的 build.gradle.kts 因此維持零變更。
//
// 一律 MUST NOT 掛在 check 之下：check 由 build 觸發，而閘門需要 git 歷史與遠端 ref，
// 掛上去會讓淺層 clone 或無 git 環境（如 source tarball）的一般建置直接失敗。
// ---------------------------------------------------------------------------

/** 各模組 runtimeClasspath 的解析根元件——下游消費者實際會拿到的傳遞依賴集合。 */
val moduleRuntimeRoots = subprojects.associate { module ->
    module.name to module.configurations.named("runtimeClasspath")
        .flatMap { it.incoming.resolutionResult.rootComponent }
}

fun DependencyGateTask.configureGateDefaults() {
    moduleRuntimeRoots.forEach { (moduleName, root) -> rootComponents.put(moduleName, root) }
    baselineDir.set(layout.projectDirectory.dir("gradle/dependency-baseline"))
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
