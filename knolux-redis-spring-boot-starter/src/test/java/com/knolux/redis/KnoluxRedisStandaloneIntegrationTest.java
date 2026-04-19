package com.knolux.redis;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KnoluxRedisAutoConfiguration} Standalone 模式的整合測試。
 *
 * <p>使用 <a href="https://testcontainers.com/">Testcontainers</a> 啟動 Redis 7.4 Docker 容器，
 * 在真實的 Redis 連線環境下驗證各種資料結構操作的正確性。
 *
 * <p>此測試類別涵蓋 Redis 最常用的資料結構與操作場景，
 * 確保 Starter 在實際生產環境中能穩定運作。
 *
 * <h2>測試涵蓋範圍</h2>
 * <ul>
 *   <li><strong>連線驗證</strong>：{@code PING} 指令</li>
 *   <li><strong>String 操作</strong>：SET / GET、TTL 自動過期、DEL 刪除</li>
 *   <li><strong>Hash 操作</strong>：HSET / HGET</li>
 *   <li><strong>List 操作</strong>：RPUSH / LLEN / LPOP</li>
 *   <li><strong>Set 操作</strong>：SADD / SCARD / SISMEMBER</li>
 *   <li><strong>計數器操作</strong>：INCR / DECR</li>
 * </ul>
 *
 * <h2>前提條件</h2>
 * <p>執行此測試需要本機環境安裝 Docker 並處於執行狀態。
 *
 * @see KnoluxRedisSentinelIntegrationTest 模擬 Sentinel 場景的整合測試
 */
@SpringBootTest(
        classes = KnoluxRedisAutoConfiguration.class,
        properties = "spring.data.redis.repositories.enabled=false"
)
class KnoluxRedisStandaloneIntegrationTest {

    /**
     * Testcontainers 管理的 Redis 容器實例。
     *
     * <p>宣告為 {@code static} 使容器在整個測試類別期間共用，
     * 減少 Docker 容器的啟停次數，加快整體測試速度。
     */
    static final RedisContainer REDIS = new RedisContainer(
            RedisContainer.DEFAULT_IMAGE_NAME.withTag("7.4")
    );

    /**
     * 注入 {@link StringRedisTemplate}，為所有測試方法的主要操作介面
     */
    @Autowired
    StringRedisTemplate redis;

