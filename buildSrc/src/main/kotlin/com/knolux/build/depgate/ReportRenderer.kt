package com.knolux.build.depgate

/**
 * 將 [GateReport] 渲染成人類可讀的 Markdown（contracts/report-format.md）。
 *
 * 純函式、無 I/O——寫檔由任務類別負責。這讓「格式對不對」可以用字串斷言測，
 * 不必真的跑一次 Gradle 建置。
 *
 * **格式為契約而非美感選擇**：「阻擋性變動」區塊必須能**原文**貼進 `CHANGELOG.md`
 * 的「⚠️ 升級前必讀」段落（見 `CHANGELOG.md:20-34` 的既有寫法）。表格欄位一旦改名、
 * 版本字串一旦被正規化、座標一旦少了反引號，貼上去就得手工修——而只要需要手工修，
 * 發版者就會退回人工比對依賴樹，本功能存在的理由隨之消失。
 *
 * 一律使用標準 GitHub-flavored Markdown（FR-021）：報告會出現在 GitHub PR、CI log
 * 與 CHANGELOG 三個地方，任何只有其中一處支援的語法，在另外兩處就是亂碼。
 */
object ReportRenderer {

    private const val SECTION_SEPARATOR = "\n\n---\n\n"
    private const val EMPTY_CELL = "—"
    private const val REMOVED_CELL = "（已移除）"
    private const val NO_CHANGE = "外溢依賴無變動"
    private const val NOTHING_COMPARED = "未比對任何模組（見下方「已跳過的模組」）"

    /** 渲染閘門報告 `gate-report.md`。 */
    fun renderGateReport(report: GateReport): String {
        val blockedByModule = report.verdicts.associateWith { verdict ->
            verdict.blockedDeltas.filter { it.kind != DeltaKind.UNPARSEABLE }
        }
        val unparseable = report.blockedDeltas.filter { it.kind == DeltaKind.UNPARSEABLE }

        val sections = buildList {
            add(renderHeader(report))
            renderBlockingSection(blockedByModule)?.let(::add)
            renderUnparseableSection(unparseable)?.let(::add)
            renderApprovedSection(report.verdicts.flatMap { it.approvedDeltas })?.let(::add)
            renderInformationalSection(report.verdicts)?.let(::add)
            renderStaleApprovalSection(report.staleApprovals)?.let(::add)
            renderSkippedSection(report.verdicts)?.let(::add)
            renderHowToHandle(report.blockedDeltas.firstOrNull())?.let(::add)
        }

        return sections.joinToString(SECTION_SEPARATOR, postfix = "\n")
    }

    /**
     * 渲染發版報告 `change-report.md`（contracts/report-format.md §2）。
     *
     * 與 [renderGateReport] 共用全部的區塊渲染，差異只有三處，且都源自同一件事：
     * **這份報告不做判定，只做揭露**。
     *
     * 1. 無「判定」行——發版報告永不失敗，寫上去會讓讀者去找一個不存在的成敗結論
     * 2. 無「如何處理」段落——那是給被擋下來的人看的；發版時該做的是把揭露寫進 CHANGELOG
     * 3. 破壞性變動**不分核准與否一律列入「⚠️ 升級前必讀」**
     *
     * 第 3 點是本函式存在的核心理由：**核准解除的是建置阻擋，不是下游會不會壞**。
     * 若已核准的破壞性變更在發版報告中消失，2026-08-01 的失效模式會原封不動地重演，
     * 只是「沒揭露」的原因從「沒人發現」變成「工具幫忙藏起來了」——後者更難察覺。
     */
    fun renderChangeReport(report: GateReport): String {
        val breakingByModule = report.verdicts.associateWith { verdict ->
            verdict.deltas.filter { it.blocking && it.kind != DeltaKind.UNPARSEABLE }
        }
        val unparseable = report.verdicts.flatMap { verdict ->
            verdict.deltas.filter { it.kind == DeltaKind.UNPARSEABLE }
        }

        val sections = buildList {
            add(renderChangeHeader(report))
            renderBreakingSection(breakingByModule)?.let(::add)
            renderUnparseableSection(unparseable)?.let(::add)
            renderInformationalSection(report.verdicts)?.let(::add)
            renderSkippedSection(report.verdicts)?.let(::add)
        }

        return sections.joinToString(SECTION_SEPARATOR, postfix = "\n")
    }

