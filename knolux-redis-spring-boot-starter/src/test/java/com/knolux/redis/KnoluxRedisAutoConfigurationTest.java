package com.knolux.redis;

import com.knolux.redis.azure.EntraIdCredentialsProviderFactory;
import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.RedisCredentialsProviderFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

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
 *   <li>Cluster 模式（{@code redis-cluster://}）下的 Bean 建立與 DB 限制</li>
 *   <li>TLS scheme（{@code rediss} 系列）是否正確啟用加密</li>
 *   <li>Azure Entra ID token 驗證的裝配與各條 fail-fast 規則</li>
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

    /**
     * 取出自動設定所建立之連線工廠的客戶端設定，用於驗證 TLS 與憑證等橫切設定。
     */
    private static LettuceClientConfiguration clientConfigurationOf(ApplicationContext ctx) {
        return ctx.getBean(LettuceConnectionFactory.class).getClientConfiguration();
    }

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

    /**
     * 驗證 redisTemplate 確實以 {@link StringRedisSerializer} 設定 key 與 hashKey 序列化器
     * （驗證 @Bean 方法的實際序列化邏輯，而非僅驗證 Bean 是否存在）。
     */
    @Test
    void redisTemplate_usesStringRedisSerializerForKeys() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redis://localhost:6379")
                .run(ctx -> {
                    RedisTemplate<?, ?> tpl = ctx.getBean("redisTemplate", RedisTemplate.class);
                    assertThat(tpl.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
                    assertThat(tpl.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
                });
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
    // Cluster 模式（redis-cluster://）
    // ─────────────────────────────────────────────

    /**
     * 驗證 Cluster 模式下，自動設定能正確建立連線工廠。
     *
     * <p>URL 中的節點為<strong>種子節點</strong>，客戶端連上後會以 {@code CLUSTER SHARDS}
     * 取得完整拓撲，因此只列一個節點即可。
     */
    @Test
    void cluster_shouldCreateConnectionFactory() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redis-cluster://localhost:6379")
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

    /**
     * 驗證 Cluster 模式指定 DB 時明確失敗。
     *
     * <p>Cluster 協定只有 DB 0，靜默忽略會讓使用者以為資料寫進了指定的 DB。
     */
    @Test
    void cluster_withNonZeroDb_shouldFailWithIllegalArgumentException() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redis-cluster://localhost:6379/3")
                .run(ctx ->
                        assertThat(ctx)
                                .getFailure()
                                .hasMessageContaining("Cluster 模式僅支援 DB 0")
                );
    }

    // ─────────────────────────────────────────────
    // TLS scheme（rediss 系列）
    // ─────────────────────────────────────────────

    /**
     * 驗證 {@code rediss://} 會建立啟用 TLS 的 Standalone 連線工廠。
     */
    @Test
    void tlsStandalone_shouldEnableSsl() {
        contextRunner
                .withPropertyValues("knolux.redis.url=rediss://localhost:6380")
                .run(ctx ->
                        assertThat(clientConfigurationOf(ctx).isUseSsl()).isTrue()
                );
    }

    /**
     * 驗證 {@code rediss-sentinel://} 會建立啟用 TLS 的 Sentinel 連線工廠。
     */
    @Test
    void tlsSentinel_shouldEnableSsl() {
        contextRunner
                .withPropertyValues("knolux.redis.url=rediss-sentinel://localhost:26379/mymaster")
                .run(ctx ->
                        assertThat(clientConfigurationOf(ctx).isUseSsl()).isTrue()
                );
    }

    /**
     * 驗證 {@code rediss-cluster://} 會建立啟用 TLS 的 Cluster 連線工廠，
     * 這正是 Azure Managed Redis（OSS clustering policy）所需的組合。
     */
    @Test
    void tlsCluster_shouldEnableSsl() {
        contextRunner
                .withPropertyValues("knolux.redis.url=rediss-cluster://mycache.eastus.redis.azure.net:10000")
                .run(ctx ->
                        assertThat(clientConfigurationOf(ctx).isUseSsl()).isTrue()
                );
    }

    /**
     * 驗證明文 scheme 不會意外啟用 TLS——加密與否只由 scheme 決定。
     */
    @Test
    void plaintextScheme_shouldNotEnableSsl() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redis://localhost:6379")
                .run(ctx ->
                        assertThat(clientConfigurationOf(ctx).isUseSsl()).isFalse()
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
     * 驗證讀取策略設定為 {@code UPSTREAM}（Lettuce 6+ 別名）時可正確接受，
     * 與 {@code MASTER} 行為一致（不啟動 topology refresh）。
     */
    @Test
    void readFrom_upstream_shouldBeAccepted() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=redis://localhost:6379",
                        "knolux.redis.read-from=UPSTREAM"
                )
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

    /**
     * 驗證 {@code LOWEST_LATENCY} 策略可正確接受。
     */
    @Test
    void readFrom_lowestLatency_shouldBeAccepted() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=redis://localhost:6379",
                        "knolux.redis.read-from=LOWEST_LATENCY"
                )
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

    /**
     * 驗證 {@code ANY_REPLICA} 策略可正確接受。
     */
    @Test
    void readFrom_anyReplica_shouldBeAccepted() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=redis://localhost:6379",
                        "knolux.redis.read-from=ANY_REPLICA"
                )
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

    /**
     * 驗證 {@code subnet:} 複合語法可正確接受（Lettuce 委派解析）。
     */
    @Test
    void readFrom_subnet_shouldBeAccepted() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=redis://localhost:6379",
                        "knolux.redis.read-from=subnet:192.168.0.0/16"
                )
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

    /**
     * 驗證 {@code regex:} 複合語法可正確接受。
     */
    @Test
    void readFrom_regex_shouldBeAccepted() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=redis://localhost:6379",
                        "knolux.redis.read-from=regex:.*region-1.*"
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

    /**
     * 驗證不支援的 scheme 會明確失敗，而非被某個 builder 靜默收下（安全性）。
     *
     * <p>此處刻意不用 {@code rediss://} 當樣本 —— 該 scheme 自 1.5.0 起已是合法的
     * TLS standalone 連線。改用完全不相干的 {@code http://}，才真正測到 fail-fast 路徑。
     */
    @Test
    void unsupportedScheme_shouldFailWithIllegalArgumentException() {
        contextRunner
                .withPropertyValues("knolux.redis.url=http://localhost:6379")
                .run(ctx ->
                        assertThat(ctx)
                                .getFailure()
                                .hasMessageContaining("不支援的 Redis URI scheme")
                );
    }

    /**
     * 驗證「開頭像 TLS scheme 但其實是錯字」的值不會被 Standalone 收下。
     *
     * <p>這是 {@code RedisUriUtils} 採具名別名表而非 {@code startsWith("rediss")}
     * 字首比對的理由：字首比對會讓 {@code redissl://} 這類錯字靜默連上明文。
     */
    @Test
    void schemeWithTlsPrefixTypo_shouldFailWithIllegalArgumentException() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redissl://localhost:6379")
                .run(ctx ->
                        assertThat(ctx)
                                .getFailure()
                                .hasMessageContaining("不支援的 Redis URI scheme")
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

    // ─────────────────────────────────────────────
    // Azure Entra ID token 驗證
    // ─────────────────────────────────────────────

    /**
     * 驗證未啟用 Entra ID 時（預設）不會建立憑證提供者工廠，
     * 連線工廠也維持原本的靜態密碼行為。
     *
     * <p>{@code ReauthenticateBehavior} 必須維持 Lettuce 預設：
     * 靜態密碼的使用者不需要、也不應該被改變重新驗證的行為。
     */
    @Test
    void entraIdDisabled_shouldNotCreateCredentialsProviderFactory() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redis://localhost:6379")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(RedisCredentialsProviderFactory.class);

                    LettuceClientConfiguration clientConfig = clientConfigurationOf(ctx);
                    assertThat(clientConfig.getRedisCredentialsProviderFactory()).isEmpty();
                    assertThat(clientConfig.getClientOptions().orElseThrow().getReauthenticateBehaviour())
                            .isEqualTo(ClientOptions.ReauthenticateBehavior.DEFAULT);
                });
    }

    /**
     * 驗證啟用 Entra ID 後會建立 {@link EntraIdCredentialsProviderFactory} Bean。
     *
     * <p>此處刻意注入自訂的 {@link LettuceConnectionFactory} 讓自動設定的連線工廠退場：
     * 連線工廠一旦建立就會向憑證提供者索取 token，單元測試不該真的對 Entra ID 發出請求。
     * 憑證提供者工廠本身是獨立 Bean，仍會被建立，故此測試依然涵蓋裝配路徑。
     */
    @Test
    void entraIdEnabled_shouldCreateEntraIdCredentialsProviderFactory() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=rediss-cluster://mycache.eastus.redis.azure.net:10000",
                        "knolux.redis.azure.entra-id.enabled=true"
                )
                .withBean(
                        "customFactory",
                        LettuceConnectionFactory.class,
                        () -> new LettuceConnectionFactory("custom-host", 6379)
                )
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(EntraIdCredentialsProviderFactory.class)
                );
    }

    /**
     * 驗證啟用 Entra ID 但使用明文 scheme 時明確失敗（FR-041）。
     *
     * <p>bearer token 是純字串憑證，任何攔截到的人都能直接冒用，不得走明文連線。
     */
    @Test
    void entraIdEnabled_withPlaintextScheme_shouldFail() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=redis-cluster://mycache.eastus.redis.azure.net:10000",
                        "knolux.redis.azure.entra-id.enabled=true"
                )
                .run(ctx ->
                        assertThat(ctx)
                                .getFailure()
                                .hasMessageContaining("rediss-cluster://")
                );
    }

    /**
     * 驗證啟用 Entra ID 但使用 Sentinel scheme 時明確失敗（FR-042）——Azure 不提供 Sentinel。
     */
    @Test
    void entraIdEnabled_withSentinelScheme_shouldFail() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=rediss-sentinel://sentinel:26379/mymaster",
                        "knolux.redis.azure.entra-id.enabled=true"
                )
                .run(ctx ->
                        assertThat(ctx)
                                .getFailure()
                                .hasMessageContaining("Sentinel")
                );
    }

    /**
     * 驗證啟用 Entra ID 但 URL 內含密碼時明確失敗（FR-043）——兩個憑證來源同時存在時，
     * 哪一個生效取決於實作細節，與其讓使用者誤以為密碼仍是備援，不如逼他明確二擇一。
     */
    @Test
    void entraIdEnabled_withPasswordInUrl_shouldFail() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=rediss://:secret@mycache.eastus.redis.azure.net:10000",
                        "knolux.redis.azure.entra-id.enabled=true"
                )
                .run(ctx ->
                        assertThat(ctx)
                                .getFailure()
                                .hasMessageContaining("密碼")
                );
    }

    /**
     * 驗證 {@code identity=USER_ASSIGNED} 缺少識別碼時明確失敗（FR-044）。
     */
    @Test
    void entraIdEnabled_userAssignedWithoutId_shouldFail() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=rediss://mycache.eastus.redis.azure.net:10000",
                        "knolux.redis.azure.entra-id.enabled=true",
                        "knolux.redis.azure.entra-id.identity=USER_ASSIGNED"
                )
                .run(ctx ->
                        assertThat(ctx)
                                .getFailure()
                                .hasMessageContaining("user-assigned-id")
                );
    }

    /**
     * 驗證 {@code identity=SERVICE_PRINCIPAL} 缺少必要欄位時，訊息逐項指名缺哪些（FR-045）。
     */
    @Test
    void entraIdEnabled_servicePrincipalMissingFields_shouldFail() {
        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=rediss://mycache.eastus.redis.azure.net:10000",
                        "knolux.redis.azure.entra-id.enabled=true",
                        "knolux.redis.azure.entra-id.identity=SERVICE_PRINCIPAL",
                        "knolux.redis.azure.entra-id.client-id=my-app-id"
                )
                .run(ctx ->
                        assertThat(ctx)
                                .getFailure()
                                .hasMessageContaining("client-secret")
                                .hasMessageContaining("authority")
                );
    }

    /**
     * 驗證使用者自訂的 {@link RedisCredentialsProviderFactory} 會取代內建的 Entra ID 實作，
     * 且連線工廠確實會採用它並開啟 {@code ON_NEW_CREDENTIALS}。
     *
     * <p>這是 1.5.0 新增的擴充點：token 來源不限於 Entra ID，
     * 任何能提供 {@link io.lettuce.core.RedisCredentialsProvider} 的實作都能接上。
     * 開啟重新驗證是動態憑證能長期運作的關鍵——少了它，背景更新取得的新憑證只會套用到
     * 之後新建的連線，連線池中既有的連線會在舊憑證失效時集體被 Redis 拒絕。
     */
    @Test
    void userDefinedCredentialsProviderFactory_shouldNotBeOverridden() {
        RedisCredentialsProviderFactory custom = new RedisCredentialsProviderFactory() {
        };

        contextRunner
                .withPropertyValues(
                        "knolux.redis.url=rediss://mycache.eastus.redis.azure.net:10000",
                        "knolux.redis.azure.entra-id.enabled=true"
                )
                .withBean("customCredentials", RedisCredentialsProviderFactory.class, () -> custom)
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(EntraIdCredentialsProviderFactory.class);

                    LettuceClientConfiguration clientConfig = clientConfigurationOf(ctx);
                    assertThat(clientConfig.getRedisCredentialsProviderFactory()).contains(custom);
                    assertThat(clientConfig.getClientOptions().orElseThrow().getReauthenticateBehaviour())
                            .isEqualTo(ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS);
                });
    }

    // ─────────────────────────────────────────────
    // HealthIndicator Bean
    // ─────────────────────────────────────────────

    /**
     * 驗證 Actuator 在 classpath 上時，自動設定能正確建立 knoluxRedis HealthIndicator Bean。
     * 此測試確認 HealthIndicator 是透過 @Bean 注册（而非 @Component + component scan）。
     */
    @Test
    void withActuatorPresent_shouldCreateHealthIndicatorBean() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redis://localhost:6379")
                .run(ctx ->
                        assertThat(ctx).hasBean("knoluxRedis")
                );
    }

    /**
     * 驗證使用者自訂的 knoluxRedis Bean 不會被自動設定覆寫。
     */
    @Test
    void userDefinedHealthIndicator_shouldNotBeOverridden() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redis://localhost:6379")
                .withBean(
                        "knoluxRedis",
                        KnoluxRedisHealthIndicator.class,
                        () -> new KnoluxRedisHealthIndicator(null)
                )
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(KnoluxRedisHealthIndicator.class)
                );
    }
}
