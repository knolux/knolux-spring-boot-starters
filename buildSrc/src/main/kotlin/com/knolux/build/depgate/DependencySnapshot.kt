package com.knolux.build.depgate

import java.io.File

/**
 * 模組解析結果的中繼檔：由各模組的 `resolveDependencySnapshot` 寫出，由根專案的閘門任務讀入。
 *
 * **為什麼需要這個交接點**——兩項約束無法在同一個任務內同時滿足：
 * 1. 解析 `runtimeClasspath` 必須發生在**模組自己的任務**裡。根專案的任務去解析
 *    `:<module>:runtimeClasspath` 屬跨專案解析，Gradle 要求解析方持有該 configuration 的
 *    exclusive lock，根專案拿不到，於是在 `--parallel` 下失敗。
 * 2. 成敗判定必須**一次看完所有模組**（FR-012），因此不能讓每個模組各自拋例外。
 *
 * 於是模組只負責產出資料，根專案負責全部的判定——快照就是兩者之間的邊界。
 *
 * 格式刻意與基準線檔完全相同（[DependencySet.serialize] / [DependencySet.parse]）：
 * 快照與基準線本來就是同一份東西的兩個時間點，用兩套格式只會讓「為何兩邊對不起來」
 * 多一個不必要的懷疑對象。
 */
object DependencySnapshot {

    /** 產生快照的任務名，出現在讀取失敗的訊息裡供人直接照做。 */
    const val PRODUCER_TASK = "resolveDependencySnapshot"

    /**
     * 寫出快照。上層目錄不存在時自動建立（首次執行時 build 目錄還是空的）。
     *
     * 一律 UTF-8 無 BOM；換行由 [DependencySet.serialize] 固定為 LF。本 repo 於 Windows
     * 開發、CI 於 Linux，交給平台決定會讓兩地產出的快照不同，而快照正是基準線比對的另一側。
     */
    fun write(dependencySet: DependencySet, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(dependencySet.serialize(), Charsets.UTF_8)
    }

    /**
     * 讀回快照。
     *
     * 檔案不存在時 fail-fast，**不**退回空集合：空集合會讓閘門把該模組的每一筆依賴
     * 都判成 `REMOVED`（阻擋性），或讓 `updateDependencyBaseline` 把整份基準線清空。
     * 兩者都遠比直接失敗難追查——而這種靜默降級正是本專案明確反對的模式。
     *
     * @throws IllegalStateException 快照不存在
     * @throws IllegalArgumentException 快照內容格式不符（由 [DependencySet.parse] 拋出）
     */
    fun read(moduleName: String, file: File): DependencySet {
        check(file.isFile) {
            "找不到模組 $moduleName 的依賴快照 ${file.path.replace('\\', '/')}；" +
                "此檔應由 :$moduleName:$PRODUCER_TASK 產生，請確認該任務已執行"
        }

        // 讀取端容忍 BOM，寫入端一律不寫——快照可能被人手動開起來看過又存檔。
        return DependencySet.parse(moduleName, file.readText(Charsets.UTF_8).removePrefix(UTF8_BOM))
    }

    private const val UTF8_BOM = "﻿"
}
