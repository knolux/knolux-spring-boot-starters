package com.knolux.build.depgate.task

import com.knolux.build.depgate.DeltaCalculator
import com.knolux.build.depgate.DeltaKind
import com.knolux.build.depgate.DependencySet
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * 以當前 `runtimeClasspath` 解析結果重新產生各模組的基準線檔。
 *
 * **明確不做的事**：不執行任何 git 操作、不 commit。變更留在工作區由維護者檢視後併入 PR——
 * 這正是 R2 選擇「簽入檔案」而非「執行期重建」的核心價值：基準線變動會直接出現在 PR diff
 * 上供人審閱。CI 不得執行此任務（憲章 Governance 明文禁止直接推送 `main`）。
 *
 * 註：清除已失配的核准項（FR-017）於 US4 導入核准檔後一併加入。
 */
abstract class UpdateDependencyBaselineTask : DependencyGateTask() {

    init {
        description = "以當前解析結果重新產生依賴基準線檔"
    }

    @TaskAction
    fun update() {
        baselineDir.get().asFile.mkdirs()

        currentDependencySets().forEach { (moduleName, current) ->
            val file = baselineFile(moduleName)
            val previous = file.readPreviousOrNull(moduleName)

            // 一律 UTF-8 無 BOM；換行由 DependencySet.serialize() 固定為 LF。
            // 交給平台決定會讓 Windows 開發者每次都產生整檔 diff。
            file.writeText(current.serialize(), Charsets.UTF_8)

            logger.lifecycle("${file.relativeToRepo()}：${summarize(previous, current)}")
        }
    }

    private fun summarize(previous: DependencySet?, current: DependencySet): String {
        if (previous == null) return "新建，共 ${current.entries.size} 筆"

        val deltas = DeltaCalculator.calculate(previous, current)
        if (deltas.isEmpty()) return "無變動，共 ${current.entries.size} 筆"

        val byKind = deltas.groupingBy { it.kind }.eachCount()
        val added = byKind[DeltaKind.ADDED] ?: 0
        val removed = byKind[DeltaKind.REMOVED] ?: 0
        val changed = deltas.size - added - removed

        return "共 ${current.entries.size} 筆（新增 $added、移除 $removed、版本變更 $changed）"
    }

    /**
     * 讀取既有內容以計算變動筆數。
     *
     * 檔案壞掉時**不**中止：這個任務的職責就是把檔案重新寫對，因為舊檔壞了而拒絕修復
     * 會讓維護者無路可走。但必須留下 WARN——靜默當成「新建」會讓一次意外的內容毀損
     * 看起來像正常的首次產生。
     */
    private fun File.readPreviousOrNull(moduleName: String): DependencySet? {
        if (!isFile) return null

        return runCatching { DependencySet.parse(moduleName, readText(Charsets.UTF_8).removePrefix(UTF8_BOM)) }
            .onFailure { logger.warn("既有基準線 ${relativeToRepo()} 無法解析（${it.message}），將直接覆寫。") }
            .getOrNull()
    }

    private companion object {
        const val UTF8_BOM = "﻿"
    }
}
