package com.knolux.redis;

import com.knolux.redis.KnoluxRedisAutoConfiguration;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KnoluxRedisAutoConfiguration} 的整合測試（模擬 Sentinel 場景）。
 *
 * <p>注意：此測試類別使用 Testcontainers 啟動的<strong>單節點 Redis 容器</strong>
 * 來模擬 Sentinel 相關情境的操作行為（基本讀寫驗證），
 * 並非在真實的 Redis Sentinel 叢集環境下執行。
 *
 * <p>透過 Testcontainers 啟動 Redis 7.4 容器，驗證連線工廠建立正確、
 * 基本 CRUD 操作可正常執行，以及不同 key 前綴的資料隔離性。
 *
 * <p>所有測試方法執行後，會透過 {@link #tearDown()} 清除以 {@code sentinel-it:} 為前綴的測試資料，
 * 確保測試間不相互干擾。
 *
 * <h2>測試涵蓋範圍</h2>
 * <ul>
 *   <li>連線工廠是否成功建立</li>
 *   <li>{@code PING} 指令是否正常回應</li>
 *   <li>字串型 key/value 的讀寫操作</li>
 *   <li>批次寫入與讀取的正確性</li>
 *   <li>不同服務使用 key 前綴進行資料隔離</li>
 * </ul>
 *
 * <h2>前提條件</h2>
 * <p>執行此測試需要本機環境安裝 Docker 並處於執行狀態。
 *
 * @see KnoluxRedisStandaloneIntegrationTest 純 Standalone 模式的整合測試
 */
@SpringBootTest(
        classes = KnoluxRedisAutoConfiguration.class,
        properties = "spring.data.redis.repositories.enabled=false"
)
class KnoluxRedisSentinelIntegrationTest {

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
     * 注入 {@link StringRedisTemplate}，用於執行測試中的 Redis 讀寫操作
     */
    @Autowired
    StringRedisTemplate redis;

    /**
     * 注入 {@link LettuceConnectionFactory}，用於驗證連線工廠的建立狀態
     */
    @Autowired
    LettuceConnectionFactory connectionFactory;

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
     * <p>容器啟動後，Testcontainers 會將容器內的 6379 埠對應至本機隨機埠。
     * 此方法在 Spring Context 初始化前將實際的連線 URL 注入至屬性設定，
     * 使 {@link KnoluxRedisAutoConfiguration} 能正確連接至測試容器。
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
     * <p>使用 {@code KEYS sentinel-it:*} 模式搜尋並刪除所有本測試類別產生的 key，
     * 確保測試間的資料隔離，避免前一個測試殘留的資料影響後續測試。
     *
     * <p>注意：{@code KEYS} 指令在生產環境中應謹慎使用（可能阻塞 Redis），
     * 此處僅用於測試環境的資料清理。
     */
    @AfterEach
    void tearDown() {
        var keys = redis.keys("sentinel-it:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    /**
     * 驗證 {@link LettuceConnectionFactory} 已正確建立（非 {@code null}）。
     *
     * <p>此為基礎健全性測試，確認自動設定在整合環境下能成功初始化連線工廠。
     */
    @Test
    void connectionFactory_shouldBeCreated() {
        assertThat(connectionFactory).isNotNull();
    }

    /**
     * 驗證透過 {@link LettuceConnectionFactory} 向 Redis 發送 {@code PING} 指令能收到 {@code PONG} 回應。
     *
     * <p>確認底層 TCP 連線建立成功，Redis 伺服器可正常接收並回應指令。
     */
    @Test
    void ping_shouldSucceed() {
        try (var conn = connectionFactory.getConnection()) {
            assertThat(conn.ping()).isEqualTo("PONG");
        }
    }

    /**
     * 驗證字串型 key/value 的寫入與讀取操作正確運作，並支援 TTL 設定。
     *
     * <p>測試流程：
     * <ol>
     *   <li>寫入 {@code sentinel-it:hello = world}，TTL 設定為 1 分鐘</li>
     *   <li>讀取 {@code sentinel-it:hello}，驗證值為 {@code "world"}</li>
     * </ol>
     */
    @Test
    void writeAndRead_shouldWork() {
        redis.opsForValue().set("sentinel-it:hello", "world", Duration.ofMinutes(1));
        assertThat(redis.opsForValue().get("sentinel-it:hello")).isEqualTo("world");
    }

    /**
     * 驗證批次寫入（5 筆）後，每筆資料均能正確讀取。
     *
     * <p>測試流程：
     * <ol>
     *   <li>循環寫入 {@code sentinel-it:key:1} ~ {@code sentinel-it:key:5}</li>
     *   <li>循環讀取並驗證每個 key 對應的 value</li>
     * </ol>
     *
     * <p>此測試確認在多次連續讀寫操作下，連線的穩定性與資料完整性。
     */
    @Test
    void multipleWriteRead_shouldWork() {
        for (int i = 1; i <= 5; i++) {
            redis.opsForValue().set("sentinel-it:key:" + i, "value-" + i);
        }
        for (int i = 1; i <= 5; i++) {
            assertThat(redis.opsForValue().get("sentinel-it:key:" + i))
                    .isEqualTo("value-" + i);
        }
    }

    /**
     * 驗證不同服務使用不同 key 前綴時，資料能正確隔離。
     *
     * <p>測試流程：
     * <ol>
     *   <li>Service A 寫入 {@code sentinel-it:service-a:data = from-service-a}</li>
     *   <li>Service B 寫入 {@code sentinel-it:service-b:data = from-service-b}</li>
     *   <li>分別讀取並驗證兩個服務的資料互不干擾</li>
     * </ol>
     *
     * <p>此測試模擬多個服務共用同一 Redis 實例時，透過命名空間前綴
     * （namespace prefix）實現資料邏輯隔離的常見模式。
     */
    @Test
    void keyPrefix_shouldIsolateData() {
        redis.opsForValue().set("sentinel-it:service-a:data", "from-service-a");
        redis.opsForValue().set("sentinel-it:service-b:data", "from-service-b");

        assertThat(redis.opsForValue().get("sentinel-it:service-a:data"))
                .isEqualTo("from-service-a");
        assertThat(redis.opsForValue().get("sentinel-it:service-b:data"))
                .isEqualTo("from-service-b");
    }
}
