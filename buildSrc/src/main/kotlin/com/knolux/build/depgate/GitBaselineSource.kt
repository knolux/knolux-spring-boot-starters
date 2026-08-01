package com.knolux.build.depgate

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 自指定的 git ref 取出當時簽入的基準線檔。
 *
 * 這是整個功能中**唯一**接觸 git 的位置（憲章 III 的邊界）。發版報告需要比對的是
 * 「前一個已發布 tag 當時的基準線」，而非工作目錄裡的版本——此時只能問 git。
 *
 * 刻意用 `git show <ref>:<path>` 而非 checkout：不動工作目錄，也就不會與並行的
 * Gradle 任務互相干擾，更不需要處理 checkout 失敗後的還原。
 *
 * @param repoDir git 工作目錄
 * @param ref 要查詢的 ref（通常是 module-scoped tag，例如 `knolux-redis-spring-boot-starter/v1.3.0`）
 * @param baselineDirPath 基準線目錄在 repo 中的相對路徑，需為 POSIX 形式
 */
class GitBaselineSource(
    private val repoDir: File,
    private val ref: String,
    private val baselineDirPath: String,
) : BaselineSource {

    override fun load(moduleName: String): BaselineLookup {
        val path = "$baselineDirPath/$moduleName$BASELINE_SUFFIX"
        val target = "$ref:$path"

        val result = runGitShow(target)
        if (result.exitCode != 0) {
            // ref 不存在、或該 ref 當時還沒有這個檔案，兩者 git 都以非零結束。
            // 這在導入首日是常態（舊 tag 本來就沒有基準線檔），不是錯誤。
            return BaselineLookup.Missing(
                "無法自 git ref『$ref』取得 $path：${result.stderr.trim().ifEmpty { "git 以 exit=${result.exitCode} 結束" }}",
            )
        }

        return BaselineLookup.Found(
            dependencySet = DependencySet.parse(moduleName, result.stdout.removePrefix(UTF8_BOM)),
            origin = "git:$target",
        )
    }

    private fun runGitShow(target: String): GitResult {
        val process = try {
            ProcessBuilder("git", "show", target)
                .directory(repoDir)
                .start()
        } catch (e: IOException) {
            // 找不到 git 執行檔屬環境問題，與「這個 ref 沒有基準線」是兩回事。
            // 若也回傳 Missing，發版報告會變成一片空白卻看起來一切正常。
            throw IllegalStateException("無法執行 git（工作目錄：${repoDir.path}）：${e.message}", e)
        }

        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        check(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "git show $target 超過 $TIMEOUT_SECONDS 秒未結束"
        }

        return GitResult(process.exitValue(), stdout, stderr)
    }

    private data class GitResult(val exitCode: Int, val stdout: String, val stderr: String)

    private companion object {
        const val BASELINE_SUFFIX = ".txt"
        const val UTF8_BOM = "﻿"
        const val TIMEOUT_SECONDS = 60L
    }
}
