package com.knolux.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnoluxRedisHealthIndicatorTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisConnectionFactory connectionFactory;

    @Mock
    private RedisConnection connection;

    private KnoluxRedisHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        // ✅ 只放所有測試都需要的共用 stubbing
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        healthIndicator = new KnoluxRedisHealthIndicator(redisTemplate);
    }

    @Test
    void health_whenRedisIsUp_shouldReturnStatusUp() {
        // ✅ 此測試才需要 getConnection()，放在這裡
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        Health health = healthIndicator.health();

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("ping", "PONG");
    }

    @Test
    void health_whenRedisThrowsException_shouldReturnStatusDown() {
        // ✅ 此測試才需要 getConnection()，放在這裡
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenThrow(new RuntimeException("Connection refused"));

        Health health = healthIndicator.health();

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    void health_whenConnectionFactoryIsNull_shouldReturnStatusDown() {
        // ✅ 此測試覆寫 connectionFactory 為 null，不需要 getConnection()
        when(redisTemplate.getConnectionFactory()).thenReturn(null);

        Health health = healthIndicator.health();

        assert health != null;
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}