    /**
     * 在所有測試方法執行前啟動 Redis 容器。
     */
    @BeforeAll
    static void startContainer() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Docker 不可用，跳過整合測試：" + e.getMessage());
        }
        REDIS.start();
    }

    /**
     * 在所有測試方法執行完畢後停止並移除 Redis 容器，釋放系統資源。
     */
    @AfterAll
    static void stopContainer() {
        REDIS.stop();
    }

    /**
     * 動態注入 Redis 連線屬性至 Spring {@code Environment}。
     *
     * <p>Testcontainers 啟動容器後會將 Redis 的 6379 埠對應至本機隨機可用埠。
     * 此方法在 Spring Context 初始化前注入實際的連線 URL，
     * 使自動設定能連接至測試容器而非正式伺服器。
     *
     * @param registry Spring 動態屬性登錄器
     */
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("knolux.redis.url", () ->
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)
        );
    }

    /**
     * 每個測試方法執行後清除測試資料。
     *
     * <p>使用 {@code KEYS integration:*} 模式搜尋並批次刪除所有本測試類別建立的 key，
     * 防止測試間的資料殘留，確保每個測試方法在乾淨的資料狀態下執行。
     */
    @AfterEach
    void tearDown() {
        var keys = redis.keys("integration:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    /**
     * 驗證 {@code PING} 指令能正確回應 {@code PONG}，確認 Redis 連線建立成功。
     *
     * <p>此為基礎健全性測試，若此測試失敗，代表 Redis 連線本身有問題，
     * 後續所有操作測試亦將無意義。
     */
    @Test
    void ping_shouldSucceed() {
        var factory = redis.getConnectionFactory();
        assertThat(factory).isNotNull();
        try (var conn = factory.getConnection()) {
            assertThat(conn.ping()).isEqualTo("PONG");
        }
    }

    /**
     * 驗證 String 資料結構的基本 SET 與 GET 操作。
     *
     * <p>測試流程：
     * <ol>
     *   <li>SET {@code integration:key1 = value1}</li>
     *   <li>GET {@code integration:key1}，驗證回傳值為 {@code "value1"}</li>
     * </ol>
     */
    @Test
    void setAndGet_shouldWork() {
        redis.opsForValue().set("integration:key1", "value1");
        assertThat(redis.opsForValue().get("integration:key1"))
                .isEqualTo("value1");
    }

    /**
     * 驗證設定 TTL 的 key 在過期後自動被刪除。
     *
     * <p>測試流程：
     * <ol>
     *   <li>SET {@code integration:ttl-key = temp}，TTL 設定為 {@code 500ms}</li>
     *   <li>立即讀取，確認值存在</li>
     *   <li>等待 {@code 600ms}（超過 TTL），再次讀取，確認值已被 Redis 自動清除（回傳 {@code null}）</li>
     * </ol>
     *
     * @throws InterruptedException 若執行緒等待過程中被中斷
     */
    @Test
    void setWithTtl_shouldExpire() throws InterruptedException {
        redis.opsForValue().set("integration:ttl-key", "temp", Duration.ofMillis(500));
        assertThat(redis.opsForValue().get("integration:ttl-key")).isEqualTo("temp");
        Thread.sleep(600);
        assertThat(redis.opsForValue().get("integration:ttl-key")).isNull();
    }

    /**
     * 驗證 DEL 指令能正確刪除指定 key。
     *
     * <p>測試流程：
     * <ol>
     *   <li>SET {@code integration:delete-key = value}</li>
     *   <li>執行 DEL 刪除該 key</li>
     *   <li>GET 該 key，確認回傳 {@code null}</li>
     * </ol>
     */
    @Test
    void delete_shouldRemoveKey() {
        redis.opsForValue().set("integration:delete-key", "value");
        redis.delete("integration:delete-key");
        assertThat(redis.opsForValue().get("integration:delete-key")).isNull();
    }

    /**
     * 驗證 Hash 資料結構的 HSET 與 HGET 操作。
     *
     * <p>測試流程：
     * <ol>
     *   <li>HSET {@code integration:user:1} 的 {@code name} 欄位為 {@code "Alice"}</li>
     *   <li>HSET {@code integration:user:1} 的 {@code email} 欄位為 {@code "alice@example.com"}</li>
     *   <li>HGET 各欄位，驗證值正確</li>
     * </ol>
     *
     * <p>此測試模擬使用 Hash 儲存使用者資訊的常見場景，
     * 相較於使用多個 String key，Hash 結構更節省記憶體。
     */
    @Test
    void hashOperations_shouldWork() {
        redis.opsForHash().put("integration:user:1", "name", "Alice");
        redis.opsForHash().put("integration:user:1", "email", "alice@example.com");

        assertThat(redis.opsForHash().get("integration:user:1", "name"))
                .isEqualTo("Alice");
        assertThat(redis.opsForHash().get("integration:user:1", "email"))
                .isEqualTo("alice@example.com");
    }

    /**
     * 驗證 List 資料結構的 RPUSH、LLEN 與 LPOP 操作。
     *
     * <p>測試流程：
     * <ol>
     *   <li>RPUSH 三個元素至 {@code integration:queue}（從右端推入）</li>
     *   <li>LLEN 確認 List 長度為 3</li>
     *   <li>LPOP 從左端彈出，確認為最早推入的 {@code "task1"}（FIFO 特性）</li>
     * </ol>
     *
     * <p>此測試模擬使用 List 作為訊息佇列（Queue）的常見場景，
     * RPUSH + LPOP 的組合實現先進先出（FIFO）的佇列語義。
     */
    @Test
    void listOperations_shouldWork() {
        redis.opsForList().rightPush("integration:queue", "task1");
        redis.opsForList().rightPush("integration:queue", "task2");
        redis.opsForList().rightPush("integration:queue", "task3");

        assertThat(redis.opsForList().size("integration:queue")).isEqualTo(3);
        assertThat(redis.opsForList().leftPop("integration:queue")).isEqualTo("task1");
    }

    /**
     * 驗證 Set 資料結構的 SADD、SCARD 與 SISMEMBER 操作。
     *
     * <p>測試流程：
     * <ol>
     *   <li>SADD 三個元素至 {@code integration:tags}（自動去重）</li>
     *   <li>SCARD 確認 Set 大小為 3</li>
     *   <li>SISMEMBER 確認 {@code "redis"} 存在於 Set 中</li>
     *   <li>SISMEMBER 確認 {@code "python"} 不存在於 Set 中</li>
     * </ol>
     *
     * <p>此測試模擬使用 Set 儲存標籤（tags）的常見場景，
     * Set 的特性確保相同標籤不會重複儲存。
     */
    @Test
    void setOperations_shouldWork() {
        redis.opsForSet().add("integration:tags", "spring", "redis", "java");

        assertThat(redis.opsForSet().size("integration:tags")).isEqualTo(3);
        assertThat(redis.opsForSet().isMember("integration:tags", "redis")).isTrue();
        assertThat(redis.opsForSet().isMember("integration:tags", "python")).isFalse();
    }

    /**
     * 驗證原子性計數器的 INCR 與 DECR 操作。
     *
     * <p>測試流程：
     * <ol>
     *   <li>SET {@code integration:counter = 10}</li>
     *   <li>INCR 兩次（10 → 11 → 12）</li>
     *   <li>DECR 一次（12 → 11）</li>
     *   <li>GET 確認最終值為 {@code "11"}</li>
     * </ol>
     *
     * <p>Redis 的 INCR / DECR 操作是原子性的，適用於計數器、限流、序號生成等場景。
     * 注意：{@link StringRedisTemplate#opsForValue()} 的 GET 回傳值為字串型別。
     */
    @Test
    void incrDecr_shouldWork() {
        redis.opsForValue().set("integration:counter", "10");
        redis.opsForValue().increment("integration:counter");
        redis.opsForValue().increment("integration:counter");
        redis.opsForValue().decrement("integration:counter");

        assertThat(redis.opsForValue().get("integration:counter")).isEqualTo("11");
    }
}
