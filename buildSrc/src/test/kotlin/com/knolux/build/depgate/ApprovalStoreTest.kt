package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 對應 contracts/file-formats.md §2。
 *
 * 這個類別是「刻意放行」的唯一入口，因此它的失效模式特別惡劣：**打錯字的核准會變成
 * 沒有核准**，而維護者以為已經處理過了。故所有不合法輸入一律 fail-fast，且訊息必須
 * 帶出項目序號與實際內容——否則一份三十筆的核准檔裡，沒有人找得到是哪一筆寫錯。
 */
class ApprovalStoreTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `剖析多筆核准並保留繁體中文理由原文`() {
        val approvals = load(
            """
            # 註解不影響剖析
            [[approval]]
            module = "knolux-redis-spring-boot-starter"
            coordinate = "io.lettuce:lettuce-core"
            kind = "MAJOR"
            from = "6.8.2.RELEASE"
            to = "7.5.2.RELEASE"
            reason = "隨 Spring Boot 4.1.0 升級而必然發生；已於 CHANGELOG 的「升級前必讀」段落揭露"

            [[approval]]
            module = "knolux-s3-spring-boot-starter"
            coordinate = "org.apache.httpcomponents.core5:httpcore5"
            kind = "DOWNGRADE"
            from = "5.4.2"
            to = "5.3.6"
            reason = "AWS SDK 尚未支援 5.4.x"
            """.trimIndent(),
        )

