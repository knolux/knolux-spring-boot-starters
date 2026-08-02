package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 快照是「模組自己解析、根專案負責判定」這個切分的交接點。
 *
 * 之所以需要交接點：解析 `runtimeClasspath` 必須發生在模組自己的任務裡（跨專案解析在
 * `--parallel` 下拿不到 exclusive lock），但成敗判定必須一次看完所有模組（FR-012）。
 * 兩個約束無法在同一個任務內同時滿足，於是模組寫快照、根專案讀快照。
 *
 * 這個檔案的行為因此是**閘門正確性的前提**：讀錯或讀漏，後面所有比對都建立在錯的資料上。
 */
class DependencySnapshotTest {

    @TempDir
    lateinit var workDir: File

    private val module = "knolux-redis-spring-boot-starter"

    @Test
    fun `寫出後讀回得到相同的依賴集合`() {
        val original = DependencySet(
            module,
            mapOf(
                DependencyCoordinate("io.lettuce", "lettuce-core") to "7.5.2.RELEASE",
                DependencyCoordinate("io.netty", "netty-buffer") to "4.2.15.Final",
            ),
        )
        val file = File(workDir, "snapshot.txt")

        DependencySnapshot.write(original, file)

        assertEquals(original, DependencySnapshot.read(module, file))
    }

    @Test
    fun `寫出時自動建立上層目錄`() {
        // 快照寫在 build 目錄下，首次執行時該目錄尚不存在。
        val file = File(workDir, "巢狀/目錄/snapshot.txt")

        DependencySnapshot.write(DependencySet(module, emptyMap()), file)

        assertTrue(file.isFile, "應建立上層目錄並寫出檔案")
    }

    @Test
    fun `寫出的內容不含 BOM 且換行為 LF`() {
        // 與基準線檔同一套決定性要求：本 repo 於 Windows 開發、CI 於 Linux，
        // 交給平台決定會讓兩地產出的快照不同，而快照正是基準線比對的另一側。
        val file = File(workDir, "snapshot.txt")

        DependencySnapshot.write(
            DependencySet(module, mapOf(DependencyCoordinate("io.lettuce", "lettuce-core") to "7.5.2.RELEASE")),
            file,
        )

        val content = file.readText(Charsets.UTF_8)
        assertFalse(content.startsWith("﻿"), "不應寫出 BOM")
        assertFalse(content.contains("\r"), "換行應固定為 LF")
    }

    @Test
    fun `檔案不存在時 fail-fast 而非當成空集合`() {
        // 當成空集合的後果最惡劣：閘門會把該模組的每一筆依賴都判為 REMOVED（阻擋性），
        // 或在基準線更新時把整份基準線清空——兩者都比直接失敗難追查得多。
        assertThrows<IllegalStateException> { DependencySnapshot.read(module, File(workDir, "不存在.txt")) }
    }

    @Test
    fun `檔案不存在的訊息需指出模組、路徑與產生快照的任務`() {
        val message = assertThrows<IllegalStateException> {
            DependencySnapshot.read(module, File(workDir, "不存在.txt"))
        }.message.orEmpty()

        assertTrue(message.contains(module), "訊息應指出模組名，實際為『$message』")
        assertTrue(message.contains("不存在.txt"), "訊息應指出預期路徑，實際為『$message』")
        assertTrue(
            message.contains("resolveDependencySnapshot"),
            "訊息應指出產生快照的任務，實際為『$message』",
        )
    }

    @Test
    fun `內容格式錯誤時 fail-fast 並帶出模組名`() {
        val file = File(workDir, "snapshot.txt").apply { writeText("這行壞掉了\n", Charsets.UTF_8) }

        val message = assertThrows<IllegalArgumentException> { DependencySnapshot.read(module, file) }.message.orEmpty()

        assertTrue(message.contains(module), "訊息應指出模組名，實際為『$message』")
    }

    @Test
    fun `容忍檔頭的 UTF-8 BOM`() {
        // 寫入端不寫 BOM，但快照可能被人手動開起來看過又存檔。
        val file = File(workDir, "snapshot.txt")
            .apply { writeText("﻿io.lettuce:lettuce-core:7.5.2.RELEASE\n", Charsets.UTF_8) }

        assertEquals("7.5.2.RELEASE", DependencySnapshot.read(module, file).entries.values.single())
    }
}
