package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 對應 research.md R6 與 tasks.md T016。
 *
 * 這是整個功能中**唯一**接觸 git 的位置，所以測試必須用真的 git repo 而非 mock——
 * `git show <ref>:<path>` 在 ref 不存在與檔案不存在時的行為差異，正是這個 adapter
 * 要處理的核心，而 mock 只會複述我對 git 的假設。
 */
class GitBaselineSourceTest {

    @TempDir
    lateinit var repoDir: File

    private val module = "knolux-redis-spring-boot-starter"
    private val baselineDirPath = "gradle/dependency-baseline"

    @BeforeEach
    fun initRepo() {
        git("init", "--initial-branch=main")
        git("config", "user.email", "test@knolux.local")
        git("config", "user.name", "Test")
    }

    @Test
    fun `可取得指定 ref 當時的基準線`() {
        commitBaseline("io.lettuce:lettuce-core:6.8.2.RELEASE\n", tag = "knolux-redis-spring-boot-starter/v1.3.0")
        // 之後的變更不該影響對舊 tag 的查詢
        commitBaseline("io.lettuce:lettuce-core:7.5.2.RELEASE\n", tag = null)

        val found = load("knolux-redis-spring-boot-starter/v1.3.0") as BaselineLookup.Found

        assertEquals("6.8.2.RELEASE", found.dependencySet.entries.values.single())
        assertEquals(module, found.dependencySet.moduleName)
    }

    @Test
    fun `來源說明帶出 ref 與檔案路徑`() {
        commitBaseline("io.lettuce:lettuce-core:6.8.2.RELEASE\n", tag = "v1.3.0")

        val found = load("v1.3.0") as BaselineLookup.Found

        assertTrue(found.origin.contains("v1.3.0"), "來源應帶出 ref，實際為『${found.origin}』")
        assertTrue(found.origin.contains("$module.txt"), "來源應帶出檔名，實際為『${found.origin}』")
    }

    @Test
    fun `ref 不存在時回傳無基準線`() {
        commitBaseline("io.lettuce:lettuce-core:6.8.2.RELEASE\n", tag = "v1.3.0")

        val lookup = load("v0.0.1-不存在")

        assertTrue(lookup is BaselineLookup.Missing, "應回傳 Missing，實際為 $lookup")
    }

    @Test
    fun `檔案在該 ref 尚未存在時回傳無基準線`() {
        // 閘門本身導入之前的 tag 一定沒有基準線檔——這是導入首日的常態，不是錯誤。
        File(repoDir, "README.md").writeText("初始提交\n")
        git("add", ".")
        git("commit", "-m", "chore: 初始提交")
        git("tag", "v1.0.0")

        val lookup = load("v1.0.0")

        assertTrue(lookup is BaselineLookup.Missing, "應回傳 Missing，實際為 $lookup")
    }

    @Test
    fun `無基準線的理由需帶出 ref 與補救說明`() {
        commitBaseline("io.lettuce:lettuce-core:6.8.2.RELEASE\n", tag = "v1.3.0")

        val missing = load("v9.9.9") as BaselineLookup.Missing

        assertTrue(missing.reason.contains("v9.9.9"), "理由應帶出 ref，實際為『${missing.reason}』")
        assertTrue(missing.reason.contains("$module.txt"), "理由應帶出預期路徑")
    }

    @Test
    fun `該 ref 的檔案格式錯誤時 fail-fast`() {
        commitBaseline("這行壞掉了\n", tag = "v1.3.0")

        val error = runCatching { load("v1.3.0") }.exceptionOrNull()

        assertTrue(
            error is IllegalArgumentException,
            "格式錯誤應 fail-fast 而非被當成無基準線，實際為 $error",
        )
    }

    // ---------- helpers ----------

    private fun load(ref: String): BaselineLookup =
        GitBaselineSource(repoDir, ref, baselineDirPath).load(module)

    private fun commitBaseline(content: String, tag: String?) {
        val file = File(repoDir, "$baselineDirPath/$module.txt")
        file.parentFile.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        git("add", ".")
        git("commit", "-m", "build: 更新基準線")
        tag?.let { git("tag", it) }
    }

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "git ${args.joinToString(" ")} 失敗（exit=$exitCode）：$output" }
    }
}
