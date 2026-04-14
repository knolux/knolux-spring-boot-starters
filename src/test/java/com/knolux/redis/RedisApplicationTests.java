package com.knolux.redis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = KnoluxRedisPropertiesTest.TestConfig.class,
        properties = {
                "knolux.redis.url=redis://:testpassword@localhost:6379",
                "knolux.redis.timeout-ms=2000ms",
                "knolux.redis.read-from=MASTER"
        }
)
class KnoluxRedisPropertiesTest {

    @EnableConfigurationProperties(KnoluxRedisProperties.class)
    static class TestConfig {}

    @Autowired
    KnoluxRedisProperties properties;

    @Test
    void urlIsBound() {
        assertThat(properties.getUrl())
                .isEqualTo("redis://:testpassword@localhost:6379");
    }

    @Test
    void timeoutIsBound() {
        assertThat(properties.getTimeoutMs())
                .isEqualTo(Duration.ofMillis(2000));
    }

    @Test
    void readFromIsBound() {
        assertThat(properties.getReadFrom())
                .isEqualTo("MASTER");
    }

    @Test
    void defaultReadFrom() {
        assertThat(new KnoluxRedisProperties().getReadFrom())
                .isEqualTo("REPLICA_PREFERRED");
    }
}