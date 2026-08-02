package com.knolux.build.depgate.task

import com.knolux.build.depgate.BaselineLookup
import com.knolux.build.depgate.DeltaCalculator
import com.knolux.build.depgate.DependencySet
import com.knolux.build.depgate.GateReport
import com.knolux.build.depgate.GateVerdict
import com.knolux.build.depgate.GitBaselineSource
import com.knolux.build.depgate.GitRefs
import com.knolux.build.depgate.ReleaseTagLookup
import com.knolux.build.depgate.ReleaseTagSelector
import com.knolux.build.depgate.ReportRenderer
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

/**
 * 發版揭露報告：列出自**上一個發布版本**以來的全部外溢依賴變動（spec User Story 2 / FR-018 ~ FR-021）。
 *
 * 與 `checkDependencyCompatibility` 的差異在比較基準：那邊比的是 merge-base（這支 PR 改了什麼），
 * 這邊比的是上一個 release tag（這個版本累積了什麼）。發版者要回答的是後者。
 *
 * **永不因發現破壞性變更而失敗**——發版當下需要的是完整資訊，不是阻擋；該擋的在合併前
 * 就已經由閘門擋過了。但環境問題（找不到 git、`--since` 指定的 ref 不存在）仍 fail-fast：
 * 那些情況下產出的是一份「看起來沒有變動」的報告，而那正是本功能要根除的失效模式。
 */
abstract class DependencyChangeReportTask : DependencyGateTask() {

    /** 報告輸出位置（`build/reports/dependency-gate/change-report.md`）。 */
    @get:Internal
    abstract val reportFile: RegularFileProperty

    /**
     * `--since=<git-ref>`；未指定時**逐模組**取各自最新的 `<module>/v*` tag（FR-004）。
     *
     * 逐模組而非全域是必要的：兩個 starter 各自發版，`knolux-redis…/v1.4.0` 與
     * `knolux-s3…/v1.3.0` 是不同的時間點。用單一 tag 套用到所有模組，另一個模組的報告
     * 就會涵蓋它上次發版之後、這個 tag 之前的那段變動——多報的部分早已揭露過。
     */
    @get:Internal
    @get:Option(option = "since", description = "比較起點的 git ref，預設為各模組最新的發布 tag")
    abstract val since: Property<String>

    init {
        description = "產出自上一個發布版本以來的依賴變更報告，供 CHANGELOG 與 Release notes 引用"
    }

    @TaskAction
    fun report() {
        val refs = GitRefs(repoDir.get().asFile)
        val explicit = since.orNull?.also {
            // 靜默退回自動挑選，會讓使用者以為報告比的是自己指定的那一個版本。
            check(refs.exists(it)) { "指定的比較起點『$it』不存在（--since）；請確認 ref 名稱，或先 git fetch --tags" }
        }
        val tags = if (explicit == null) refs.listTags() else emptyList()

        val bases = mutableListOf<String>()
        val verdicts = currentDependencySets().map { (moduleName, current) ->
            if (explicit != null) {
                bases += ReleaseTagSelector.describeBase(explicit)
                return@map evaluate(moduleName, current, explicit)
            }

            val lookup = ReleaseTagSelector.select(moduleName, tags)
            warnIgnoredTags(moduleName, lookup.ignoredTags)

            when (lookup) {
                // 尚未發布過的模組沒有可比較的上一版。FR-005 要求記入報告而非靜默略過。
                is ReleaseTagLookup.Missing -> GateVerdict.skipped(moduleName, lookup.reason)

                is ReleaseTagLookup.Found -> {
                    bases += lookup.describe
                    evaluate(moduleName, current, lookup.tag)
                }
            }
        }

        // distinct：`--since` 指定時各模組的基準相同，重複列出只是雜訊。
        val report = GateReport(bases.distinct().joinToString("、").ifEmpty { NO_BASE }, verdicts)
        val file = reportFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(ReportRenderer.renderChangeReport(report), Charsets.UTF_8)

        logger.lifecycle("依賴變更報告：${file.relativeToRepo()}")
    }

    /**
     * 版本號無法解析的 tag 被略過時 MUST 出聲。
     *
     * 被略過的那一個，可能正是維護者以為會被拿來比較的版本；靜默略過的後果是
     * 報告用了更舊的基準，而輸出上完全看不出來。
     */
    private fun warnIgnoredTags(moduleName: String, ignored: List<String>) {
        if (ignored.isEmpty()) return

        logger.warn(
            "$moduleName：略過版本號無法解析的 tag ${ignored.joinToString("、")}；" +
                "請確認 tag 命名為 `$moduleName/v<版本>`",
        )
    }

    private fun evaluate(moduleName: String, current: DependencySet, base: String): GateVerdict {
        val source = GitBaselineSource(repoDir.get().asFile, base, baselineDirPath())

        return when (val historic = source.load(moduleName)) {
            // 首次導入閘門之前發布的版本沒有基準線檔，這是預期內的；但 FR-005 要求記入報告。
            is BaselineLookup.Missing -> GateVerdict.skipped(moduleName, historic.reason)
            is BaselineLookup.Found ->
                GateVerdict.evaluate(moduleName, DeltaCalculator.calculate(historic.dependencySet, current))
        }
    }

    private companion object {
        const val NO_BASE = "（無：所有模組皆無可用的發布 tag）"
    }
}
