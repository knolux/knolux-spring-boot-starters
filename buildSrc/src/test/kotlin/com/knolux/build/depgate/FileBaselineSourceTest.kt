package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 對應 spec FR-005 與 data-model.md「型別間關係」中的 port/adapter 邊界。
 *
 * 這個 adapter 是**唯一**接觸基準線檔案系統的位置。它最重要的行為不是「讀得到時
 * 回傳什麼」，而是「讀不到時回傳什麼」——FR-005 要求無基準線時跳過而非失敗，
 * 因為新模組首次加入時本來就沒有基準線，讓它失敗等於逼人關掉閘門。
 */
class FileBaselineSourceTest {

    @TempDir
    lateinit var baselineDir: File

    private val module = "knolux-redis-spring-boot-starter"

    @Test
    fun `檔案存在時回傳解析後的依賴集合`() {
        writeBaseline(
            """
            # 由 ./gradlew updateDependencyBaseline 產生，請勿手動編輯。
            io.lettuce:lettuce-core:7.5.2.RELEASE
            io.netty:netty-buffer:4.2.15.Final
            """.trimIndent() + "\n",
        )

        val lookup = FileBaselineSource(baselineDir).load(module)

        val found = assertIsFound(lookup)
        assertEquals(2, found.dependencySet.entries.size)
        assertEquals(module, found.dependencySet.moduleName)
        assertEquals(
            "7.5.2.RELEASE",
            found.dependencySet.entries[DependencyCoordinate("io.lettuce", "lettuce-core")],
        )
    }

    @Test
    fun `找到時的來源說明可指出實際讀了哪個檔`() {
        writeBaseline("io.lettuce:lettuce-core:7.5.2.RELEASE\n")

        val found = assertIsFound(FileBaselineSource(baselineDir).load(module))

        assertTrue(found.origin.contains("$module.txt"), "來源說明應指出檔名，實際為『${found.origin}』")
    }

    @Test
    fun `檔案不存在時回傳無基準線而非拋例外`() {
        // FR-005：新模組首次加入時本來就沒有基準線。此時失敗等於逼人關掉閘門。
        val lookup = FileBaselineSource(baselineDir).load(module)

        assertTrue(lookup is BaselineLookup.Missing, "應回傳 Missing，實際為 $lookup")
    }

    @Test
    fun `無基準線的理由需指出檔案路徑與補救方式`() {
        val missing = FileBaselineSource(baselineDir).load(module) as BaselineLookup.Missing

        assertTrue(missing.reason.contains("$module.txt"), "理由應指出預期路徑")
        assertTrue(
            missing.reason.contains("updateDependencyBaseline"),
            "理由應指出補救方式，實際為『${missing.reason}』",
        )
    }

    @Test
    fun `目錄整個不存在時同樣回傳無基準線`() {
        val lookup = FileBaselineSource(File(baselineDir, "不存在的目錄")).load(module)

        assertTrue(lookup is BaselineLookup.Missing)
    }

    @Test
    fun `檔案格式錯誤時 fail-fast 而非當成無基準線`() {
        // 壞掉的基準線若被當成「沒有基準線」，整個模組會被靜默跳過——
        // 閘門看似運作，實際上什麼都沒擋。
        writeBaseline("這行壞掉了\n")

        assertThrows<IllegalArgumentException> { FileBaselineSource(baselineDir).load(module) }
    }

    @Test
    fun `容忍檔頭的 UTF-8 BOM`() {
        // 寫入端一律不寫 BOM，但有人用 Windows 的編輯器改過檔案時會被加上。
        writeBaseline("﻿io.lettuce:lettuce-core:7.5.2.RELEASE\n")

        val found = assertIsFound(FileBaselineSource(baselineDir).load(module))

        assertEquals("7.5.2.RELEASE", found.dependencySet.entries.values.single())
    }

    private fun writeBaseline(content: String) {
        baselineDir.mkdirs()
        File(baselineDir, "$module.txt").writeText(content, Charsets.UTF_8)
    }

    private fun assertIsFound(lookup: BaselineLookup): BaselineLookup.Found {
        assertTrue(lookup is BaselineLookup.Found, "應回傳 Found，實際為 $lookup")
        return lookup as BaselineLookup.Found
    }
}
