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
            renderInformationalSection(report.verdicts)?.let(::add)
            renderSkippedSection(report.verdicts)?.let(::add)
            renderHowToHandle(report.blockedDeltas.firstOrNull())?.let(::add)
        }

        return sections.joinToString(SECTION_SEPARATOR, postfix = "\n")
    }

    private fun renderHeader(report: GateReport): String = buildString {
        appendLine("# 依賴相容性閘門報告")
        appendLine()
        appendLine("**比較基準**：${report.comparisonBase}")
        append("**判定**：${renderVerdictLine(report)}")
    }

    private fun renderVerdictLine(report: GateReport): String {
        if (report.blocked) return "❌ 阻擋 — ${report.blockedDeltas.size} 項未核准的破壞性變更"
        val informationalCount = report.verdicts.sumOf { verdict -> verdict.deltas.count { !it.blocking } }
        return if (informationalCount == 0) "✅ 通過 — 外溢依賴無變動" else "✅ 通過 — $informationalCount 項資訊性變動"
    }

    /**
     * 阻擋性變動。小標即為可貼進 CHANGELOG 的「⚠️ 升級前必讀」，因此**逐模組**分表——
     * CHANGELOG 的揭露對象是單一 artifact 的使用者，混在一起貼過去反而要再拆。
     */
    private fun renderBlockingSection(blockedByModule: Map<GateVerdict, List<DependencyDelta>>): String? {
        val modules = blockedByModule.filterValues { it.isNotEmpty() }
        if (modules.isEmpty()) return null

        return buildString {
            append("## ❌ 阻擋性變動（需核准或還原）")
            modules.forEach { (verdict, deltas) ->
                appendLine()
                appendLine()
                appendLine("### ⚠️ 升級前必讀：`${verdict.moduleName}` 的傳遞依賴破壞性變更")
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

    /** 資訊性表格的「類別」欄——簡短即可，這裡不需要讀者採取行動。 */
    private fun categoryLabel(kind: DeltaKind): String = when (kind) {
        DeltaKind.PATCH -> "patch"
        DeltaKind.MINOR -> "minor"
        DeltaKind.ADDED -> "新增"
        else -> kind.displayName
    }
}