        assertEquals(2, approvals.size)
        assertEquals(DependencyCoordinate("io.lettuce", "lettuce-core"), approvals[0].coordinate)
        assertEquals(DeltaKind.MAJOR, approvals[0].kind)
        assertEquals("7.5.2.RELEASE", approvals[0].to)
        assertTrue(
            approvals[0].reason.contains("「升級前必讀」"),
            "理由會原文出現在報告中，不得被正規化，實際為『${approvals[0].reason}』",
        )
        assertEquals(DeltaKind.DOWNGRADE, approvals[1].kind)
    }

    @Test
    fun `REMOVED 可省略 to`() {
        val approvals = load(
            """
            [[approval]]
            module = "knolux-redis-spring-boot-starter"
            coordinate = "io.netty:netty-transport"
            kind = "REMOVED"
            from = "4.1.125.Final"
            reason = "Netty 4.1.x 線隨 Spring Boot 4.1.0 移除，統一至 4.2.x"
            """.trimIndent(),
        )

        assertEquals(1, approvals.size)
        assertEquals(null, approvals[0].to)
    }

    @Test
    fun `檔案不存在視為零筆核准而非錯誤`() {
        // 尚無任何破壞性變更需核准時的常態；此時要求存在一個空檔只是形式主義。
        assertEquals(emptyList<Approval>(), ApprovalStore.load(File(dir, "不存在.toml")))
    }

    @Test
    fun `空檔與僅含註解的檔案皆為零筆核准`() {
        assertEquals(emptyList<Approval>(), load(""))
        assertEquals(emptyList<Approval>(), load("# 只有註解，尚無核准項\n"))
    }

    @Test
    fun `欄位缺漏時 fail-fast 並帶出項目序號`() {
        val error = loadFailure(
            """
            [[approval]]
            module = "knolux-redis-spring-boot-starter"
            coordinate = "io.lettuce:lettuce-core"
            kind = "MAJOR"
            from = "6.8.2.RELEASE"
            to = "7.5.2.RELEASE"

            [[approval]]
            module = "knolux-s3-spring-boot-starter"
            coordinate = "io.netty:netty-common"
            kind = "MAJOR"
            from = "4.1.125.Final"
            to = "4.2.12.Final"
            reason = "有理由"
            """.trimIndent(),
        )

        assertTrue(error.message!!.contains("reason"), "應指出缺的是哪個欄位，實際為『${error.message}』")
        assertTrue(error.message!!.contains("第 1 項"), "應指出是第幾項，實際為『${error.message}』")
    }

    @Test
    fun `kind 填入資訊性類別時 fail-fast`() {
        // PATCH 本來就不會阻擋建置，替它寫核准代表作者誤解了機制——
        // 靜默接受會讓那份誤解一直留著，直到真正需要擋的東西沒被擋住。
        val error = loadFailure(
            """
            [[approval]]
            module = "knolux-redis-spring-boot-starter"
            coordinate = "ch.qos.logback:logback-classic"
            kind = "PATCH"
            from = "1.5.32"
            to = "1.5.34"
            reason = "例行更新"
            """.trimIndent(),
        )

        assertTrue(error.message!!.contains("PATCH"), "實際為『${error.message}』")
    }

    @Test
    fun `kind 為無法辨識的字串時 fail-fast 並列出可用值`() {
        val error = loadFailure(
            """
            [[approval]]
            module = "knolux-redis-spring-boot-starter"
            coordinate = "io.lettuce:lettuce-core"
            kind = "BREAKING"
            from = "6.8.2.RELEASE"
            to = "7.5.2.RELEASE"
            reason = "有理由"
            """.trimIndent(),
        )

        assertTrue(error.message!!.contains("BREAKING"), "實際為『${error.message}』")
        assertTrue(error.message!!.contains("MAJOR"), "訊息須列出可用值，實際為『${error.message}』")
    }

    @Test
    fun `TOML 格式錯誤時 fail-fast 並帶出剖析器訊息`() {
        val error = loadFailure("[[approval]\nmodule = ")

        assertTrue(error.message!!.contains(APPROVALS_FILE), "訊息須指出是哪個檔案，實際為『${error.message}』")
    }

    @Test
    fun `REMOVED 以外的類別缺少 to 時 fail-fast`() {
        val error = loadFailure(
            """
            [[approval]]
            module = "knolux-redis-spring-boot-starter"
            coordinate = "io.lettuce:lettuce-core"
            kind = "MAJOR"
            from = "6.8.2.RELEASE"
            reason = "有理由"
            """.trimIndent(),
        )

        assertTrue(error.message!!.contains("to"), "實際為『${error.message}』")
    }

    @Test
    fun `座標格式錯誤時 fail-fast`() {
        // 打錯的座標永遠配不到任何 delta，等同於沒寫這筆核准。
        val error = loadFailure(
            """
            [[approval]]
            module = "knolux-redis-spring-boot-starter"
            coordinate = "io.lettuce.lettuce-core"
            kind = "MAJOR"
            from = "6.8.2.RELEASE"
            to = "7.5.2.RELEASE"
            reason = "有理由"
            """.trimIndent(),
        )

        assertTrue(error.message!!.contains("io.lettuce.lettuce-core"), "實際為『${error.message}』")
    }

    // ---------- 清除過期核准（US4／FR-017） ----------

    @Test
    fun `清除指定的核准並原文保留其餘內容`() {
        val file = write(TWO_APPROVALS)
        val approvals = ApprovalStore.load(file)

        val removed = ApprovalStore.prune(file, listOf(approvals[0]))

        assertEquals(listOf(approvals[0]), removed)
        val remaining = file.readText(Charsets.UTF_8)
        assertFalse(remaining.contains("lettuce"), "被清除者應完全消失，實際內容為：\n$remaining")
        assertTrue(remaining.contains("""reason = "AWS SDK 尚未支援 5.4.x""""), "留下者須逐字保留，實際內容為：\n$remaining")
        assertEquals(listOf(approvals[1]), ApprovalStore.load(file))
    }

    @Test
    fun `保留檔案標頭註解`() {
        // 標頭寫的是「這份檔案為什麼存在、怎麼填」。整檔重產會把它連同維護者自行加註的
        // 追蹤資訊一起抹掉，而那些內容正是日後回頭審視這些放行決策時唯一的線索。
        val file = write(TWO_APPROVALS)

        ApprovalStore.prune(file, ApprovalStore.load(file))

        val remaining = file.readText(Charsets.UTF_8)
        assertTrue(remaining.contains("# 標頭警語"), "標頭註解須保留，實際內容為：\n$remaining")
        assertEquals(emptyList<Approval>(), ApprovalStore.load(file))
    }

    @Test
    fun `沒有要清除的項目時不動檔案`() {
        val file = write(TWO_APPROVALS)

        val removed = ApprovalStore.prune(file, emptyList())

        assertEquals(emptyList<Approval>(), removed)
        assertEquals(TWO_APPROVALS, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `檔案不存在時清除為無操作`() {
        assertEquals(emptyList<Approval>(), ApprovalStore.prune(File(dir, "不存在.toml"), emptyList()))
    }

    private fun load(content: String): List<Approval> = ApprovalStore.load(write(content))

    private fun loadFailure(content: String): Exception =
        runCatching { load(content) }.exceptionOrNull() as? Exception
            ?: error("預期剖析失敗但成功了：$content")

    private fun write(content: String): File =
        File(dir, APPROVALS_FILE).apply { writeText(content, Charsets.UTF_8) }

    private companion object {
        const val APPROVALS_FILE = "dependency-approvals.toml"

        val TWO_APPROVALS = """
            # 標頭警語
            # kind 可用值：MAJOR / REMOVED / DOWNGRADE / UNPARSEABLE

            [[approval]]
            module = "knolux-redis-spring-boot-starter"
            coordinate = "io.lettuce:lettuce-core"
            kind = "MAJOR"
            from = "6.8.2.RELEASE"
            to = "7.5.2.RELEASE"
            reason = "隨 Spring Boot 4.1.0 升級而必然發生"

            [[approval]]
            module = "knolux-s3-spring-boot-starter"
            coordinate = "org.apache.httpcomponents.core5:httpcore5"
            kind = "DOWNGRADE"
            from = "5.4.2"
            to = "5.3.6"
            reason = "AWS SDK 尚未支援 5.4.x"

        """.trimIndent()
    }
}