    private fun renderChangeHeader(report: GateReport): String = buildString {
        appendLine("# 依賴變更報告")
        appendLine()
        append("**比較基準**：${report.comparisonBase}")

        // 完全無變動時仍要明說。少了這句，讀者無從分辨「這次真的沒動」與「報告產壞了」。
        if (report.verdicts.all { it.deltas.isEmpty() }) {
            // 一個模組都沒比對成功時不掛 ✅：那面綠勾會被當成「檢查過了，沒事」。
            val icon = if (report.verdicts.any { it.status != GateStatus.SKIPPED_NO_BASELINE }) "✅" else "⚠️"
            appendLine()
            append("**結果**：$icon ${noChangeSummary(report.verdicts)}")
        }
    }

    /**
     * 「無變動」的結論句——只涵蓋**真的比對過**的模組。
     *
     * 被跳過的模組其 deltas 同樣為空，資料上與「比過了且相同」長得一模一樣，
     * 意義卻正好相反：一個是什麼都沒檢查，一個是檢查完沒事。實際踩過這個坑——
     * 以早於基準線檔存在時點的 tag 產報告，兩個模組都因取不到基準線而跳過，
     * 標頭卻寫著「外溢依賴無變動」。一份看起來乾淨、實際上什麼都沒檢查的報告，
     * 正是本功能要根除的失效模式。
     *
     * 前置條件：呼叫端已確認比對過的模組皆無差異。
     */
    private fun noChangeSummary(verdicts: List<GateVerdict>): String {
        val compared = verdicts.filter { it.status != GateStatus.SKIPPED_NO_BASELINE }

        return when {
            compared.isEmpty() -> NOTHING_COMPARED
            compared.size == verdicts.size -> NO_CHANGE
            // 部分跳過時 MUST 點名範圍：讀者才知道這句話管的是哪幾個模組。
            else -> compared.joinToString("、") { "`${it.moduleName}`" } +
                " $NO_CHANGE；其餘模組未比對（見下方「已跳過的模組」）"
        }
    }

    private fun renderHeader(report: GateReport): String = buildString {
        appendLine("# 依賴相容性閘門報告")
        appendLine()
        appendLine("**比較基準**：${report.comparisonBase}")
        append("**判定**：${renderVerdictLine(report)}")
    }

    private fun renderVerdictLine(report: GateReport): String {
        if (report.blocked) return "❌ 阻擋 — ${report.blockedDeltas.size} 項未核准的破壞性變更"

        // 已核准的筆數要**先**於資訊性筆數呈現：讀者掃過標頭就該知道「這次有破壞性變更，
        // 只是被放行了」，而不是看到「✅ 通過」後就不再往下讀。
        val approvedCount = report.verdicts.sumOf { it.approvedDeltas.size }
        val informationalCount = report.verdicts.sumOf { verdict -> verdict.deltas.count { !it.blocking } }
        val clauses = listOfNotNull(
            "$approvedCount 項破壞性變更已核准".takeIf { approvedCount > 0 },
            "$informationalCount 項資訊性變動".takeIf { informationalCount > 0 },
        )

        // clauses 為空即代表沒有任何差異（阻擋、已核准、資訊性三類皆為零），
        // 此時「無變動」的範圍交由 noChangeSummary 界定——全部模組都跳過時不能這樣講。
        val summary = if (clauses.isEmpty()) noChangeSummary(report.verdicts) else clauses.joinToString("、")

        return "✅ 通過 — $summary"
    }

    /**
     * 已核准的破壞性變動（情形 B）。
     *
     * 核准解除的是「建置阻擋」，不是「下游會不會壞」。少了結尾那句提醒，核准很容易被
     * 當成「這件事處理完了」，於是揭露就不會被寫進 CHANGELOG——而沒有揭露的破壞性變更，
     * 正是本功能一開始要解決的問題。
     */
    private fun renderApprovedSection(approved: List<Pair<DependencyDelta, Approval>>): String? {
        if (approved.isEmpty()) return null

        return buildString {
            appendLine("## ✅ 已核准的破壞性變動")
            appendLine()
            appendLine("| 模組 | 依賴 | 基準線 | 當前 | 類別 | 核准理由 |")
            append("|---|---|---|---|---|---|")
            approved.forEach { (delta, approval) ->
                appendLine()
                append(
                    "| `${delta.moduleName}` | `${delta.coordinate}` | ${delta.from} | ${currentCell(delta)} | " +
                        "${categoryLabel(delta.kind)} | ${approval.reason} |",
                )
            }
            appendLine()
            appendLine()
            append("⚠️ 已核准不代表下游不受影響。發版時仍須將上表寫入 CHANGELOG 的「升級前必讀」段落。")
        }
    }

