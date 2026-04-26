package com.knolux.redis;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KnoluxRedisHealthIndicator} 的整合測試。
 *
 * <p>使用 <a href="https://testcontainers.com/">Testcontainers</a> 在測試期間啟動真實的
 * Redis Docker 容器（版本 7.4），驗證健康指標在實際 Redis 連線環境下的行為。
 *
 * <p>相較於 {@link KnoluxRedisHealthIndicatorTest}（使用 Mock），
 * 此整合測試確認健康指標與 Redis 的端到端連線正常，
 * 包括實際的 TCP 連線建立、{@code PING} 指令的傳送與 {@code PONG} 的接收。
 *
 * <h2>測試環境</h2>
 * <ul>
 *   <li>Redis 版本：7.4（透過 Docker 容器）</li>
 *   <li>連線模式：Standalone（無密碼）</li>
 *   <li>主機與埠號：由 Testcontainers 動態分配，透過 {@link DynamicPropertySource} 注入</li>
 * </ul>
 *
 * <h2>前提條件</h2>
 * <p>執行此測試需要本機環境安裝 Docker 並處於執行狀態。Docker 不可用時自動跳過。
 *
 * @see KnoluxRedisHealthIndicator
 * @see KnoluxRedisHealthIndicatorTest 單元測試（使用 Mock，無需 Docker）
 */
@SpringBootTest(
        classes = KnoluxRedisAutoConfiguration.class,
        properties = "spring.data.redis.repositories.enabled=false"
)
class KnoluxRedisHealthIndicatorIntegrationTest {

    /**
     * Testcontainers 管理的 Redis 容器實例。
     *
     * <p>宣告為 {@code static} 使容器在整個測試類別期間共用，
     * 避免每個測試方法重複啟動/停止容器，大幅降低測試執行時間。
     * 使用 Redis 官方映像 {@code redis:7.4}。
     */
    static final RedisContainer REDIS = new RedisContainer(
            RedisContainer.DEFAULT_IMAGE_NAME.withTag("7.4")
    );

    /**
     * 注入由 {@link KnoluxRedisAutoConfiguration} 建立的健康指標 Bean，為測試對象
     */
    @Autowired
    KnoluxRedisHealthIndicator healthIndicator;

    /**
     * 在所有測試方法執行前啟動 Redis 容器。
     *
     * <p>Testcontainers 會自動尋找可用的本機 Docker 環境並啟動容器，
     * 同時綁定容器內的 6379 埠至本機的隨機可用埠。
     * Docker 不可用時跳過整個測試類別。
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
     * <p>由於 Testcontainers 動態分配容器的對外埠號，
     * 必須在容器啟動後才能得知實際的連線位址。
     * {@link DynamicPropertySource} 機制讓我們在 Spring Context 初始化前注入正確的 URL，
     * 確保 {@link KnoluxRedisAutoConfiguration} 能連接至測試容器。
     *
     * @param registry Spring 動態屬性登錄器，用於在測試期間覆寫設定屬性
     */
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("knolux.redis.url", () ->
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)
        );
    }

    /**
     * 驗證連接至真實 Redis 時，健康檢查回傳 {@code UP} 狀態。
     *
     * <p>此測試確認端到端的健康檢查流程：
     * {@link KnoluxRedisHealthIndicator} → {@link org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory}
     * → Redis 容器，並成功接收 {@code PONG} 回應。
     *
     * <p>預期結果：
     * <ul>
     *   <li>健康狀態為 {@link Status#UP}</li>
     *   <li>詳細資訊包含 {@code ping: PONG}</li>
     * </ul>
     */
    @Test
    void health_withRealRedis_shouldReturnUp() {
        Health health = healthIndicator.health();

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("ping", "PONG");
    }
}
