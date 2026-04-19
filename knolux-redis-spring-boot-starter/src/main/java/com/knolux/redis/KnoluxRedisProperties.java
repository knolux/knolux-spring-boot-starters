package com.knolux.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Knolux Redis Spring Boot Starter 的外部化設定屬性類別。
 *
 * <p>所有屬性均以 {@code knolux.redis} 為前綴，可透過 {@code application.yml} 或
 * {@code application.properties} 進行設定。Spring Boot 的 {@link ConfigurationProperties}
 * 機制會在應用程式啟動時自動將設定值綁定至此類別的欄位。
 *
 * <h2>application.yml 設定範例</h2>
 *
 * <h3>Standalone（直連）模式</h3>
 * <pre>{@code
 * knolux:
 *   redis:
 *     url: redis://:mypassword@localhost:6379/0
 *     timeout-ms: 2000ms
 *     read-from: MASTER
 * }</pre>
 *
 * <h3>Sentinel（高可用）模式</h3>
 * <pre>{@code
 * knolux:
 *   redis:
 *     url: redis-sentinel://:mypassword@sentinel-host:26379/mymaster
 *     timeout-ms: 3000ms
 *     read-from: REPLICA_PREFERRED
 * }</pre>
 *
 * <h2>URL 格式說明</h2>
 * <ul>
 *   <li><strong>Standalone：</strong>{@code redis://[:password@]host:port[/db]}</li>
 *   <li><strong>Sentinel：</strong>{@code redis-sentinel://[:password@]sentinel-host:sentinel-port/master-name}</li>
 * </ul>
 *
 * @see KnoluxRedisAutoConfiguration
 */
@ConfigurationProperties(prefix = "knolux.redis")
public class KnoluxRedisProperties {

    /**
     * Redis 連線 URL。
     *
     * <p>此為必填欄位，若未設定則應用程式啟動時將拋出 {@link IllegalArgumentException}。
     *
     * <p>支援兩種 URL scheme：
     * <ul>
     *   <li>{@code redis://} — Standalone 直連模式，連接單一 Redis 節點</li>
     *   <li>{@code redis-sentinel://} — Sentinel 高可用模式，透過哨兵節點進行主從自動切換</li>
     * </ul>
     *
     * <p>URL 格式範例：
     * <ul>
     *   <li>{@code redis://localhost:6379} — 無密碼 Standalone</li>
     *   <li>{@code redis://:secret@localhost:6379} — 有密碼 Standalone</li>
     *   <li>{@code redis://:secret@localhost:6379/3} — 指定資料庫編號（DB 3）</li>
     *   <li>{@code redis-sentinel://:secret@sentinel:26379/mymaster} — Sentinel 模式</li>
     * </ul>
     */
    private String url;

    /**
     * 連線指令逾時時間，預設為 {@code 1000ms}（1 秒）。
     *
     * <p>此設定控制 Lettuce 客戶端等待 Redis 指令回應的最長時間。
     * 超過此時間若仍未收到回應，將拋出 {@link io.lettuce.core.RedisCommandTimeoutException}。
     *
     * <p>Spring Boot 的 {@link org.springframework.boot.convert.DurationStyle} 支援多種格式：
     * <ul>
     *   <li>{@code 1000ms} — 毫秒</li>
     *   <li>{@code 2s} — 秒</li>
     *   <li>{@code PT2S} — ISO-8601 格式</li>
     * </ul>
     *
     * <p>建議根據業務場景調整：
     * <ul>
     *   <li>快取場景：500ms ~ 1000ms</li>
     *   <li>Sentinel 高可用場景：2000ms ~ 3000ms（需考慮主從切換延遲）</li>
     * </ul>
     */
    private Duration timeoutMs = Duration.ofMillis(1000);

    /**
     * Lettuce 客戶端讀取策略，預設為 {@code REPLICA_PREFERRED}。
     *
     * <p>此設定決定 Lettuce 在執行讀取指令時應選擇哪個節點，
     * 可有效分散讀取壓力、降低 Master 節點負載。
     *
     * <p>支援的策略值（不區分大小寫）：
     * <ul>
     *   <li>{@code REPLICA_PREFERRED}（預設）— 優先從 Replica（從節點）讀取；
     *       若 Replica 不可用則自動降級至 Master。適用於大部分讀多寫少的場景。</li>
     *   <li>{@code MASTER} — 所有讀寫操作均走 Master（主節點）。
     *       適用於對資料一致性要求極高的場景，或純 Standalone 不含 Replica 的部署。</li>
     *   <li>{@code REPLICA} — 強制只從 Replica 讀取。
     *       若 Replica 不可用則操作失敗，適用於明確分離讀寫流量的場景。</li>
     *   <li>{@code ANY} — 從任意可用節點讀取（包含 Master 與所有 Replica）。
     *       適用於最大化讀取吞吐量的場景。</li>
     * </ul>
     *
     * <p>注意：在純 Standalone 模式（{@code redis://} 且 {@code readFrom=MASTER}）下，
     * Lettuce 不會啟動 topology refresh，可減少背景連線開銷。
     */
    private String readFrom = "REPLICA_PREFERRED";

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Duration getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Duration timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getReadFrom() {
        return readFrom;
    }

    public void setReadFrom(String readFrom) {
        this.readFrom = readFrom;
    }
}
