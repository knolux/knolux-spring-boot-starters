package com.knolux.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code knolux.redis.*} 設定屬性。
 *
 * <p>使用範例（{@code application.yml}）：
 * <pre>{@code
 * knolux:
 *   redis:
 *     url: redis://:password@localhost:6379
 *     timeout-ms: 1000ms
 *     read-from: REPLICA_PREFERRED
 * }</pre>
 */
@ConfigurationProperties(prefix = "knolux.redis")
public class KnoluxRedisProperties {

    /**
     * Redis 連線 URL。
     * 支援 redis:// 與 redis-sentinel:// 兩種格式。
     */
    private String url;

    /**
     * 連線逾時（毫秒），預設 1000ms。
     */
    private Duration timeoutMs = Duration.ofMillis(1000);

    /**
     * 讀取策略：
     * <ul>
     *   <li>REPLICA_PREFERRED（預設）：優先 Replica，降低 Master 壓力</li>
     *   <li>MASTER：所有讀寫走 Master</li>
     *   <li>REPLICA：強制走 Replica</li>
     *   <li>ANY：任意節點</li>
     * </ul>
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
