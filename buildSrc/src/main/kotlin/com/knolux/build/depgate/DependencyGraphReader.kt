package com.knolux.build.depgate

import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

/**
 * 走訪 Gradle 的解析結果圖，取出所有元件（憲章 III 的 adapter）。
 *
 * 這裡**只做走訪**，不做任何取捨判斷——哪些依賴該進基準線是領域決策，屬於
 * [DependencySetBuilder]。分開的理由很實際：Gradle 的 `ResolvedComponentResult`
 * 無法在單元測試中合理地建構，把判斷混進來就等於讓它永遠測不到。
 *
 * 走的是 `runtimeClasspath`。這是下游消費者實際會拿到的傳遞依賴集合——
 * 兩個 starter 都以 `api` scope 曝露核心依賴，故此集合的變動即為對外的相容性變動。
 */
object DependencyGraphReader {

    /**
     * 自根元件遞迴走訪整張圖。
     *
     * **略過 constraint 邊**：BOM 帶來的版本約束會在圖上產生大量邊，但「只被約束到」
     * 的元件並不在 classpath 上。真正在用的元件必定另有一條實際依賴邊抵達，
     * 因此略過 constraint 不會漏掉任何東西，卻能避免把整份 BOM 寫進基準線。
     *
     * @throws IllegalStateException 圖中存在無法解析的依賴
     */
    fun read(root: ResolvedComponentResult): List<ResolvedEntry> {
        val entries = mutableListOf<ResolvedEntry>()
        val visited = mutableSetOf<Any>()

        fun walk(component: ResolvedComponentResult) {
            if (!visited.add(component.id)) return

            component.moduleVersion?.let { module ->
                entries += ResolvedEntry(
                    group = module.group,
                    artifact = module.name,
                    version = module.version,
                    isProject = component.id is ProjectComponentIdentifier,
                )
            }

            component.dependencies.forEach { dependency ->
                when (dependency) {
                    // 無法解析卻繼續產生基準線，等於把一個殘缺的集合當成事實記錄下來。
                    is UnresolvedDependencyResult -> error(
                        "依賴 ${dependency.attempted.displayName} 無法解析：${dependency.failure.message}",
                    )

                    is ResolvedDependencyResult -> if (!dependency.isConstraint) walk(dependency.selected)

                    else -> Unit
                }
            }
        }

        walk(root)
        return entries
    }
}
