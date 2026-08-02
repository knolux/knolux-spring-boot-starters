package com.knolux.build.depgate

import java.io.File

/**
 * 自工作目錄讀取簽入版控的基準線檔（`gradle/dependency-baseline/<module>.txt`）。
 *
 * 這是 `checkDependencyCompatibility` 使用的 adapter：比對的對象是**此刻簽入的**
 * 基準線，因此 PR 若同時改動基準線檔，diff 會直接呈現在同一個 PR 中供人審閱——
 * 這正是 R2 選擇「簽入檔案」而非「執行期重建」的核心價值。
 *
 * @param baselineDir 基準線檔所在目錄
 */
class FileBaselineSource(private val baselineDir: File) : BaselineSource {

    override fun load(moduleName: String): BaselineLookup {
        val file = File(baselineDir, "$moduleName$BASELINE_SUFFIX")
        if (!file.isFile) {
            return BaselineLookup.Missing(
                "找不到基準線檔 ${file.invariantPath()}；" +
                    "若 $moduleName 是首次納入閘門，請執行 ./gradlew updateDependencyBaseline 產生後一併提交",
            )
        }

        // 讀取端刻意寬鬆（容忍 BOM 與 CRLF），寫入端一律 UTF-8 無 BOM + LF。
        // 有人用 Windows 的編輯器動過檔案是可預期的，為此讓整個模組被跳過並不值得。
        val content = file.readText(Charsets.UTF_8).removePrefix(UTF8_BOM)

        return BaselineLookup.Found(
            dependencySet = DependencySet.parse(moduleName, content),
            origin = file.invariantPath(),
        )
    }

    private fun File.invariantPath(): String = path.replace('\\', '/')

    private companion object {
        const val BASELINE_SUFFIX = ".txt"
        const val UTF8_BOM = "﻿"
    }
}
