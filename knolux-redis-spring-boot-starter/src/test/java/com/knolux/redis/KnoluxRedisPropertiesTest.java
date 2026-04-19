package com.knolux.redis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KnoluxRedisProperties} 的設定屬性綁定測試。
 *
 * <p>驗證 Spring Boot 的 {@link EnableConfigurationProperties} 機制能正確將
 * {@code application.yml} / {@code application.properties} 中的設定值綁定至
 * {@link KnoluxRedisProperties} 物件的對應欄位。
 *
 * <p>此測試類別刻意使用最小化的 Spring Context（{@link TestConfig}），
 * 僅啟用 {@link KnoluxRedisProperties} 的設定綁定，
 * 不載入 {@link KnoluxRedisAutoConfiguration}，因此<strong>不需要真實的 Redis 連線</strong>。
 *
 * <p>測試屬性透過 {@link SpringBootTest#properties()} 直接提供，不依賴外部設定檔，
 * 確保測試環境的獨立性與可重複性。
 *
 * <h2>測試涵蓋範圍</h2>
 * <ul>
 *   <li>{@code knolux.redis.url} 屬性是否正確綁定</li>
 *   <li>{@code knolux.redis.timeout-ms} Duration 屬性是否正確解析</li>
 *   <li>{@code knolux.redis.read-from} 屬性是否正確綁定</li>
 *   <li>{@link KnoluxRedisProperties} 各欄位的預設值是否符合預期</li>
 * </ul>
 *
 * @see KnoluxRedisProperties
 */
@SpringBootTest(
        classes = KnoluxRedisPropertiesTest.TestConfig.class,
        properties = {
                "knolux.redis.url=redis://:testpassword@localhost:6379",
                "knolux.redis.timeout-ms=2000ms",
                "knolux.redis.read-from=MASTER"
        }
)
class KnoluxRedisPropertiesTest {

    /**
     * 由 Spring 自動注入，驗證設定綁定結果的 {@link KnoluxRedisProperties} 實例
     */
    @Autowired
    KnoluxRedisProperties properties;

    /**
     * 驗證 {@code knolux.redis.url} 屬性能正確綁定至 {@link KnoluxRedisProperties#getUrl()}。
     *
     * <p>測試值：{@code redis://:testpassword@localhost:6379}
     */
    @Test
    void url_shouldBeBound() {
        assertThat(properties.getUrl())
                .isEqualTo("redis://:testpassword@localhost:6379");
    }

    /**
     * 驗證 {@code knolux.redis.timeout-ms} 屬性能正確解析 Duration 並綁定。
     *
     * <p>Spring Boot 的 Duration 轉換器將 {@code 2000ms} 解析為 {@link Duration#ofMillis(long) Duration.ofMillis(2000)}。
     * 測試確認解析結果等同於 {@code 2000} 毫秒。
     */
    @Test
    void timeoutMs_shouldBeBound() {
        assertThat(properties.getTimeoutMs())
                .isEqualTo(Duration.ofMillis(2000));
    }

    /**
     * 驗證 {@code knolux.redis.read-from} 屬性能正確綁定至 {@link KnoluxRedisProperties#getReadFrom()}。
     *
     * <p>測試值：{@code MASTER}（覆寫預設值 {@code REPLICA_PREFERRED}）。
     */
    @Test
    void readFrom_shouldBeBound() {
        assertThat(properties.getReadFrom())
                .isEqualTo("MASTER");
    }

    /**
     * 驗證 {@link KnoluxRedisProperties} 在未提供任何外部設定時，各欄位使用正確的預設值。
     *
     * <p>建立一個全新的 {@link KnoluxRedisProperties} 實例（不依賴 Spring 注入），
     * 直接驗證預設值：
     * <ul>
     *   <li>{@code readFrom} 預設為 {@code "REPLICA_PREFERRED"}</li>
     *   <li>{@code timeoutMs} 預設為 {@code 1000ms}（1 秒）</li>
     *   <li>{@code url} 預設為 {@code null}（必填欄位）</li>
     * </ul>
     */
    @Test
    void defaultValues_shouldBeCorrect() {
        KnoluxRedisProperties defaults = new KnoluxRedisProperties();
        assertThat(defaults.getReadFrom()).isEqualTo("REPLICA_PREFERRED");
        assertThat(defaults.getTimeoutMs()).isEqualTo(Duration.ofMillis(1000));
        assertThat(defaults.getUrl()).isNull();
    }

    /**
     * 最小化測試 Spring Context 設定。
     *
     * <p>僅啟用 {@link KnoluxRedisProperties} 的設定屬性綁定，
     * 不引入任何其他 Bean 定義，確保測試執行輕量且快速。
     * 不載入 {@link KnoluxRedisAutoConfiguration}，因此不會觸發 Redis 連線建立。
     */
    @EnableConfigurationProperties(KnoluxRedisProperties.class)
    static class TestConfig {
    }
}
