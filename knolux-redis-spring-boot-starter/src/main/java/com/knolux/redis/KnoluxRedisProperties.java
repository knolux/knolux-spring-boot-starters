package com.knolux.redis;

import com.knolux.redis.azure.KnoluxRedisEntraIdProperties;
import io.lettuce.core.SslVerifyMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

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
 * <h3>Cluster（分片）模式</h3>
 * <pre>{@code
 * knolux:
 *   redis:
 *     url: redis-cluster://:mypassword@node1:6379
 *     cluster:
 *       max-redirects: 5
 * }</pre>
 *
 * <h3>Azure Managed Redis（TLS + Entra ID 受控身分）</h3>
 * <pre>{@code
 * knolux:
 *   redis:
 *     url: rediss-cluster://mycache.eastus.redis.azure.net:10000
 *     timeout-ms: 3000ms
 *     azure:
 *       entra-id:
 *         enabled: true
 *         identity: SYSTEM_ASSIGNED
 * }</pre>
 *
 * <h2>URL 格式說明</h2>
 * <ul>
 *   <li><strong>Standalone：</strong>{@code redis://[:password@]host:port[/db]}</li>
 *   <li><strong>Sentinel：</strong>{@code redis-sentinel://[:password@]sentinel-host:sentinel-port/master-name}</li>
 *   <li><strong>Cluster：</strong>{@code redis-cluster://[:password@]seed-host:port}</li>
 * </ul>
 *
 * <p>三者各有對應的 TLS scheme：{@code rediss://}、{@code rediss-sentinel://}、
 * {@code rediss-cluster://}。是否加密僅由 scheme 決定，另見 {@link Ssl}。
 *
 * @see KnoluxRedisAutoConfiguration
 */
@Getter
@Setter
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
     * <p>此設定透過 {@link RedisUriUtils#parseReadFrom(String)} 解析，
     * 先以大小寫不敏感的 switch 比對所有具名策略，
     * 再將 {@code subnet:} / {@code regex:} 前綴形式委派至 Lettuce 處理。
     *
     * <h3>單一節點選擇</h3>
     * <ul>
     *   <li>{@code MASTER} / {@code UPSTREAM} — 所有讀寫均走 Master / Upstream。
     *       純 Standalone 時不啟動 topology refresh（背景連線最少）。</li>
     *   <li>{@code MASTER_PREFERRED} / {@code UPSTREAM_PREFERRED} —
     *       優先 Master，不可用時降級至 Replica。</li>
     *   <li>{@code REPLICA} / {@code SLAVE} — 強制只從 Replica 讀取，
     *       Replica 不可用時操作失敗。</li>
     *   <li>{@code REPLICA_PREFERRED}（預設）— 優先 Replica，
     *       不可用時降級至 Master。適用大部分讀多寫少場景。</li>
     *   <li>{@code ANY} — 任意可用節點（Master 或 Replica）。</li>
     *   <li>{@code ANY_REPLICA} — 任意 Replica 節點。</li>
     * </ul>
     *
     * <h3>進階策略</h3>
     * <ul>
     *   <li>{@code LOWEST_LATENCY} / {@code NEAREST} — 選擇延遲最低的節點，
     *       需動態 topology refresh。</li>
     *   <li>{@code subnet:<cidr,cidr,...>} — 限定特定子網路內的節點，
     *       例如 {@code subnet:192.168.0.0/16,2001:db8::/52}。</li>
     *   <li>{@code regex:<pattern>} — 以正規表示式比對節點 URI，
     *       例如 {@code regex:.*region-1.*}。</li>
     * </ul>
     *
     * <h3>備註</h3>
     * <p>未知值會記錄 {@code WARN} 並回退至 {@code REPLICA_PREFERRED}。
     * 純 Standalone 模式（{@code redis://}）且 {@code readFrom} 為 {@code MASTER} /
     * {@code UPSTREAM} 時，Lettuce 不啟動 topology refresh。
     *
     * @see io.lettuce.core.ReadFrom
     */
    private String readFrom = "REPLICA_PREFERRED";

    /**
     * TLS 相關設定，僅在 URL 使用 {@code rediss} 系列 scheme 時生效。
     */
    @NestedConfigurationProperty
    private Ssl ssl = new Ssl();

    /**
     * Cluster 模式設定，僅在 URL 使用 {@code redis-cluster://} 或
     * {@code rediss-cluster://} scheme 時生效。
     */
    @NestedConfigurationProperty
    private Cluster cluster = new Cluster();

    /**
     * Azure 專屬設定。
     */
    @NestedConfigurationProperty
    private Azure azure = new Azure();

    /**
     * TLS 設定。
     *
     * <p><strong>此處刻意沒有 {@code enabled} 旗標</strong>：是否加密完全由 URL scheme
     * 決定（{@code rediss://} / {@code rediss-sentinel://} / {@code rediss-cluster://}），
     * 單一來源可避免「scheme 說要加密、旗標說不要」這種互相矛盾且難以察覺的無效狀態。
     * 本類別只承載 scheme 表達不了的資訊。
     *
     * <pre>{@code
     * knolux:
     *   redis:
     *     url: rediss://mycache.eastus.redis.azure.net:10000
     *     ssl:
     *       verify-peer: FULL
     *       start-tls: false
     * }</pre>
     */
    @Getter
    @Setter
    public static class Ssl {

        /**
         * 伺服器憑證驗證強度，預設 {@link SslVerifyMode#FULL}。
         *
         * <ul>
         *   <li>{@code FULL} — 驗證憑證鏈與主機名稱，正式環境唯一正確的選項</li>
         *   <li>{@code CA} — 僅驗證憑證鏈，不比對主機名稱。
         *       用於憑證 CN／SAN 與實際連線位址不符的過渡情境</li>
         *   <li>{@code NONE} — 完全不驗證。<strong>僅限測試環境</strong>，
         *       設定此值會記錄 {@code WARN}，因為它讓連線可被中間人攔截</li>
         * </ul>
         */
        private SslVerifyMode verifyPeer = SslVerifyMode.FULL;

        /**
         * 是否以 STARTTLS 方式在既有明文連線上協商升級為 TLS，預設 {@code false}。
         *
         * <p>Azure Managed Redis 直接以 TLS 建立連線，不使用 STARTTLS，維持預設即可。
         */
        private boolean startTls = false;
    }

    /**
     * Cluster 模式設定。
     *
     * <pre>{@code
     * knolux:
     *   redis:
     *     url: rediss-cluster://mycache.eastus.redis.azure.net:10000
     *     cluster:
     *       max-redirects: 5
     *       topology-refresh:
     *         enabled: true
     *         period: 60s
     *         adaptive: true
     * }</pre>
     */
    @Getter
    @Setter
    public static class Cluster {

        /**
         * 單一指令允許的最大 {@code MOVED} / {@code ASK} 重導次數，預設 {@code 5}。
         *
         * <p>重新分片（resharding）期間 slot 會在節點間搬移，過低的值會讓指令在
         * 搬移完成前就放棄。
         */
        private int maxRedirects = 5;

        /**
         * 叢集拓撲更新設定。
         */
        @NestedConfigurationProperty
        private TopologyRefresh topologyRefresh = new TopologyRefresh();
    }

    /**
     * 叢集拓撲更新設定。
     *
     * <p>預設為啟用，與 Lettuce 本身的預設（停用）相反。這是刻意的：
     * Azure Managed Redis 的分片節點埠號位於動態範圍且會隨 failover／擴縮變動，
     * 若不更新拓撲，客戶端會持續連向已消失的節點。
     */
    @Getter
    @Setter
    public static class TopologyRefresh {

        /**
         * 是否啟用拓撲更新，預設 {@code true}。
         */
        private boolean enabled = true;

        /**
         * 週期性更新的間隔，預設 {@code 60s}。
         */
        private Duration period = Duration.ofSeconds(60);

        /**
         * 是否啟用適應性更新（收到 {@code MOVED} / {@code ASK} 或連線異常時立即觸發），
         * 預設 {@code true}。
         *
         * <p>週期性更新負責處理緩慢的拓撲漂移，適應性更新負責 failover 當下的即時收斂，
         * 兩者互補。
         */
        private boolean adaptive = true;
    }

    /**
     * Azure 專屬設定的容器。
     */
    @Getter
    @Setter
    public static class Azure {

        /**
         * Microsoft Entra ID token 驗證設定。
         */
        @NestedConfigurationProperty
        private KnoluxRedisEntraIdProperties entraId = new KnoluxRedisEntraIdProperties();
    }
}