    /**
     * 過期核准（data-model.md §6）。純資訊性，不影響判定。
     *
     * 講出來是為了讓核准檔能被清理：不講，過期項只會越積越多，
     * 最終沒有人敢動這份檔案，也就沒有人會再審視裡面還放行著什麼。
     */
    private fun renderStaleApprovalSection(stale: List<Approval>): String? {
        if (stale.isEmpty()) return null

        return buildString {
            appendLine("## ℹ️ 過期的核准")
            appendLine()
            appendLine("以下核准在本輪未對應到任何差異，可執行 `./gradlew updateDependencyBaseline` 一併清除。")
            appendLine()
            appendLine("| 模組 | 依賴 | 核准的變動 | 核准理由 |")
            append("|---|---|---|---|")
            stale.forEach { approval ->
                appendLine()
                append(
                    "| `${approval.module}` | `${approval.coordinate}` | " +
                        "${approval.from} → ${approval.to ?: REMOVED_CELL} | ${approval.reason} |",
                )
            }
        }
    }

    /** 閘門報告的阻擋性變動區塊。模組小標即為可貼進 CHANGELOG 的「⚠️ 升級前必讀」。 */
    private fun renderBlockingSection(blockedByModule: Map<GateVerdict, List<DependencyDelta>>): String? =
        renderBreakingTables(
            heading = "## ❌ 阻擋性變動（需核准或還原）",
            moduleHeading = { "### ⚠️ 升級前必讀：`$it` 的傳遞依賴破壞性變更" },
            byModule = blockedByModule,
        )

    /**
     * 發版報告的破壞性變動區塊。小標即為 CHANGELOG 的段落名，可整段複製。
     *
     * 與 [renderBlockingSection] 共用表格渲染，僅換掉兩層標題：表格本身是與 CHANGELOG
     * 的契約（見本類別的 KDoc），兩份報告若各寫一份，遲早會有一邊先漂移。
     */
    private fun renderBreakingSection(breakingByModule: Map<GateVerdict, List<DependencyDelta>>): String? =
        renderBreakingTables(
            heading = "## ⚠️ 升級前必讀",
            moduleHeading = { "### `$it` 的傳遞依賴破壞性變更" },
            byModule = breakingByModule,
        )

    /**
     * 逐模組分表——CHANGELOG 的揭露對象是單一 artifact 的使用者，
     * 混在一起貼過去反而要再拆。
     */
    private fun renderBreakingTables(
        heading: String,
        moduleHeading: (String) -> String,
        byModule: Map<GateVerdict, List<DependencyDelta>>,
    ): String? {
        val modules = byModule.filterValues { it.isNotEmpty() }
        if (modules.isEmpty()) return null

        return buildString {
            append(heading)
            modules.forEach { (verdict, deltas) ->
                appendLine()
                appendLine()
                appendLine(moduleHeading(verdict.moduleName))
                appendLine()
                appendLine("| 依賴 | 基準線 | 當前 | 影響 |")
                appendLine("|---|---|---|---|")
                deltas.forEach { delta ->
                    appendLine("| `${delta.coordinate}` | ${delta.from} | **${currentCell(delta)}** | **${impactLabel(delta.kind)}** |")
                }
            }
            // 移除最後一行多出的換行，讓區塊間距由 SECTION_SEPARATOR 統一控制
            setLength(length - 1)
        }
    }

    /**
     * 無法解析的版本自成一個區塊（情形 E）。
     *
     * 刻意不併進「跨 major 版本」表格：那會謊報事實——實際狀況是「無法判定」，
     * 而非「已判定它跨了 major」。兩者對讀者的後續行動完全不同。
     */
    private fun renderUnparseableSection(deltas: List<DependencyDelta>): String? {
        if (deltas.isEmpty()) return null

        return buildString {
            appendLine("## ❌ 無法判定的版本")
            appendLine()
            appendLine("| 模組 | 依賴 | 基準線 | 當前 | 問題 |")
            append("|---|---|---|---|---|")
            deltas.forEach { delta ->
                appendLine()
                append(
                    "| `${delta.moduleName}` | `${delta.coordinate}` | `${delta.from}` | `${delta.to}` | " +
                        "${delta.unparseableReason} |",
                )
            }
        }
    }

