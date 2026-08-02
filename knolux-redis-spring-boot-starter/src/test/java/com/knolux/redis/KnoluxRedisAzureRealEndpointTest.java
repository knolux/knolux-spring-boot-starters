package com.knolux.redis;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 針對真實 Azure Managed Redis 端點的手動驗收測試。
 *
 * <p>此類別標記為 {@code @Disabled}，不會在 CI 自動執行。它走的是完整的自動設定路徑
 * （{@link KnoluxRedisAutoConfiguration}），因此驗收的就是使用者實際會遇到的行為。
 *
 * <h2>執行前準備</h2>
 * <ol>
 *   <li>Azure Managed Redis 已啟用 Microsoft Entra 驗證</li>
 *   <li>執行環境的身分已在該 Redis 上被指派資料存取權限
 *       （Azure Portal → Data Access Configuration，指派 Data Owner 或等效角色）</li>
 *   <li>設定環境變數後，對單一 {@code @Test} 方法按右鍵「Run」</li>
 * </ol>
 *
 * <pre>
 *   # 必要
 *   export AZURE_REDIS_HOST=mycache.eastus.redis.azure.net
 *   # 選用，預設 10000（Azure Managed Redis 的 TLS 埠）
 *   export AZURE_REDIS_PORT=10000
 *
 *   # userAssignedManagedIdentity_roundTrip 專用
 *   export AZURE_REDIS_USER_ASSIGNED_ID=00000000-0000-0000-0000-000000000000
 *
 *   # servicePrincipal_roundTrip 專用
 *   export AZURE_CLIENT_ID=...
 *   export AZURE_CLIENT_SECRET=...
 *   export AZURE_TENANT_ID=...
 *
 *   # tokenRotation_shouldSurviveRenewal 專用，預設 5（分鐘）
 *   export AZURE_REDIS_SOAK_MINUTES=5
 * </pre>
 *
 * <h2>scheme 與 clustering policy 的對應</h2>
 * <p>Azure Managed Redis 建立時選擇的 clustering policy 決定客戶端該用哪個 scheme：
 * <ul>
 *   <li><strong>OSS</strong>（預設）—— 客戶端必須是 cluster-aware，用
 *       {@code rediss-cluster://}；分片會把客戶端導向 85xx 動態埠</li>
 *   <li><strong>Enterprise</strong> —— 單一端點代理分片，用 {@code rediss://}</li>
 * </ul>
 * 用錯 scheme 的症狀通常是逾時或 {@code MOVED} 錯誤，而非驗證失敗。
 *
 * @see KnoluxRedisTokenAuthIntegrationTest 不需 Azure 的 token 輪替整合測試
 */
@Disabled("手動驗收測試 — 需要連線至真實 Azure Managed Redis，請逐一執行")
class KnoluxRedisAzureRealEndpointTest {

    private static final String TEST_KEY = "knolux-redis-test:acceptance";

    private static final String TEST_VALUE = "Hello from KnoluxRedisAutoConfiguration!";

    // ── 情境一：OSS clustering policy（AMR 預設）+ 系統指派受控身分 ───────────

    /**
     * 最常見的正式部署組合：AKS / App Service 上的系統指派受控身分連 OSS policy 的 AMR。
     */
    @Test
    void ossClusteringPolicy_systemAssigned_roundTrip() {
        runner("rediss-cluster://" + endpoint(),
                "knolux.redis.azure.entra-id.enabled=true",
                "knolux.redis.azure.entra-id.identity=SYSTEM_ASSIGNED")
                .run(this::roundTrip);
    }

    // ── 情境二：Enterprise clustering policy + 系統指派受控身分 ───────────────

    /**
     * Enterprise policy 由伺服器端代理分片，客戶端當成單一節點連線即可。
     */
    @Test
    void enterpriseClusteringPolicy_systemAssigned_roundTrip() {
        runner("rediss://" + endpoint(),
                "knolux.redis.azure.entra-id.enabled=true",
                "knolux.redis.azure.entra-id.identity=SYSTEM_ASSIGNED")
                .run(this::roundTrip);
    }

    // ── 情境三：使用者指派的受控身分 ─────────────────────────────────────────

    /**
     * 多個服務共用同一身分時使用；識別碼型別由 {@code user-assigned-id-type} 指明。
     */
    @Test
    void userAssignedManagedIdentity_roundTrip() {
        String userAssignedId = requireEnv("AZURE_REDIS_USER_ASSIGNED_ID");

        runner("rediss-cluster://" + endpoint(),
                "knolux.redis.azure.entra-id.enabled=true",
                "knolux.redis.azure.entra-id.identity=USER_ASSIGNED",
                "knolux.redis.azure.entra-id.user-assigned-id-type=CLIENT_ID",
                "knolux.redis.azure.entra-id.user-assigned-id=" + userAssignedId)
                .run(this::roundTrip);
    }

    // ── 情境四：DefaultAzureCredential 憑證鏈（本機開發） ────────────────────

    /**
     * 沿用 {@code az login} 的登入狀態，讓本機開發與正式環境共用同一份設定。
     *
     * <p>需要 {@code com.azure:azure-identity} 在 classpath 上
     * （為 {@code redis-authx-entraid} 的傳遞依賴，通常自動具備）。
     */
    @Test
    void defaultAzureCredentialChain_roundTrip() {
        runner("rediss-cluster://" + endpoint(),
                "knolux.redis.azure.entra-id.enabled=true",
                "knolux.redis.azure.entra-id.identity=DEFAULT_CHAIN")
                .run(this::roundTrip);
    }

    // ── 情境五：Service Principal（非 Azure 環境） ──────────────────────────

    /**
     * 地端或其他雲的部署無法取得受控身分，只能用 Service Principal。
     */
    @Test
    void servicePrincipal_roundTrip() {
        String clientId = requireEnv("AZURE_CLIENT_ID");
        String clientSecret = requireEnv("AZURE_CLIENT_SECRET");
        String tenantId = requireEnv("AZURE_TENANT_ID");

        runner("rediss-cluster://" + endpoint(),
                "knolux.redis.azure.entra-id.enabled=true",
                "knolux.redis.azure.entra-id.identity=SERVICE_PRINCIPAL",
                "knolux.redis.azure.entra-id.client-id=" + clientId,
                "knolux.redis.azure.entra-id.client-secret=" + clientSecret,
                "knolux.redis.azure.entra-id.authority=https://login.microsoftonline.com/" + tenantId)
                .run(this::roundTrip);
    }

    // ── 情境六：token 輪替後連線仍然可用（長時間） ──────────────────────────

    /**
     * 驗證 token 更新後連線不會斷——這是本功能最關鍵、也最難用單元測試涵蓋的一點。
     *
     * <p>{@code lower-refresh-bound=59m} 是為了把更新時點拉近：Entra ID 發出的 token
     * 通常有 60～90 分鐘壽命，設定「到期前 59 分鐘就開始更新」等同要求在連線後數分鐘內
     * 完成一輪換發。實際時點仍取決於 Entra 回傳的 token 壽命，因此本測試以持續 ping 觀察，
     * 而非斷言某個精確時間。
     *
     * <p>過程中若看到 log 出現 {@code Token renew failed}，代表更新路徑有問題；
     * 若 ping 開始拋出 {@code WRONGPASS} 之類的錯誤，代表新 token 沒有套用到既有連線
     * （通常是 {@code ReauthenticateBehavior} 沒生效）。
     */
    @Test
    void tokenRotation_shouldSurviveRenewal() {
        Duration soak = Duration.ofMinutes(Long.parseLong(env("AZURE_REDIS_SOAK_MINUTES", "5")));

        runner("rediss-cluster://" + endpoint(),
                "knolux.redis.azure.entra-id.enabled=true",
                "knolux.redis.azure.entra-id.identity=SYSTEM_ASSIGNED",
                "knolux.redis.azure.entra-id.lower-refresh-bound=59m")
                .run(ctx -> {
                    var redis = ctx.getBean(StringRedisTemplate.class);
                    Instant deadline = Instant.now().plus(soak);

                    int round = 0;
                    while (Instant.now().isBefore(deadline)) {
                        redis.opsForValue().set(TEST_KEY, TEST_VALUE + "-" + round);
                        assertThat(redis.opsForValue().get(TEST_KEY)).isEqualTo(TEST_VALUE + "-" + round);
                        System.out.printf("[%s] round %d 成功，認證身分 %s%n",
                                Instant.now(), round, whoAmI(redis));
                        round++;
                        Thread.sleep(Duration.ofSeconds(30));
                    }
                    redis.delete(TEST_KEY);
                });
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    /**
     * 建立掛上完整自動設定的 {@link ApplicationContextRunner}。
     *
     * <p>{@code timeout-ms} 刻意設為 10 秒：跨網際網路連 Azure 再加上一次 token 請求，
     * 預設值容易在網路較慢時誤判為失敗。
     */
    private ApplicationContextRunner runner(String url, String... entraIdProperties) {
        String[] properties = new String[entraIdProperties.length + 2];
        properties[0] = "knolux.redis.url=" + url;
        properties[1] = "knolux.redis.timeout-ms=10s";
        System.arraycopy(entraIdProperties, 0, properties, 2, entraIdProperties.length);

        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KnoluxRedisAutoConfiguration.class))
                .withPropertyValues(properties);
    }

    /**
     * 寫入、讀回、刪除一顆 key，並印出實際認證成功的身分以利對照 Azure 端的權限設定。
     */
    private void roundTrip(AssertableApplicationContext ctx) {
        var redis = ctx.getBean(StringRedisTemplate.class);

        System.out.println("認證身分（Entra ID 物件 ID）：" + whoAmI(redis));

        redis.opsForValue().set(TEST_KEY, TEST_VALUE);
        assertThat(redis.opsForValue().get(TEST_KEY)).isEqualTo(TEST_VALUE);
        redis.delete(TEST_KEY);
        assertThat(redis.opsForValue().get(TEST_KEY)).isNull();
    }

    /**
     * 回傳目前連線所認證的使用者。在 Azure Managed Redis 上，這會是身分的物件（principal）ID
     * ——與 Azure Portal 的資料存取設定逐字比對，是權限問題最快的定位方式。
     */
    private static String whoAmI(StringRedisTemplate redis) {
        var factory = redis.getConnectionFactory();
        assertThat(factory).isNotNull();
        try (var conn = factory.getConnection()) {
            Object reply = conn.execute("ACL", "WHOAMI".getBytes(StandardCharsets.UTF_8));
            return new String((byte[]) reply, StandardCharsets.UTF_8);
        }
    }

    /**
     * 組出 {@code host:port}，未設定 {@code AZURE_REDIS_HOST} 時跳過測試。
     */
    private static String endpoint() {
        return requireEnv("AZURE_REDIS_HOST") + ":" + env("AZURE_REDIS_PORT", "10000");
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            Assumptions.abort("未設定環境變數 " + name + "，跳過此驗收測試");
        }
        return value;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
