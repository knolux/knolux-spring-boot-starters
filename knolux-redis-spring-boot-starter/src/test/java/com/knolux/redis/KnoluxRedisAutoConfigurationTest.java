package com.knolux.redis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KnoluxRedisAutoConfiguration} 的單元測試。
 *
 * <p>使用 Spring Boot 提供的 {@link ApplicationContextRunner} 測試工具，
 * 在不啟動完整應用程式（無 {@code @SpringBootTest}）的前提下，
 * 驗證自動設定類別在各種設定組合下是否正確建立或拒絕建立 Bean。
 *
 * <p>此測試類別不需要真實的 Redis 連線，因為 {@link ApplicationContextRunner}
 * 只驗證 Bean 是否存在於 ApplicationContext 中，不會實際執行 Redis 操作。
 *
 * <h2>測試涵蓋範圍</h2>
 * <ul>
 *   <li>Standalone 模式（{@code redis://}）下的 Bean 建立</li>
 *   <li>Sentinel 模式（{@code redis-sentinel://}）下的 Bean 建立</li>
 *   <li>各種讀取策略（{@code readFrom}）設定</li>
 *   <li>URL 未設定或為空時的錯誤處理</li>
 *   <li>{@link org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean} 覆寫機制</li>
 * </ul>
 *
 * @see KnoluxRedisAutoConfiguration
 * @see ApplicationContextRunner
 */
class KnoluxRedisAutoConfigurationTest {

    /**
     * 共用的 {@link ApplicationContextRunner}，預先載入 {@link KnoluxRedisAutoConfiguration} 自動設定。
     *
     * <p>每個測試方法可在此基礎上透過 {@code withPropertyValues()} 加入不同設定值，
     * 再呼叫 {@code run()} 驗證 ApplicationContext 的狀態。
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KnoluxRedisAutoConfiguration.class));

    // ─────────────────────────────────────────────
    // 直連模式（redis://）
    // ─────────────────────────────────────────────

    /**
     * 驗證 Standalone 模式下，自動設定能正確建立所有必要的 Bean。
     *
     * <p>預期行為：
     * <ul>
     *   <li>ApplicationContext 中只存在一個 {@link LettuceConnectionFactory}</li>
     *   <li>ApplicationContext 中只存在一個 {@link StringRedisTemplate}</li>
     *   <li>存在名為 {@code redisTemplate} 的 Bean</li>
     *   <li>存在名為 {@code stringRedisTemplate} 的 Bean</li>
     * </ul>
     */
    @Test
    void standalone_shouldCreateAllBeans() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redis://:password@localhost:6379")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class);
                    assertThat(ctx).hasSingleBean(StringRedisTemplate.class);
                    assertThat(ctx).hasBean("redisTemplate");
                    assertThat(ctx).hasBean("stringRedisTemplate");
                });
    }

    /**
     * 驗證 Standalone 模式在 URL 不含密碼時，仍能成功建立連線工廠。
     *
     * <p>適用於開發環境中未設定 Redis 密碼的場景。
     * URL 格式：{@code redis://localhost:6379}（無 userInfo 部分）。
     */
    @Test
    void standalone_withoutPassword_shouldCreateConnectionFactory() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redis://localhost:6379")
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

    /**
     * 驗證 Standalone 模式在 URL 指定資料庫編號時，能成功建立連線工廠。
     *
     * <p>URL 格式：{@code redis://:password@localhost:6379/3}，
     * 其中 {@code /3} 代表使用 Redis DB 3。
     * 連線工廠應正確解析並設定資料庫編號。
     */
    @Test
    void standalone_withDb_shouldCreateConnectionFactory() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redis://:password@localhost:6379/3")
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

    // ─────────────────────────────────────────────
    // Sentinel 模式（redis-sentinel://）
    // ─────────────────────────────────────────────

    /**
     * 驗證 Sentinel 模式下，自動設定能正確建立連線工廠。
     *
     * <p>URL 格式：{@code redis-sentinel://:password@localhost:26379/mymaster}，
     * 其中 {@code /mymaster} 為 Redis Sentinel 管理的 Master 節點名稱。
     */
    @Test
    void sentinel_shouldCreateConnectionFactory() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=redis-sentinel://:password@localhost:26379/mymaster"
                )
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

    /**
     * 驗證 Sentinel 模式在使用自訂 Master 名稱時，能正確建立連線工廠。
     *
     * <p>此測試確認 Master 名稱解析不侷限於特定命名格式，
     * 使用者可自由定義 Master 名稱（例如 {@code custommaster}）。
     */
    @Test
    void sentinel_customMasterName_shouldCreateConnectionFactory() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=redis-sentinel://:password@localhost:26379/custommaster"
                )
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

    // ─────────────────────────────────────────────
    // 讀取策略
    // ─────────────────────────────────────────────

    /**
     * 驗證讀取策略設定為 {@code MASTER} 時，連線工廠能正確建立。
     *
     * <p>在此模式下，所有讀寫操作均路由至 Master 節點，
     * Lettuce 不會啟動 topology refresh 背景執行緒。
     */
    @Test
    void readFrom_master_shouldBeAccepted() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=redis://localhost:6379",
                        "knolux.redis.read-from=MASTER"
                )
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

    /**
     * 驗證讀取策略設定為 {@code REPLICA} 時，連線工廠能正確建立。
     *
     * <p>在此模式下，讀取操作強制路由至 Replica 節點；
     * 若 Replica 不可用，操作將失敗。
     */
    @Test
    void readFrom_replica_shouldBeAccepted() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=redis://localhost:6379",
                        "knolux.redis.read-from=REPLICA"
                )
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

    /**
     * 驗證讀取策略設定為 {@code ANY} 時，連線工廠能正確建立。
     *
     * <p>在此模式下，讀取操作可路由至任意可用節點（Master 或 Replica），
     * 以最大化讀取吞吐量。
     */
    @Test
    void readFrom_any_shouldBeAccepted() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=redis://localhost:6379",
                        "knolux.redis.read-from=ANY"
                )
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

    /**
     * 驗證讀取策略設定為未知值時，自動回退至 {@code REPLICA_PREFERRED} 預設值。
     *
     * <p>此測試確認容錯處理機制：當 {@code knolux.redis.read-from} 設定為無效值時，
     * 系統不應拋出例外，而是靜默地使用 {@code REPLICA_PREFERRED} 作為安全預設值。
     */
    @Test
    void readFrom_unknown_shouldFallbackToReplicaPreferred() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=redis://localhost:6379",
                        "knolux.redis.read-from=UNKNOWN_VALUE"
                )
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

    // ─────────────────────────────────────────────
    // URL 未設定
    // ─────────────────────────────────────────────

    /**
     * 驗證未設定 {@code knolux.redis.url} 時，ApplicationContext 啟動失敗並拋出正確例外。
     *
     * <p>預期 ApplicationContext 的啟動失敗原因中包含 {@code "knolux.redis.url is required"} 訊息，
     * 引導使用者正確設定連線 URL。
     */
    @Test
    void missingUrl_shouldFailWithIllegalArgumentException() {
        contextRunner
                .run(ctx ->
                        assertThat(ctx)
                                .getFailure()
                                .hasMessageContaining("knolux.redis.url is required")
                );
    }

    /**
     * 驗證 {@code knolux.redis.url} 設定為空字串時，ApplicationContext 啟動失敗並拋出正確例外。
     *
     * <p>空字串與未設定的行為一致，均應觸發 {@link IllegalArgumentException}，
     * 防止使用者誤將空字串作為有效設定。
     */
    @Test
    void emptyUrl_shouldFailWithIllegalArgumentException() {
        contextRunner
                .withPropertyValues("knolux.redis.url=")
                .run(ctx ->
                        assertThat(ctx)
                                .getFailure()
                                .hasMessageContaining("knolux.redis.url is required")
                );
    }

    // ─────────────────────────────────────────────
    // ConditionalOnMissingBean
    // ─────────────────────────────────────────────

    /**
     * 驗證使用者自訂的 {@link LettuceConnectionFactory} Bean 不會被自動設定覆寫。
     *
     * <p>此測試確認 {@link org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean}
     * 機制正常運作：當使用者在應用程式中定義了自訂的連線工廠 Bean 時，
     * 自動設定不應建立第二個連線工廠，也不應修改使用者的自訂設定。
     *
     * <p>測試做法：注入一個連接至 {@code custom-host} 的連線工廠，
     * 驗證最終 Bean 的主機名稱確實為 {@code custom-host} 而非自動設定的預設值。
     */
    @Test
    void userDefinedConnectionFactory_shouldNotBeOverridden() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redis://localhost:6379")
                .withBean(
                        "customFactory",
                        LettuceConnectionFactory.class,
                        () -> new LettuceConnectionFactory("custom-host", 6379)
                )
                .run(ctx -> {
                    LettuceConnectionFactory factory =
                            ctx.getBean("customFactory", LettuceConnectionFactory.class);
                    assertThat(factory.getHostName()).isEqualTo("custom-host");
                });
    }
}
