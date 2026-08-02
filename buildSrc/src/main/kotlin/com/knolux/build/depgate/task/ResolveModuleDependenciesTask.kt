package com.knolux.build.depgate.task

import com.knolux.build.depgate.DependencyGraphReader
import com.knolux.build.depgate.DependencySetBuilder
import com.knolux.build.depgate.DependencySnapshot
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * 解析**本模組**的 `runtimeClasspath`，把外溢依賴集合寫成快照供根專案的閘門任務讀取。
 *
 * 這是唯一觸碰 Gradle 解析結果的任務，且刻意註冊在**各模組**而非根專案：解析
 * `:<module>:runtimeClasspath` 需要持有該 configuration 的 exclusive lock，根專案的任務
 * 拿不到，於是在 `--parallel` 下拋出「Resolution of the configuration … was attempted
 * without an exclusive lock」。這個錯誤只在 parallel 模式出現——本機序列建置完全看不到，
 * 是 CI 的 `GRADLE_OPTS` 帶 `-Dorg.gradle.parallel=true` 才抓出來的。
 *
 * 跨專案解析同時也是 Gradle isolated projects 要禁止的模式，因此改成模組自己解析
 * 不只是為了繞過眼前這個錯誤。
 *
 * 判定（差異分類、核准比對、成敗）一律留在根專案的 [DependencyGateTask]：FR-012 要求
 * 所有模組所有差異一次算完才決定成敗，而那在「每個模組各自拋例外」的結構下做不到。
 * 本任務因此只產出資料，不做任何取捨——與 [DependencyGraphReader] 同樣的分工理由。
 *
 * 屬性全標 `@Internal`：`ResolvedComponentResult` 無法作為輸入指紋，而解析結果會隨遠端
 * 版本變動，本來就不該被 up-to-date 檢查略過。任務因此每次都執行。
 */
abstract class ResolveModuleDependenciesTask : DefaultTask() {

    /** 模組名，例如 `knolux-redis-spring-boot-starter`。 */
    @get:Internal
    abstract val moduleName: Property<String>

    /** 本模組 `runtimeClasspath` 的解析根元件——下游消費者實際會拿到的傳遞依賴集合。 */
    @get:Internal
    abstract val rootComponent: Property<ResolvedComponentResult>

    /** 快照輸出位置（`<module>/build/dependency-gate/<module>.txt`）。 */
    @get:Internal
    abstract val snapshotFile: RegularFileProperty

    init {
        group = "verification"
        description = "解析本模組的外溢依賴並寫出快照，供依賴相容性閘門讀取"
    }

    @TaskAction
    fun resolve() {
        val module = moduleName.get()
        val dependencySet = DependencySetBuilder.build(module, DependencyGraphReader.read(rootComponent.get()))

        DependencySnapshot.write(dependencySet, snapshotFile.get().asFile)
        logger.info("$module：解析出 ${dependencySet.entries.size} 筆外溢依賴")
    }
}
