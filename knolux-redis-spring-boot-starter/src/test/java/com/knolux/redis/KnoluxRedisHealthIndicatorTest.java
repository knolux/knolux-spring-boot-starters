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

/**
 * {@link KnoluxRedisHealthIndicator} 的單元測試。
 *
 * <p>使用 Mockito 模擬 Redis 連線相關物件，在不需要真實 Redis 伺服器的情況下，
 * 驗證 {@link KnoluxRedisHealthIndicator#health()} 在各種狀況下的回傳結果。
 *
 * <p>測試設計採用 Mock 隔離策略：所有外部依賴（{@link StringRedisTemplate}、
 * {@link RedisConnectionFactory}、{@link RedisConnection}）均以 Mock 物件替代，
 * 確保測試執行速度快、結果穩定，不受外部環境影響。
 *
 * <h2>測試涵蓋範圍</h2>
 * <ul>
 *   <li>Redis 正常回應 {@code PONG} → 健康狀態 {@code UP}</li>
 *   <li>Redis 連線拋出例外 → 健康狀態 {@code DOWN}（附錯誤訊息）</li>
 *   <li>連線工廠為 {@code null} → 健康狀態 {@code DOWN}（附固定錯誤訊息）</li>
 * </ul>
 *
 * @see KnoluxRedisHealthIndicator
 * @see KnoluxRedisHealthIndicatorIntegrationTest 整合測試（需真實 Redis）
 */
@ExtendWith(MockitoExtension.class)
class KnoluxRedisHealthIndicatorTest {

    /**
     * Mock 的 {@link StringRedisTemplate}，用於模擬 Redis 操作模板
     */
    @Mock
    private StringRedisTemplate redisTemplate;

    /**
     * Mock 的 {@link RedisConnectionFactory}，用於模擬連線工廠
     */
    @Mock
    private RedisConnectionFactory connectionFactory;

    /**
     * Mock 的 {@link RedisConnection}，用於模擬單次 Redis 連線
     */
    @Mock
    private RedisConnection connection;

    /**
     * 待測試的 {@link KnoluxRedisHealthIndicator} 實例
     */
    private KnoluxRedisHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        healthIndicator = new KnoluxRedisHealthIndicator(redisTemplate);
    }

    /**
     * 驗證 Redis 正常運作時，健康檢查回傳 {@code UP} 狀態。
     *
     * <p>模擬情境：
     * <ol>
     *   <li>{@link StringRedisTemplate#getConnectionFactory()} 回傳 Mock 連線工廠</li>
     *   <li>{@link RedisConnectionFactory#getConnection()} 回傳 Mock 連線</li>
     *   <li>{@link RedisConnection#ping()} 回傳 {@code "PONG"}</li>
     * </ol>
     *
     * <p>預期結果：
     * <ul>
     *   <li>健康狀態為 {@link Status#UP}</li>
     *   <li>詳細資訊包含 {@code ping: PONG}</li>
     * </ul>
     */
    @Test
    void health_whenRedisIsUp_shouldReturnStatusUp() {
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        Health health = healthIndicator.health();

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("ping", "PONG");
    }

    /**
     * 驗證 Redis 連線拋出例外時，健康檢查回傳 {@code DOWN} 狀態並附上錯誤訊息。
     *
     * <p>模擬情境：{@link RedisConnection#ping()} 拋出 {@link RuntimeException}（模擬連線拒絕等網路錯誤）。
     *
     * <p>預期結果：
     * <ul>
     *   <li>健康狀態為 {@link Status#DOWN}</li>
     *   <li>詳細資訊包含 {@code error: Connection refused}（例外訊息）</li>
     * </ul>
     *
     * <p>此測試確認健康檢查的容錯機制：例外不會向上傳播，而是被捕捉並轉換為 {@code DOWN} 狀態。
     */
    @Test
    void health_whenRedisThrowsException_shouldReturnStatusDown() {
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenThrow(new RuntimeException("Connection refused"));

        Health health = healthIndicator.health();

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "Connection refused");
    }

    /**
     * 驗證連線工廠為 {@code null} 時，健康檢查回傳 {@code DOWN} 狀態並附上固定錯誤訊息。
     *
     * <p>模擬情境：{@link StringRedisTemplate#getConnectionFactory()} 回傳 {@code null}，
     * 模擬連線工廠尚未正確初始化的異常狀態。
     *
     * <p>預期結果：
     * <ul>
     *   <li>健康狀態為 {@link Status#DOWN}</li>
     *   <li>詳細資訊包含 {@code error: Connection factory is null}</li>
     * </ul>
     *
     * <p>此測試確認防禦性程式設計：明確處理 {@code null} 連線工廠，
     * 避免 {@link NullPointerException} 被誤判為未知錯誤。
     */
    @Test
    void health_whenConnectionFactoryIsNull_shouldReturnStatusDown() {
        when(redisTemplate.getConnectionFactory()).thenReturn(null);

        Health health = healthIndicator.health();

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "Connection factory is null");
    }
}
