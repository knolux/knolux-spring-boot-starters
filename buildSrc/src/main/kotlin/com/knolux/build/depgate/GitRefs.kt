package com.knolux.build.depgate

import java.io.File

/**
 * 解析「要跟什麼比」的 git ref（spec FR-003、contracts/gradle-tasks.md 的 `--base` 選項）。
 *
 * 取的是 **merge-base** 而非 ref 本身。PR 分支落後 `dev` 好幾天是常態；若直接拿 `dev`
 * 的當前狀態來比，別人合併進 `dev` 的依賴升級會被算成這支 PR 的責任，報告因此失真、
 * 閘門也會擋錯人。
 *
 * 三種失敗（ref 不存在、無共同祖先、找不到 git）一律 fail-fast。此處**沒有**任何靜默
 * 退路：比較基準錯了，後續整份報告就是錯的，而「看起來通過了」比「明白地失敗」危險得多。
 *
 * @param repoDir git 工作目錄
 */
class GitRefs(repoDir: File) {

    private val git = GitCli(repoDir)

    /**
     * 決定比較基準。
     *
     * @param explicit 使用者以 `--base=` 明確指定的 ref；為 null 時改試 [defaults]
     * @param defaults 候選 ref，依序取第一個存在者（專案預設為 `origin/dev` → `origin/main`）
     * @param head 要與基準求 merge-base 的一端，預設為當前 HEAD
     * @throws IllegalStateException ref 不存在、或與 [head] 無共同祖先
     */
    fun resolveComparisonBase(
        explicit: String?,
        defaults: List<String>,
        head: String = "HEAD",
    ): ComparisonBase {
        val ref = when {
            // 明確指定卻不存在時 MUST NOT 退回預設：使用者會以為比的是自己指定的東西。
            explicit != null -> explicit.also {
                check(exists(it)) { "指定的比較基準『$it』不存在（--base）；請確認 ref 名稱，或先 git fetch 取得該分支／tag" }
            }

            else -> defaults.firstOrNull { exists(it) }
                ?: error(
                    "找不到任何可用的比較基準，已依序嘗試：${defaults.joinToString("、")}。" +
                        "若在 CI 上，多半是 checkout 為淺層 clone——請將 actions/checkout 的 fetch-depth 設為 0；" +
                        "在本機則可用 --base=<git-ref> 明確指定。",
                )
        }

        val result = git.run("merge-base", ref, head)
        check(result.successful) {
            "『$ref』與『$head』沒有共同祖先，無法求得 merge-base：${result.failureDetail}。" +
                "此時「沒有差異」並非正確答案，故不繼續比對。"
        }

        return ComparisonBase(ref, result.stdout.trim())
    }

    /** ref 是否可解析。用 `rev-parse --verify` 而非 `show-ref`，因為前者同時吃分支、tag 與 SHA。 */
    fun exists(ref: String): Boolean = git.run("rev-parse", "--verify", "--quiet", "$ref^{commit}").successful

    /**
     * 列出全部 tag，供 [ReleaseTagSelector] 挑選發版比較基準（FR-004）。
     *
     * **不在此處排序或過濾**：`git tag` 的排序是字典序，而發版基準必須依語意化版本挑選
     * （`v1.10.0` 要勝過 `v1.9.0`）。把挑選規則留在可單元測試的純邏輯側，
     * 這裡只負責把清單原樣拿出來。
     *
     * 空白行一律濾掉：沒有任何 tag 時 `git tag` 輸出空字串，未過濾的 split 會產生一個
     * 空字串項，下游會把它當成一個「名稱為空的 tag」，於是報告說「有 tag 但解析不了」，
     * 與事實（從未發布過）相反。
     */
    fun listTags(): List<String> =
        git.run("tag", "--list").stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * 已解析的比較基準。
 *
 * 同時保留 ref 名稱與 merge-base commit：前者是人看得懂的「跟哪個分支比」，
 * 後者是實際比對的那一點——報告兩者都要寫出來才可被重現與驗證。
 */
data class ComparisonBase(val ref: String, val mergeBase: String) {

    /** 給報告標頭與 console 用的描述，例如 `` `origin/dev`（merge-base `a1b2c3d`） ``。 */
    val describe: String get() = "`$ref`（merge-base `${mergeBase.take(SHORT_SHA_LENGTH)}`）"

    private companion object {
        const val SHORT_SHA_LENGTH = 7
    }
}
