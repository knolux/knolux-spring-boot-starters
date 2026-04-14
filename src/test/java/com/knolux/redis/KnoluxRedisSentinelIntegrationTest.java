package com.knolux.redis;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = KnoluxRedisAutoConfiguration.class,
        properties = "spring.data.redis.repositories.enabled=false"
)
class KnoluxRedisSentinelIntegrationTest {

    static final RedisContainer REDIS = new RedisContainer(
            RedisContainer.DEFAULT_IMAGE_NAME.withTag("7.4")
    );

    @BeforeAll
    static void startContainer() {
        REDIS.start();
    }

    @AfterAll
    static void stopContainer() {
        REDIS.stop();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("knolux.redis.url", () ->
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)
        );
    }

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    LettuceConnectionFactory connectionFactory;

    @AfterEach
    void tearDown() {
        var keys = redis.keys("sentinel-it:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void connectionFactory_shouldBeCreated() {
        assertThat(connectionFactory).isNotNull();
    }

    @Test
    void ping_shouldSucceed() {
        assertThat(connectionFactory.getConnection().ping()).isEqualTo("PONG");
    }

    @Test
    void writeAndRead_shouldWork() {
        redis.opsForValue().set("sentinel-it:hello", "world", Duration.ofMinutes(1));
        assertThat(redis.opsForValue().get("sentinel-it:hello")).isEqualTo("world");
    }

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