    private fun renderInformationalSection(verdicts: List<GateVerdict>): String? {
        val reported = verdicts.filter { it.status != GateStatus.SKIPPED_NO_BASELINE }
        if (reported.none { verdict -> verdict.deltas.any { !it.blocking } }) return null

        return buildString {
            append("## ℹ️ 資訊性變動")
            reported.forEach { verdict ->
                val deltas = verdict.deltas.filter { !it.blocking }
                appendLine()
                appendLine()
                appendLine("### `${verdict.moduleName}`")
                appendLine()
                if (deltas.isEmpty()) {
                    // 留白會讓人誤以為報告漏了這個模組
                    append("無變動。")
                    return@forEach
                }
                appendLine("| 依賴 | 基準線 | 當前 | 類別 |")
                append("|---|---|---|---|")
                deltas.forEach { delta ->
                    appendLine()
                    append("| `${delta.coordinate}` | ${delta.from ?: EMPTY_CELL} | ${currentCell(delta)} | ${categoryLabel(delta.kind)} |")
                }
            }
        }
    }

    /**
     * 已跳過的模組（情形 D）。
     *
     * FR-005 允許沒有基準線時跳過，但憲章 IV 不允許靜默跳過——
     * 那等同於「這個模組沒被保護，而沒有人知道」。
     */
    private fun renderSkippedSection(verdicts: List<GateVerdict>): String? {
        val skipped = verdicts.filter { it.status == GateStatus.SKIPPED_NO_BASELINE }
        if (skipped.isEmpty()) return null

        return buildString {
            appendLine("## ⏭️ 已跳過的模組")
            appendLine()
            appendLine("| 模組 | 原因 |")
            append("|---|---|")
            skipped.forEach { verdict ->
                appendLine()
                append("| `${verdict.moduleName}` | ${verdict.skipReason} |")
            }
        }
    }

    /**
     * 處理指引（FR-018）。範本直接填入**實際被擋的第一筆**，讓維護者可以整段複製。
     *
     * 只說「被擋住了」而不說怎麼辦，維護者的下一步會是去找人問而非自己解決；
     * 給的又是空白範本的話，多數人會填錯欄位再重跑一次 CI。
     */
    private fun renderHowToHandle(sample: DependencyDelta?): String? {
        if (sample == null) return null

        return buildString {
            appendLine("## 如何處理阻擋性變動")
            appendLine()
            appendLine("若變更並非有意，請還原造成該變更的依賴調整。")
            appendLine()
            appendLine("若變更確屬有意且已評估影響，於 `gradle/dependency-approvals.toml` 逐筆加入核准：")
            appendLine()
            appendLine("```toml")
            appendLine("[[approval]]")
            appendLine("""module = "${sample.moduleName}"""")
            appendLine("""coordinate = "${sample.coordinate}"""")
            appendLine("""kind = "${sample.kind.name}"""")
            appendLine("""from = "${sample.from}"""")
            sample.to?.let { appendLine("""to = "$it"""") }
            appendLine("""reason = "（填寫理由）"""")
            appendLine("```")
            appendLine()
            append("核准後請將上表的「升級前必讀」段落一併寫入 `CHANGELOG.md` 與 GitHub Release notes。")
        }
    }

    private fun currentCell(delta: DependencyDelta): String = delta.to ?: REMOVED_CELL

    /** 阻擋性表格的「影響」欄——寫的是對下游的後果，不是分類代號。 */
    private fun impactLabel(kind: DeltaKind): String = when (kind) {
        DeltaKind.MAJOR -> "跨 major 版本"
        DeltaKind.REMOVED -> "依賴移除"
        DeltaKind.DOWNGRADE -> "版本後退"
        DeltaKind.UNPARSEABLE -> "無法判定"
        DeltaKind.MINOR, DeltaKind.PATCH, DeltaKind.ADDED -> kind.displayName
    }

    /**
     * 資訊性與已核准表格的「類別」欄——簡短即可，這裡不需要讀者採取行動。
     *
     * 刻意窮舉而不留 `else`：新增 [DeltaKind] 時這裡會編譯失敗，
     * 強迫決定該類別在報告中怎麼稱呼，而不是無聲落到 `displayName` 上。
     */
    private fun categoryLabel(kind: DeltaKind): String = when (kind) {
        DeltaKind.PATCH -> "patch"
        DeltaKind.MINOR -> "minor"
        DeltaKind.MAJOR -> "major"
        DeltaKind.ADDED -> "新增"
        DeltaKind.REMOVED -> "移除"
        DeltaKind.DOWNGRADE -> "版本後退"
        DeltaKind.UNPARSEABLE -> "無法解析"
    }
}
