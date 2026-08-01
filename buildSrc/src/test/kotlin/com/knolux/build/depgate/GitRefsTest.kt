package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 對應 contracts/gradle-tasks.md 的 `--base` 選項與 spec FR-003。
 *
 * 比較基準取的是 **merge-base** 而非 ref 本身：PR 分支落後 `dev` 好幾天是常態，
 * 直接拿 `dev` 的當前狀態來比，會把「別人合併進 dev 的依賴升級」算成這支 PR 的責任。
 *
 * 與 [GitBaselineSourceTest] 同樣使用真實 git repo——這個類別的全部價值就在於正確
 * 處理 git 的實際行為（ref 不存在、無共同祖先、淺層 clone），mock 只會複述我的假設。
 */
class GitRefsTest {

    @TempDir
    lateinit var repoDir: File

    private val refs: GitRefs by lazy { GitRefs(repoDir) }

    @BeforeEach
    fun initRepo() {
        git("init", "--initial-branch=main")
        git("config", "user.email", "test@knolux.local")
        git("config", "user.name", "Test")
        commit("初始提交")
    }

    @Test
    fun `明確指定的 ref 優先於預設`() {
        git("branch", "release")

        val base = refs.resolveComparisonBase(explicit = "release", defaults = listOf("main"))

        assertEquals("release", base.ref)
    }

    @Test
    fun `未指定時採用第一個存在的預設 ref`() {
        // 預設為 origin/dev，不存在時退回 origin/main——fork 或尚未建立 dev 的情境。
        git("branch", "main-mirror")

        val base = refs.resolveComparisonBase(explicit = null, defaults = listOf("origin/dev", "main-mirror"))

        assertEquals("main-mirror", base.ref)
    }

    @Test
    fun `比較基準為 merge-base 而非 ref 當前狀態`() {
        // 情境：PR 分支自 dev 分出後，dev 又前進了。此時該比的是分岔點。
        val forkPoint = revParse("HEAD")
        git("checkout", "-b", "feature")
        commit("PR 的變更")
        git("checkout", "main")
        commit("其他人合併進 dev 的變更")
        git("checkout", "feature")

        val base = refs.resolveComparisonBase(explicit = "main", defaults = emptyList())

        assertEquals(forkPoint, base.mergeBase, "應取分岔點，否則別人的依賴升級會被算到這支 PR 頭上")
    }

    @Test
    fun `描述文字同時帶出 ref 與 merge-base 短碼`() {
        val base = refs.resolveComparisonBase(explicit = "main", defaults = emptyList())

        assertTrue(base.describe.contains("main"), "實際為『${base.describe}』")
        assertTrue(base.describe.contains(base.mergeBase.take(7)), "實際為『${base.describe}』")
    }

    @Test
    fun `所有預設 ref 都不存在時 fail-fast 並提示 CI 設定`() {
        // 淺層 clone（GitHub Actions 預設 fetch-depth=1）取不到遠端分支——
        // 這是導入時最常見的失敗，訊息必須直接指出修法，否則會被誤判成閘門壞掉。
        val error = runCatching {
            refs.resolveComparisonBase(explicit = null, defaults = listOf("origin/dev", "origin/main"))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException, "實際為 $error")
        assertTrue(error!!.message!!.contains("origin/dev"), "訊息應列出試過的 ref")
        assertTrue(error.message!!.contains("origin/main"))
        assertTrue(error.message!!.contains("fetch-depth"), "訊息應提示淺層 clone 的修法，實際為『${error.message}』")
    }

    @Test
    fun `明確指定的 ref 不存在時 fail-fast`() {
        // 靜默退回預設會讓使用者以為比對過了，實際上比的是別的東西。
        val error = runCatching {
            refs.resolveComparisonBase(explicit = "沒有這個 ref", defaults = listOf("main"))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException, "實際為 $error")
        assertTrue(error!!.message!!.contains("沒有這個 ref"))
    }

    @Test
    fun `無共同祖先時 fail-fast 而非當成無差異`() {
        // 孤立分支與主線沒有 merge-base；此時「沒有差異」是錯的答案，
        // 靜默放行等同於對這支 PR 完全不設防。
        git("checkout", "--orphan", "orphan")
        commit("孤立分支")

        val error = runCatching { refs.resolveComparisonBase(explicit = "main", defaults = emptyList()) }
            .exceptionOrNull()

        assertTrue(error is IllegalStateException, "實際為 $error")
        assertTrue(error!!.message!!.contains("merge-base"), "實際為『${error.message}』")
    }

    // ---------- helpers ----------

    private fun revParse(ref: String): String = run("rev-parse", ref).trim()

    private fun commit(message: String) {
        File(repoDir, "file.txt").appendText("$message\n")
        git("add", ".")
        git("commit", "-m", message)
    }

    private fun git(vararg args: String) {
        run(*args)
    }

    private fun run(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "git ${args.joinToString(" ")} 失敗（exit=$exitCode）：$output" }
        return output
    }
}
