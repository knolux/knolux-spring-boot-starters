package com.knolux.build.depgate.task

import com.knolux.build.depgate.BaselineLookup
import com.knolux.build.depgate.BaselineStaleness
import com.knolux.build.depgate.ConsoleRenderer
import com.knolux.build.depgate.FileBaselineSource
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction

/**
 * 驗證簽入的基準線檔與當前 `runtimeClasspath` 解析結果逐字相同（research.md R2 機制 M1）。
 *
 * 單獨存在的理由：`publish.yml` 需要在**不做差異比對**的情況下，只確認「即將發布的內容
 * 與簽入基準線一致」。它建立的不變量是——發布出去的 artifact，其外溢依賴集合必定等於
 * 當時簽入的基準線檔案。少了這個不變量，`checkDependencyCompatibility` 拿歷史基準線
 * 做的一切比較都只是在比對兩份可能都不真實的檔案。
 *
 * 缺少基準線檔在此**視為失敗**（與閘門本體不同，那裡是跳過並記錄原因）：
 * 沒有基準線就無從建立上述不變量，而發版是不可回收的動作。
 */
abstract class CheckDependencyBaselineTask : DependencyGateTask() {

    init {
        description = "驗證簽入的依賴基準線與當前解析結果一致"
    }

    @TaskAction
    fun check() {
        val source = FileBaselineSource(baselineDir.get().asFile)

        // 一次檢查完所有模組再決定成敗（FR-012）：只回報第一個模組會讓維護者
        // 陷入「修一個、重跑、又冒出一個」的迴圈，而一輪 CI 要數分鐘。
        val problems = currentDependencySets().mapNotNull { (moduleName, current) ->
            when (val lookup = source.load(moduleName)) {
                is BaselineLookup.Missing -> lookup.reason

                // 此處**不看** BaselineStaleness.blocking：閘門本體容忍非阻擋性落差（FR-011），
                // 但發版是不可回收的動作，任何落差都代表發布出去的 artifact 與簽入基準線不符，
                // M2 的不變量因此不成立。容忍的界線只到合併前為止。
                is BaselineLookup.Found -> BaselineStaleness.detect(lookup.dependencySet, current)
                    ?.let { ConsoleRenderer.renderStaleBaseline(it, baselineFile(moduleName).relativeToRepo()) }
            }
        }

        if (problems.isNotEmpty()) {
            problems.forEach { logger.error(it) }
            throw GradleException("依賴基準線與當前解析結果不一致（${problems.size} 個模組）；詳見上方輸出。")
        }

        logger.lifecycle("依賴基準線：✅ ${rootComponents.get().size} 個模組與簽入內容一致")
    }
}
