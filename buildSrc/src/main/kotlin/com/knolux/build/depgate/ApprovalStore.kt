package com.knolux.build.depgate

import org.tomlj.Toml
import org.tomlj.TomlTable
import java.io.File

/**
 * 讀取核准檔 `gradle/dependency-approvals.toml`（contracts/file-formats.md §2）。
 *
 * 這是閘門唯一的「刻意放行」入口，因此它的失效模式特別惡劣——**任何無法解析的項目
 * 若被靜默略過，就會變成「沒有核准」**，而維護者仍以為已經處理過了，於是本該被擋下的
 * 破壞性變更在下一次沒人看的建置中悄悄通過。故此處所有不合法輸入一律 fail-fast，
 * 且訊息必帶出項目序號與實際內容：一份三十筆的核准檔，光說「格式錯誤」找不到是哪一筆。
 *
 * 唯一的例外是**檔案不存在**：那是「目前沒有任何破壞性變更需要核准」的常態，
 * 要求維護者放一個空檔只是形式主義。
 */
object ApprovalStore {

    /** 表格陣列的鍵；核准檔以 `[[approval]]` 一段一筆的形式書寫。 */
    private const val ARRAY_KEY = "approval"

    private val REQUIRED_KEYS = listOf("module", "coordinate", "kind", "from", "reason")

    /** `to` 於 [DeltaKind.REMOVED] 時省略，故不在必填之列。 */
    private val KNOWN_KEYS = REQUIRED_KEYS + "to"

    private const val UTF8_BOM = "﻿"

    /**
     * 載入 [file] 中的全部核准項；檔案不存在時回傳空清單。
     *
     * @throws IllegalStateException TOML 格式錯誤、欄位缺漏、`kind` 不合法、或含無法辨識的欄位
     */
    fun load(file: File): List<Approval> {
        if (!file.isFile) return emptyList()

        val origin = file.path.replace('\\', '/')
        // 讀取端容忍 BOM：有人用 Windows 的編輯器動過核准檔是可預期的，
        // 為此讓整份核准失效，等於把「放行」變成「阻擋」——方向雖安全但難以理解。
        val result = Toml.parse(file.readText(Charsets.UTF_8).removePrefix(UTF8_BOM))
        check(!result.hasErrors()) {
            "核准檔 $origin 不是合法的 TOML：${result.errors().joinToString("；") { it.toString() }}"
        }

        val unknownTopLevel = result.keySet() - ARRAY_KEY
        check(unknownTopLevel.isEmpty()) {
            "核准檔 $origin 含有無法辨識的頂層鍵 ${unknownTopLevel.joinToString("、")}；" +
                "本檔案只接受 `[[$ARRAY_KEY]]` 表格陣列"
        }
        if (!result.contains(ARRAY_KEY)) return emptyList()

        check(result.isArray(ARRAY_KEY)) {
            "核准檔 $origin 的 `$ARRAY_KEY` 必須寫成 `[[$ARRAY_KEY]]`（表格陣列，一段一筆），" +
                "實際為 ${result.get(ARRAY_KEY)?.javaClass?.simpleName}"
        }

        val array = checkNotNull(result.getArray(ARRAY_KEY))
        return (0 until array.size()).map { index -> parseApproval(array.get(index), index + 1, origin) }
    }

    /**
     * 自 [file] 移除 [stale] 所列的核准項，回傳實際被移除者（FR-017）。
     *
     * **以行區塊刪除，不整檔重產**：核准檔是維護者手寫的，標頭警語與各項旁自行加註的
     * 追蹤資訊（issue 連結、當初的決策脈絡）在重產時會一併消失——而那些內容正是日後
     * 回頭審視「當年為什麼放行這件事」時唯一的線索。重產只留得下閘門看得懂的欄位。
     *
     * 區塊界定為「自 `[[approval]]` 那一行起，至下一個 `[[approval]]` 的前一行為止」。
     * 因此**註解請寫在所屬項目的下方**；寫在上方會被歸給前一項，隨前一項一併刪除。
     *
     * @param stale 要移除的項目；內容相同的重複項會一併移除
     * @throws IllegalStateException 檔案內的 `[[approval]]` 行數與剖析結果筆數不符（代表本函式的
     *   區塊界定與 TOML 剖析器對這份檔案的理解已分歧，此時任何刪除都可能刪錯行）
     */
    fun prune(file: File, stale: List<Approval>): List<Approval> {
        if (stale.isEmpty() || !file.isFile) return emptyList()

        val approvals = load(file)
        val removedIndices = approvals.indices.filter { approvals[it] in stale }.toSet()
        if (removedIndices.isEmpty()) return emptyList()

        val lines = file.readText(Charsets.UTF_8).removePrefix(UTF8_BOM).lines()
        val starts = lines.indices.filter { lines[it].trimStart().startsWith("[[$ARRAY_KEY]]") }
        check(starts.size == approvals.size) {
            "核准檔 ${file.path.replace('\\', '/')} 有 ${approvals.size} 筆核准，卻找到 ${starts.size} 行 `[[$ARRAY_KEY]]`；" +
                "無法可靠地判斷該刪哪幾行，故不動這個檔案。請手動移除已過期的項目"
        }

        // 逐行保留：只有落在「要刪的區塊」內的行會被丟棄，其餘（含標頭註解）逐字留下。
        val dropped = removedIndices.flatMap { index ->
            val end = starts.getOrNull(index + 1) ?: lines.size
            starts[index] until end
        }.toSet()

        val kept = lines.filterIndexed { index, _ -> index !in dropped }
        file.writeText(kept.joinToString("\n").trimEnd('\n') + "\n", Charsets.UTF_8)

        return removedIndices.map { approvals[it] }
    }

    private fun parseApproval(element: Any?, itemNumber: Int, origin: String): Approval {
        val table = element as? TomlTable
            ?: error("核准檔 $origin 第 $itemNumber 項不是表格（實際為 $element）；每筆核准須以 `[[$ARRAY_KEY]]` 起始")

        // 例外一律補上項目序號後再拋：Approval 與 DependencyCoordinate 的驗證訊息說得出
        // 「哪裡不對」，卻說不出「是哪一筆」——後者才是維護者修檔案時真正需要的資訊。
        return try {
            val unknownKeys = table.keySet() - KNOWN_KEYS.toSet()
            check(unknownKeys.isEmpty()) {
                "含有無法辨識的欄位 ${unknownKeys.joinToString("、")}（可用欄位：${KNOWN_KEYS.joinToString("、")}）；" +
                    "欄位名打錯不會被 TOML 擋下，但會讓這筆核准實際上不生效"
            }
            Approval(
                module = table.requiredString("module"),
                coordinate = DependencyCoordinate.parse(table.requiredString("coordinate")),
                from = table.requiredString("from"),
                to = table.optionalString("to"),
                kind = DeltaKind.parseApprovable(table.requiredString("kind")),
                reason = table.requiredString("reason"),
            )
        } catch (e: Exception) {
            throw IllegalStateException("核准檔 $origin 第 $itemNumber 項無效：${e.message ?: e}", e)
        }
    }

    private fun TomlTable.requiredString(key: String): String =
        optionalString(key) ?: error("缺少必填欄位 `$key`")

    private fun TomlTable.optionalString(key: String): String? {
        if (!contains(key)) return null
        val value = getString(key)
        check(!value.isNullOrBlank()) { "欄位 `$key` 不得為空白" }
        return value.trim()
    }
}
