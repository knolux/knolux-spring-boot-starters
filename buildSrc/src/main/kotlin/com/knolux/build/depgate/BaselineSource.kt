package com.knolux.build.depgate

/**
 * 取得某模組基準線的 port（憲章 III）。
 *
 * 抽成 port 的理由不是「未來可能換實作」這種空泛的彈性，而是**基準線的來源本來就
 * 不只一種**：閘門比對的是簽入版控的檔案（[FileBaselineSource]），而發版報告要比對
 * 的是「前一個已發布 tag 當時的檔案」（[GitBaselineSource]）。兩者的取得方式天差地遠，
 * 但下游的差異計算與報告產生完全相同。
 */
interface BaselineSource {

    /**
     * 取得 [moduleName] 的基準線。
     *
     * 「取不到」是預期內的正常狀況而非錯誤，因此回傳 [BaselineLookup.Missing]
     * 而不拋例外（FR-005）。
     */
    fun load(moduleName: String): BaselineLookup
}

/**
 * [BaselineSource.load] 的結果。
 *
 * 用 sealed 型別而非可空回傳值，是為了讓「沒有基準線」帶著**理由**一起傳出去。
 * 那段理由會原文出現在報告的跳過說明中（FR-005 要求跳過必須記入報告）；
 * 只回傳 null 的話，報告就只能寫「跳過」而說不出為什麼跳過。
 */
sealed interface BaselineLookup {

    /**
     * 找到基準線。
     *
     * @property origin 實際來源的可讀說明（檔案路徑或 git ref），會出現在報告中
     */
    data class Found(val dependencySet: DependencySet, val origin: String) : BaselineLookup

    /**
     * 沒有基準線。
     *
     * @property reason 缺少的原因與補救方式，會原文出現在報告中
     */
    data class Missing(val reason: String) : BaselineLookup
}
