package com.knolux.build.depgate.task

import com.knolux.build.depgate.DependencyGraphReader
import com.knolux.build.depgate.DependencySet
import com.knolux.build.depgate.DependencySetBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Internal
import java.io.File

/**
 * 三個閘門任務共用的接線（憲章 III：composition root 只負責組裝，不放判斷邏輯）。
 *
 * 所有屬性標為 `@Internal`：`ResolvedComponentResult` 無法作為輸入指紋，而閘門的
 * 正確結果取決於 git 狀態與遠端 ref，本來就不該被 up-to-date 檢查略過。任務因此每次都執行。
 */
abstract class DependencyGateTask : DefaultTask() {

    /** 模組名 → 該模組 `runtimeClasspath` 的解析根元件。 */
    @get:Internal
    abstract val rootComponents: MapProperty<String, ResolvedComponentResult>

    /** 基準線檔所在目錄（`gradle/dependency-baseline`）。 */
    @get:Internal
    abstract val baselineDir: DirectoryProperty

    /** repo 根目錄，供 git 操作與路徑相對化使用。 */
    @get:Internal
    abstract val repoDir: DirectoryProperty

    init {
        group = "verification"
    }

    /**
     * 解析各模組當前的依賴集合，依模組名排序。
     *
     * 排序是硬性要求：報告會被貼進 CHANGELOG，模組順序浮動會讓兩次執行的輸出無從比對。
     */
    protected fun currentDependencySets(): Map<String, DependencySet> =
        rootComponents.get().toSortedMap().mapValues { (moduleName, root) ->
            DependencySetBuilder.build(moduleName, DependencyGraphReader.read(root))
        }

    /** 基準線目錄相對於 repo 根的 POSIX 路徑，供 `git show <ref>:<path>` 使用。 */
    protected fun baselineDirPath(): String = baselineDir.get().asFile.relativeToRepo()

    protected fun baselineFile(moduleName: String): File =
        File(baselineDir.get().asFile, "$moduleName$BASELINE_SUFFIX")

    /** 訊息中一律使用 repo 相對路徑：絕對路徑在 CI log 裡又長又無法點擊。 */
    protected fun File.relativeToRepo(): String =
        relativeToOrSelf(repoDir.get().asFile).path.replace('\\', '/')

    protected companion object {
        const val BASELINE_SUFFIX = ".txt"
    }
}
