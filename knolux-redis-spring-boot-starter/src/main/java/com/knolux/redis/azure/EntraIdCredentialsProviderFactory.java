package com.knolux.redis.azure;

import com.knolux.redis.KnoluxRedisProperties;
import com.knolux.redis.RedisUriUtils;
import io.lettuce.authx.TokenBasedRedisCredentialsProvider;
import io.lettuce.core.RedisCredentialsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.lettuce.RedisCredentialsProviderFactory;
import org.springframework.util.ClassUtils;
import redis.clients.authentication.core.TokenAuthConfig;

import java.net.URI;
import java.time.Duration;

/**
 * 以 Microsoft Entra ID token 作為 Redis 憑證來源的
 * {@link RedisCredentialsProviderFactory} 實作。
 *
 * <p>Spring Data Redis 透過此介面向 Lettuce 提供憑證。本實作回傳的
 * {@link TokenBasedRedisCredentialsProvider} 是<strong>串流式</strong>憑證來源：
 * 背景更新取得新 token 時會推送給所有既有連線，搭配
 * {@code ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS}
 * （由 {@code LettuceClientConfigurationFactory} 一併開啟）才能讓長壽連線在
 * token 到期前重新 AUTH，而不是集體被 Redis 拒絕。
 *
 * <h2>生命週期</h2>
 * <p>底層 provider 持有更新排程與執行緒池，其 Javadoc 明載「生命週期由外部管理」，
 * 因此本類別實作 {@link AutoCloseable}，並在 Auto-Configuration 中以
 * {@code @Bean(destroyMethod = "close")} 註冊。
 *
 * <p>provider 採<strong>延後建立</strong>：{@link TokenBasedRedisCredentialsProvider#create}
 * 會立刻啟動第一次 token 請求，若在建構子就建立，任何「只是把 Bean 裝配起來」的情境
 * （測試、尚未連線的應用程式）都會平白對 Entra ID 發出請求。
 *
 * <h2>選用依賴</h2>
 * <p>本類別<strong>不</strong>參考 {@code redis.clients.authentication.entraid.*}，
 * 那些型別集中在 {@link EntraIdTokenAuthConfigFactory}。
 * {@link #forConnection} 會先確認該依賴在 classpath 上，才碰觸該類別。
 *
 * @see EntraIdTokenAuthConfigFactory
 * @see KnoluxRedisEntraIdProperties
 */
@Slf4j
public class EntraIdCredentialsProviderFactory implements RedisCredentialsProviderFactory, AutoCloseable {

    /**
     * 用來判斷 {@code redis-authx-entraid} 是否在 classpath 上的標記類別。
     */
    private static final String ENTRA_ID_MARKER_CLASS =
            "redis.clients.authentication.entraid.EntraIDTokenAuthConfigBuilder";

    /**
     * 用來判斷 {@code azure-identity} 是否在 classpath 上的標記類別，
     * 僅 {@code DEFAULT_CHAIN} 身分來源需要。
     */
    private static final String AZURE_IDENTITY_MARKER_CLASS = "com.azure.identity.DefaultAzureCredentialBuilder";

    /**
     * 與本 starter 一同驗證過的選用依賴版本，用於組出可直接複製的座標。
     */
    private static final String ENTRA_ID_VERSION = "0.1.1-beta2";

    private final TokenAuthConfig tokenAuthConfig;

    private TokenBasedRedisCredentialsProvider provider;

    private boolean closed;

    /**
     * 以既有的 {@link TokenAuthConfig} 建構。
     *
     * <p>此建構子不涉及任何 Azure 專屬型別，因此也是替換 token 來源的擴充點：
     * 自訂 {@code IdentityProvider}（例如測試用的假 token 來源）可直接組出
     * {@link TokenAuthConfig} 交給本類別。
     *
     * @param tokenAuthConfig token 驗證設定
     */
    public EntraIdCredentialsProviderFactory(TokenAuthConfig tokenAuthConfig) {
        this.tokenAuthConfig = tokenAuthConfig;
    }

    /**
     * 依連線 URI 與外部化設定建立工廠，並在此完成所有啟動階段檢查。
     *
     * <p>檢查順序刻意由「與身分設定無關」到「身分設定細節」：先確定這條連線
     * 本來就適合用 token 驗證，再檢查依賴，最後才驗證身分欄位。
     *
     * @param uri        連線 URI
     * @param properties 外部化設定
     * @return 可註冊為 Bean 的憑證提供者工廠
     * @throws IllegalArgumentException 任一前置條件不成立時（訊息帶實際值與修正方式）
     */
    public static EntraIdCredentialsProviderFactory forConnection(URI uri, KnoluxRedisProperties properties) {
        KnoluxRedisEntraIdProperties entraId = properties.getAzure().getEntraId();

        validateScheme(uri);
        validateNoStaticPassword(uri);
        requireOptionalDependencies(entraId);
        warnOnTokenRequestTimeout(entraId.getTokenRequestTimeout(), properties.getTimeoutMs());

        return new EntraIdCredentialsProviderFactory(EntraIdTokenAuthConfigFactory.create(entraId));
    }

