package com.knolux.build.depgate

/**
 * 簽入的基準線檔與**當前**解析結果之間的落差（research.md R2 機制 M1）。
 *
 * 與閘門本體比的東西不同，別把兩者搞混：
 * - 閘門比「merge-base 當時的基準線 vs 現在的實際解析」＝這次變更集造成的依賴改變
 * - 這裡比「工作區現在的基準線檔 vs 現在的實際解析」＝簽入的檔案還說不說得準
 *
 * 因此基準線過期**不會**讓閘門的比較失準（閘門的另一側本來就是實際解析結果），
 * 它的真正代價是把一份不真實的檔案留給下一個人當比較對象。
 *
 * [blocking] 是這個型別存在的唯一理由——落差的**嚴重度**決定成敗，而非落差的有無。
 * 一律失敗的話，Dependabot 每週送來的 patch bump 全數紅燈，違反 FR-011 與 SC-003；
 * 而 research.md R2 否決 Gradle dependency locking 的理由，正正就是「會讓每支 bot PR 紅燈」。
 */
data class BaselineStaleness(
    val moduleName: String,
    val deltas: List<DependencyDelta>,
) {

    init {
        require(deltas.isNotEmpty()) {
            "模組 $moduleName 沒有任何落差卻建構了 BaselineStaleness；" +
                "無落差請以 detect 回傳的 null 表示，否則會輸出「已過期，共 0 處不一致」這種讀者只會當成顯示錯誤的訊息"
        }
    }

    /**
     * 是否讓閘門失敗。
     *
     * 含阻擋性項目才失敗：這種落差一旦混進 `dev`，要等到發版前的
     * `checkDependencyBaseline` 才會爆——而發版是最不該被臨時擋住的時刻。
     * 全屬非阻擋性時只警告，晚一點補上基準線的代價僅是後續 PR 多報幾行資訊性變動。
     *
     * 刻意**不**看核准紀錄：核准說的是「這個依賴變動可以接受」，
     * 不是「基準線檔可以繼續說謊」。
     */
    val blocking: Boolean get() = deltas.any { it.blocking }

    companion object {

        /** 偵測落差；完全一致時回傳 `null`（而非空的 [BaselineStaleness]）。 */
        fun detect(baseline: DependencySet, current: DependencySet): BaselineStaleness? =
            DeltaCalculator.calculate(baseline, current)
                .takeIf { it.isNotEmpty() }
                ?.let { BaselineStaleness(current.moduleName, it) }
    }
}
