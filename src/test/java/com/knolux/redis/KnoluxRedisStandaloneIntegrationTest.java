package com.knolux.redis;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = KnoluxRedisAutoConfiguration.class,
        properties = "spring.data.redis.repositories.enabled=false"
)
class KnoluxRedisStandaloneIntegrationTest {

    static final RedisContainer REDIS = new RedisContainer(
            RedisContainer.DEFAULT_IMAGE_NAME.withTag("7.4")
    );
    @Autowired
    StringRedisTemplate redis;

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

    @AfterEach
    void tearDown() {
        var keys = redis.keys("integration:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    void ping_shouldSucceed() {
        assertThat(redis.getConnectionFactory().getConnection().ping())
                .isEqualTo("PONG");
    }

    @Test
    void setAndGet_shouldWork() {
        redis.opsForValue().set("integration:key1", "value1");
        assertThat(redis.opsForValue().get("integration:key1"))
                .isEqualTo("value1");
    }

    @Test
    void setWithTtl_shouldExpire() throws InterruptedException {
        redis.opsForValue().set("integration:ttl-key", "temp", Duration.ofMillis(500));
        assertThat(redis.opsForValue().get("integration:ttl-key")).isEqualTo("temp");
        Thread.sleep(600);
        assertThat(redis.opsForValue().get("integration:ttl-key")).isNull();
    }

    @Test
    void delete_shouldRemoveKey() {
        redis.opsForValue().set("integration:delete-key", "value");
        redis.delete("integration:delete-key");
        assertThat(redis.opsForValue().get("integration:delete-key")).isNull();
    }

    @Test
    void hashOperations_shouldWork() {
        redis.opsForHash().put("integration:user:1", "name", "Alice");
        redis.opsForHash().put("integration:user:1", "email", "alice@example.com");

        assertThat(redis.opsForHash().get("integration:user:1", "name"))
                .isEqualTo("Alice");
        assertThat(redis.opsForHash().get("integration:user:1", "email"))
                .isEqualTo("alice@example.com");
    }

    @Test
    void listOperations_shouldWork() {
        redis.opsForList().rightPush("integration:queue", "task1");
        redis.opsForList().rightPush("integration:queue", "task2");
        redis.opsForList().rightPush("integration:queue", "task3");

        assertThat(redis.opsForList().size("integration:queue")).isEqualTo(3);
        assertThat(redis.opsForList().leftPop("integration:queue")).isEqualTo("task1");
    }

    @Test
    void setOperations_shouldWork() {
        redis.opsForSet().add("integration:tags", "spring", "redis", "java");

        assertThat(redis.opsForSet().size("integration:tags")).isEqualTo(3);
        assertThat(redis.opsForSet().isMember("integration:tags", "redis")).isTrue();
        assertThat(redis.opsForSet().isMember("integration:tags", "python")).isFalse();
    }

    @Test
    void incrDecr_shouldWork() {
        redis.opsForValue().set("integration:counter", "10");
        redis.opsForValue().increment("integration:counter");
        redis.opsForValue().increment("integration:counter");
        redis.opsForValue().decrement("integration:counter");

        assertThat(redis.opsForValue().get("integration:counter")).isEqualTo("11");
    }
}