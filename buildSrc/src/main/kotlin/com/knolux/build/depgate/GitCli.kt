package com.knolux.build.depgate

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 執行 git 指令的最小封裝。
 *
 * 整個功能只有兩個地方需要 git（取歷史版本的基準線、解析比較基準的 ref），
 * 兩者共用此處以確保錯誤處理一致——尤其是「找不到 git 執行檔」與「指令回非零」
 * 這兩件事必須被區別對待：前者是環境問題，後者往往是正常結果（例如 ref 不存在）。
 */
class GitCli(private val repoDir: File) {

    /**
     * 執行 git 並取回結果。**非零結束碼不拋例外**——呼叫端才知道那是不是錯誤。
     *
     * @throws IllegalStateException 找不到 git 執行檔或逾時；這是環境問題，
     *   若混入一般結果會讓上層把「沒有 git」誤判成「沒有這個 ref」
     */
    fun run(vararg args: String): Result {
        val process = try {
            ProcessBuilder(listOf("git") + args)
                .directory(repoDir)
                .start()
        } catch (e: IOException) {
            throw IllegalStateException("無法執行 git（工作目錄：${repoDir.path}）：${e.message}", e)
        }

        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        check(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "git ${args.joinToString(" ")} 超過 $TIMEOUT_SECONDS 秒未結束"
        }

        return Result(process.exitValue(), stdout, stderr)
    }

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val successful: Boolean get() = exitCode == 0

        /** 給例外訊息用的簡短說明：優先採 git 自己的 stderr，沒有才退回結束碼。 */
        val failureDetail: String get() = stderr.trim().ifEmpty { "git 以 exit=$exitCode 結束" }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
    }
}
