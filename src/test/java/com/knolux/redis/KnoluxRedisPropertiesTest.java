package com.knolux.redis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 測試 KnoluxRedisProperties 屬性綁定。
 * 不需要 Redis 連線，只驗證 application.yml 設定是否正確對應到 Properties 物件。
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

    @Autowired
    KnoluxRedisProperties properties;

    @Test
    void url_shouldBeBound() {
        assertThat(properties.getUrl())
                .isEqualTo("redis://:testpassword@localhost:6379");
    }

    @Test
    void timeoutMs_shouldBeBound() {
        assertThat(properties.getTimeoutMs())
                .isEqualTo(Duration.ofMillis(2000));
    }

    @Test
    void readFrom_shouldBeBound() {
        assertThat(properties.getReadFrom())
                .isEqualTo("MASTER");
    }

    @Test
    void defaultValues_shouldBeCorrect() {
        KnoluxRedisProperties defaults = new KnoluxRedisProperties();
        assertThat(defaults.getReadFrom()).isEqualTo("REPLICA_PREFERRED");
        assertThat(defaults.getTimeoutMs()).isEqualTo(Duration.ofMillis(1000));
        assertThat(defaults.getUrl()).isNull();
    }

    @EnableConfigurationProperties(KnoluxRedisProperties.class)
    static class TestConfig {
    }
}