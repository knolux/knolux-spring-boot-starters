package com.knolux.build.depgate

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/**
 * 自 Gradle 的解析結果取出「會外溢給下游消費者」的依賴集合。
 *
 * **MUST NOT 剖析 `dependencies` 任務的文字輸出**——research.md R1 已實證該輸出把
 * 請求版本與解析版本混在一起（同一份輸出中 Netty 同時出現 `4.2.13.Final` 與
 * `4.2.15.Final`），文字剖析會靜默產生錯誤的基準線，而錯誤的基準線比沒有基準線更糟。
 *
 * 取用 `runtimeClasspath` 的理由見 research.md R1：它與 `maven-publish` 的
 * `versionMapping { usage("java-api") { fromResolutionOf("runtimeClasspath") } }` 同源，
 * 也就是實際寫進 POM、下游真正會拿到的那一組版本。
 */
object ResolvedDependencyReader {

    /**
     * 由解析結果的根節點走訪整棵圖，收集全部外部模組依賴。
     *
     * 取 `rootComponent`（`Provider<ResolvedComponentResult>`）而非 `ResolutionResult.allComponents`，
     * 是為了讓任務相容於 Gradle 的 configuration cache：`ResolutionResult` 無法序列化進快取，
     * 而 `rootComponent` 可以作為任務輸入。兩者涵蓋的元件集合相同。
     *
     * 排除項：
     * - 根節點自身（就是本模組）
     * - `ProjectComponentIdentifier`（本 repo 內的 project 依賴）——它們發布時會被換成
     *   正式座標，不是「傳遞依賴」，混進來只會製造假警報
     */
    fun read(moduleName: String, root: ResolvedComponentResult): DependencySet {
        val entries = sortedMapOf<DependencyCoordinate, String>()
        val visited = mutableSetOf<ResolvedComponentResult>()

        fun walk(component: ResolvedComponentResult) {
            if (!visited.add(component)) return // 依賴圖可能有環，走過就不再展開

            (component.id as? ModuleComponentIdentifier)?.let { id ->
                if (component != root) {
                    entries[DependencyCoordinate(id.group, id.module)] = id.version
                }
            }

            component.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { walk(it.selected) }
        }

        walk(root)

        return DependencySet(moduleName, entries)
    }
}
