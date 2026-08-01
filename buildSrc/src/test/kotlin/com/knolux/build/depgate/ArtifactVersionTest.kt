package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * 對應 spec FR-008 / FR-009。
 *
 * 這個測試的重點不只是「能解析」，更是「解析不了的時候會怎樣」。
 * 2026-08-01 的事件之所以發生，根因就是缺少偵測；若解析器在遇到看不懂的
 * 版本字串時靜默回傳 null 或某個預設值，我們會蓋出一個「看起來在保護、
 * 實際上會放行」的閘門，比沒有閘門更危險。
 */
class ArtifactVersionTest {

    // ---------- 正向：本專案 2026-08-01 解析結果中實際出現的全部形式 ----------

    @ParameterizedTest(name = "{0} → {1}.{2}.{3} (qualifier={4})")
    @CsvSource(
        // 版本字串,            major, minor, patch, qualifier
        "7.5.2.RELEASE,          7,     5,     2,     RELEASE",   // io.lettuce:lettuce-core
        "4.2.15.Final,           4,     2,     15,    Final",     // io.netty:*
        "4.1.136.Final,          4,     1,     136,   Final",     // io.netty:* 舊版本線
        "2.49.3,                 2,     49,    3,     ",          // AWS SDK
        "1.5.34,                 1,     5,     34,    ",          // logback
        "1.0.0-SNAPSHOT,         1,     0,     0,     SNAPSHOT",
        "3.2.1.M1,               3,     2,     1,     M1",
    )
    fun `解析本專案實際出現的版本形式`(
        raw: String,
        major: Int,
        minor: Int,
        patch: Int,
        qualifier: String?,
    ) {
        val parsed = parsedOf(raw)

        assertEquals(major, parsed.major, "major 段")
        assertEquals(minor, parsed.minor, "minor 段")
        assertEquals(patch, parsed.patch, "patch 段")
        assertEquals(qualifier, parsed.qualifier, "qualifier")
    }

    @Test
    fun `缺少的版本段補 0`() {
        // snakeyaml 的 2.6 是真實案例：只有兩段
        val twoSegments = parsedOf("2.6")
        assertEquals(2, twoSegments.major)
        assertEquals(6, twoSegments.minor)
        assertEquals(0, twoSegments.patch)

        val oneSegment = parsedOf("7")
        assertEquals(7, oneSegment.major)
        assertEquals(0, oneSegment.minor)
        assertEquals(0, oneSegment.patch)
    }

    @Test
    fun `原始字串永遠保留不做正規化`() {
        // 錯誤訊息與基準線檔都要呈現原字串（FR-009 / FR-013）。
        // 一旦在解析階段正規化，7.5.2.RELEASE 會變成 7.5.2，
        // 基準線就再也對不上實際發布的 POM。
        assertEquals("7.5.2.RELEASE", parsedOf("7.5.2.RELEASE").raw)
        assertEquals("2.6", parsedOf("2.6").raw)
    }

    // ---------- 負向：無法解析時 MUST fail-fast，MUST NOT 靜默放行 ----------

    @ParameterizedTest(name = "無法解析：''{0}''")
    @ValueSource(
        strings = [
            "latest.release",
            "master-SNAPSHOT",
            "RELEASE",
            "+",
            "",
            "   ",
        ],
    )
    fun `無前導數字段時判定為無法解析`(raw: String) {
        val result = ArtifactVersion.parse(raw)

        assertTrue(
            result is ArtifactVersion.ParseResult.Unparseable,
            "『$raw』應判定為無法解析，實際為 $result",
        )
    }

    @Test
    fun `數字段溢位 Int 時判定為無法解析`() {
        val result = ArtifactVersion.parse("99999999999999999999.0.0")

        assertTrue(result is ArtifactVersion.ParseResult.Unparseable)
    }

    @Test
    fun `無法解析時的理由必須帶出原始字串`() {
        // 訊息不帶原字串，維護者就得自己去翻依賴樹才知道是哪一筆出問題——
        // 憲章 IV 要求例外訊息帶上實際值與修正提示。
        val raw = "latest.release"
        val result = ArtifactVersion.parse(raw) as ArtifactVersion.ParseResult.Unparseable

        assertEquals(raw, result.raw)
        assertTrue(
            result.reason.contains(raw),
            "reason 應包含原始字串『$raw』，實際為『${result.reason}』",
        )
    }

    @Test
    fun `解析失敗時不得回傳 null 也不得拋出例外`() {
        // 這正是本功能要根除的模式：靜默把「看不懂」當成「沒變動」。
        // 回傳型別為 sealed ParseResult，呼叫端被編譯器強迫處理失敗分支。
        val result: ArtifactVersion.ParseResult = ArtifactVersion.parse("完全不是版本")

        assertTrue(result is ArtifactVersion.ParseResult.Unparseable)
    }

    // ---------- 比較：DOWNGRADE 偵測的基礎 ----------

    @Test
    fun `版本比較只看數字段忽略 qualifier`() {
        assertTrue(parsedOf("7.5.2.RELEASE") > parsedOf("6.8.2.RELEASE"))
        assertTrue(parsedOf("4.2.15.Final") > parsedOf("4.1.136.Final"))
        assertEquals(0, parsedOf("1.0.0.RELEASE").compareTo(parsedOf("1.0.0.Final")))
    }

    @Test
    fun `版本比較依語意化版本而非字典序`() {
        // 字典序會給出相反答案：字串上 "1.9.0" > "1.10.0"
        assertTrue(parsedOf("1.10.0") > parsedOf("1.9.0"))
    }

    private fun parsedOf(raw: String): ArtifactVersion {
        val result = ArtifactVersion.parse(raw)
        assertTrue(result is ArtifactVersion.ParseResult.Parsed, "『$raw』應可解析，實際為 $result")
        return (result as ArtifactVersion.ParseResult.Parsed).version
    }
}