    /**
     * 回傳串流式的 token 憑證提供者。
     *
     * <p>刻意忽略 {@code redisConfiguration}：token 與節點無關，
     * 且所有節點共用同一顆 token 才能讓更新一次生效。
     *
     * @param redisConfiguration Spring Data 的連線設定（未使用）
     * @return 串流式憑證提供者，同一工廠實例永遠回傳同一個
     */
    @Override
    public synchronized RedisCredentialsProvider createCredentialsProvider(RedisConfiguration redisConfiguration) {
        if (closed) {
            throw new IllegalStateException(
                    "EntraIdCredentialsProviderFactory 已關閉，無法再提供憑證。"
                            + "此情況通常代表在 ApplicationContext 關閉後仍嘗試建立 Redis 連線。");
        }
        if (provider == null) {
            provider = TokenBasedRedisCredentialsProvider.create(tokenAuthConfig);
        }
        return provider;
    }

    /**
     * 停止背景 token 更新並釋放其執行緒資源。
     *
     * <p>可重複呼叫；尚未建立過 provider 時為 no-op。
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (provider != null) {
            provider.close();
            provider = null;
        }
    }

    // ─────────────────────────────────────────────
    // 啟動階段檢查
    // ─────────────────────────────────────────────

    private static void validateScheme(URI uri) {
        String baseScheme = RedisUriUtils.baseScheme(uri);
        if ("redis-sentinel".equals(baseScheme)) {
            throw new IllegalArgumentException(
                    "knolux.redis.azure.entra-id.enabled=true 不支援 Sentinel 連線（實際 scheme: "
                            + uri.getScheme() + "://）。Azure 不提供 Sentinel，"
                            + "Azure Managed Redis 請改用 rediss-cluster://（OSS clustering policy，預設）"
                            + "或 rediss://（Enterprise clustering policy）。");
        }
        if (!RedisUriUtils.isTls(uri)) {
            throw new IllegalArgumentException(
                    "knolux.redis.azure.entra-id.enabled=true 但連線使用明文 scheme: "
                            + uri.getScheme() + "://。Entra ID 發出的是 bearer token，"
                            + "任何取得該字串的人都能直接冒用，因此不得走明文連線。"
                            + "請改用 rediss://（Standalone）或 rediss-cluster://（Cluster）。");
        }
    }

    private static void validateNoStaticPassword(URI uri) {
        String password = RedisUriUtils.parsePassword(uri);
        if (password != null && !password.isBlank()) {
            // 兩個憑證來源同時存在時，究竟哪一個生效取決於實作細節。
            // 與其讓使用者以為密碼仍是備援，不如逼他們明確二擇一。
            throw new IllegalArgumentException(
                    "knolux.redis.url 內含密碼，與 knolux.redis.azure.entra-id.enabled=true 相衝突。"
                            + "啟用 Entra ID 後憑證一律來自 token，URL 中的密碼不會被使用。"
                            + "請移除 URL 的密碼段，或改為 enabled=false 使用密碼驗證。");
        }
    }

    private static void requireOptionalDependencies(KnoluxRedisEntraIdProperties entraId) {
        ClassLoader classLoader = EntraIdCredentialsProviderFactory.class.getClassLoader();

        if (!ClassUtils.isPresent(ENTRA_ID_MARKER_CLASS, classLoader)) {
            throw new IllegalArgumentException(missingDependencyMessage(
                    "redis.clients.authentication:redis-authx-entraid", ENTRA_ID_VERSION,
                    "knolux.redis.azure.entra-id.enabled=true"));
        }
        // azure-identity 是 redis-authx-entraid 的傳遞依賴，正常情況會一併帶入；
        // 但只有 DEFAULT_CHAIN 會用到它，若被刻意排除，其餘身分來源仍應可用，
        // 因此這道檢查綁在身分來源上而非無條件執行。
        if (entraId.getIdentity() == KnoluxRedisEntraIdProperties.IdentityType.DEFAULT_CHAIN
                && !ClassUtils.isPresent(AZURE_IDENTITY_MARKER_CLASS, classLoader)) {
            throw new IllegalArgumentException(missingDependencyMessage(
                    "com.azure:azure-identity", null,
                    "knolux.redis.azure.entra-id.identity=DEFAULT_CHAIN"));
        }
    }

    private static String missingDependencyMessage(String coordinate, String version, String trigger) {
        String gradleVersion = version == null ? "" : ":" + version;
        String mavenVersion = version == null ? "" : "\n    <version>" + version + "</version>";
        String[] parts = coordinate.split(":");
        return trigger + " 需要 " + coordinate + "，但 classpath 上找不到。"
                + "本 starter 以 compileOnly 引入此依賴（未啟用的使用者不必背負 msal4j / azure-identity 等傳遞依賴），"
                + "請自行加入：\n"
                + "  Gradle: implementation(\"" + coordinate + gradleVersion + "\")\n"
                + "  Maven : <dependency>\n"
                + "    <groupId>" + parts[0] + "</groupId>\n"
                + "    <artifactId>" + parts[1] + "</artifactId>" + mavenVersion + "\n"
                + "  </dependency>";
    }

    private static void warnOnTokenRequestTimeout(Duration tokenRequestTimeout, Duration commandTimeout) {
        if (tokenRequestTimeout.compareTo(commandTimeout) >= 0) {
            // 只警告不擋：Lettuce 是在連線建立後才索取憑證，逾時偏長會拖垮連線建立，
            // 但「IdP 回應慢」在某些網路環境是事實，硬性阻擋反而讓人無法啟動
            log.warn("knolux.redis.azure.entra-id.token-request-timeout={} 不小於 knolux.redis.timeout-ms={}："
                            + "Lettuce 在連線建立後才向憑證提供者索取 token，"
                            + "token 請求若超出連線建立逾時，連線本身就會失敗。"
                            + "建議把 token-request-timeout 調到明顯小於 timeout-ms。",
                    tokenRequestTimeout, commandTimeout);
        }
    }
}
