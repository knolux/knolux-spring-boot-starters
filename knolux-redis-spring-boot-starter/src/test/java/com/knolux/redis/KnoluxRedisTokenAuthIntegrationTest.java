package com.knolux.redis;

import com.knolux.redis.azure.EntraIdCredentialsProviderFactory;
import com.knolux.redis.connection.LettuceClientConfigurationFactory;
import com.knolux.redis.connection.StandaloneConnectionFactoryBuilder;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.DockerClientFactory;
import redis.clients.authentication.core.IdentityProvider;
import redis.clients.authentication.core.SimpleToken;
import redis.clients.authentication.core.TokenAuthConfig;
import redis.clients.authentication.core.TokenManagerConfig;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * token 驗證與輪替的整合測試。
 *
 * <p>本測試<strong>不需要 Azure 環境</strong>：token 來源以 {@code redis-authx-core} 的
 * {@link IdentityProvider} SPI 自製，發出的「token」就是 Testcontainers Redis 上
 * ACL 使用者的密碼。要驗證的是本 starter 這一側的行為
 * ——憑證是否真的被送出、輪替後既有連線是否重新 AUTH——
 * 至於 token 從哪裡取得（Entra ID 或假的 provider）對這條路徑毫無影響。
 *
 * <h2>為何直接用建構子而非 {@code forConnection}</h2>
 * <p>{@link EntraIdCredentialsProviderFactory#forConnection} 會強制 TLS scheme，
 * 而 Testcontainers 的 Redis 走明文。改用公開建構子——它本來就是為
 * 「自備 {@link TokenAuthConfig}」的擴充情境而存在。
 * TLS 強制本身另有單元測試涵蓋。
 *
 * <h2>「同一條連線」的證明</h2>
 * <p>光看 {@code ACL WHOAMI} 從 alpha 變成 bravo 並不足以證明重新驗證生效
 * ——若連線被悄悄重建，新連線本來就會帶上新憑證。因此測試先以
 * {@code CLIENT SETNAME} 在連線上蓋一個唯一標記：Lettuce 重連時不會帶上這個名稱，
 * 名稱仍在就代表通道自始至終是同一條。
 *
 * <h2>前提條件</h2>
 * <p>執行此測試需要本機環境安裝 Docker 並處於執行狀態。
 *
 * @see EntraIdCredentialsProviderFactory
 * @see LettuceClientConfigurationFactory
 */
class KnoluxRedisTokenAuthIntegrationTest {

    private static final String ALPHA_USER = "knolux-alpha";

    private static final String ALPHA_SECRET = "alpha-token-value";

    private static final String BRAVO_USER = "knolux-bravo";

    private static final String BRAVO_SECRET = "bravo-token-value";

    /**
     * 假 token 的存活時間。
     *
     * <p>搭配 {@code expirationRefreshRatio = 0.5} 得到約 500ms 的更新週期，
     * 讓「輪替後重新驗證」在數秒內可觀測，而非等待正式環境的數十分鐘。
     */
    private static final Duration TOKEN_LIFETIME = Duration.ofSeconds(1);

    static final RedisContainer REDIS = new RedisContainer(
            RedisContainer.DEFAULT_IMAGE_NAME.withTag("7.4")
    );

    /**
     * 目前應發放的身分；測試中途換值即等同 Entra ID 換發了一顆屬於另一個身分的 token。
     */
    private final AtomicReference<Identity> currentIdentity =
            new AtomicReference<>(new Identity(ALPHA_USER, ALPHA_SECRET));

    /**
     * 假 identity provider 被索取 token 的次數，用來觀測背景更新排程是否仍在運作。
     */
    private final AtomicInteger tokenRequests = new AtomicInteger();

    @BeforeAll
    static void startContainer() throws Exception {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Docker 不可用，跳過整合測試：" + e.getMessage());
        }
        REDIS.start();
        createAclUser(ALPHA_USER, ALPHA_SECRET);
        createAclUser(BRAVO_USER, BRAVO_SECRET);
    }

    @AfterAll
    static void stopContainer() {
        REDIS.stop();
    }

    /**
     * 建立可用密碼登入且具備完整權限的 ACL 使用者。
     */
    private static void createAclUser(String user, String secret) throws Exception {
        var result = REDIS.execInContainer(
                "redis-cli", "ACL", "SETUSER", user, "on", ">" + secret, "~*", "+@all");
        assertThat(result.getStdout() + result.getStderr()).contains("OK");
    }

    @BeforeEach
    void resetTokenSource() {
        currentIdentity.set(new Identity(ALPHA_USER, ALPHA_SECRET));
        tokenRequests.set(0);
    }

    // ── 測試 ─────────────────────────────────────────────────────────────────

    /**
     * 驗證憑證提供者發出的 token 確實被當成 Redis 憑證送出：
     * 連線建立後的身分是 token 所指的 ACL 使用者，而非 {@code default}。
     */
    @Test
    void tokenCredentials_shouldAuthenticateAsTokenUser() {
        var credentials = new EntraIdCredentialsProviderFactory(shortLivedTokenConfig());
        LettuceConnectionFactory factory = connectionFactory(credentials);
        try (RedisConnection conn = factory.getConnection()) {
            assertThat(conn.ping()).isEqualTo("PONG");
            assertThat(whoAmI(conn)).isEqualTo(ALPHA_USER);
        } finally {
            factory.destroy();
            credentials.close();
        }
    }

    /**
     * 驗證 token 輪替後，<strong>既有連線</strong>會被重新 AUTH。
     *
     * <p>這是整個 Entra ID 支援能否長期運作的關鍵：少了
     * {@code ReauthenticateBehavior.ON_NEW_CREDENTIALS}，背景更新取得的新 token
     * 只會套用到之後新建的連線，連線池中的既有連線會在 token 到期時集體被 Redis 拒絕。
     */
    @Test
    void tokenRotation_shouldReauthenticateExistingConnection() {
        var credentials = new EntraIdCredentialsProviderFactory(shortLivedTokenConfig());
        LettuceConnectionFactory factory = connectionFactory(credentials);
        try (RedisConnection conn = factory.getConnection()) {
            String marker = "knolux-token-auth-" + System.nanoTime();
            setClientName(conn, marker);
            assertThat(whoAmI(conn)).isEqualTo(ALPHA_USER);

            currentIdentity.set(new Identity(BRAVO_USER, BRAVO_SECRET));

            await().atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> assertThat(whoAmI(conn)).isEqualTo(BRAVO_USER));

            // 連線名稱是「通道未被重建」的證據；重連後的新通道不會帶著這個標記
            assertThat(clientName(conn)).isEqualTo(marker);
        } finally {
            factory.destroy();
            credentials.close();
        }
    }

    /**
     * 驗證 {@link EntraIdCredentialsProviderFactory#close()} 會停止背景更新。
     *
     * <p>底層的更新排程與派送執行緒都是<strong>非 daemon</strong>執行緒，
     * 沒收乾淨會讓 JVM 無法結束——這正是 Bean 必須以
     * {@code destroyMethod = "close"} 註冊的理由。
     *
     * @throws InterruptedException 若靜置等待期間被中斷
     */
    @Test
    void close_shouldStopBackgroundRenewal() throws InterruptedException {
        var credentials = new EntraIdCredentialsProviderFactory(shortLivedTokenConfig());
        LettuceConnectionFactory factory = connectionFactory(credentials);
        try (RedisConnection conn = factory.getConnection()) {
            assertThat(whoAmI(conn)).isEqualTo(ALPHA_USER);
        } finally {
            factory.destroy();
        }

        // 先確認更新排程確實在跑，否則「關閉後不再增加」只是句廢話
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(tokenRequests).hasValueGreaterThan(1));

        credentials.close();
        int requestsAtClose = tokenRequests.get();

        // 靜置數個更新週期後仍未增加，即代表排程確實停止。
        // 容許 +1：close 的瞬間可能剛好有一次請求已經送進執行緒池。
        Thread.sleep(TOKEN_LIFETIME.multipliedBy(3).toMillis());
        assertThat(tokenRequests.get()).isLessThanOrEqualTo(requestsAtClose + 1);
    }

    // ── 測試裝配 ──────────────────────────────────────────────────────────────

    /**
     * 以正式的組裝路徑（{@link LettuceClientConfigurationFactory} →
     * {@link StandaloneConnectionFactoryBuilder}）建立連線工廠，
     * 確保測到的是使用者實際會走的那條線，而非測試專用的捷徑。
     */
    private LettuceConnectionFactory connectionFactory(EntraIdCredentialsProviderFactory credentials) {
        var properties = new KnoluxRedisProperties();
        properties.setTimeoutMs(Duration.ofSeconds(5));

        URI uri = URI.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        var factory = new StandaloneConnectionFactoryBuilder(new LettuceClientConfigurationFactory(credentials))
                .build(uri, properties);
        factory.afterPropertiesSet();
        return factory;
    }

    /**
     * 組出一份高頻更新的 {@link TokenAuthConfig}，token 內容取自 {@link #currentIdentity}。
     */
    private TokenAuthConfig shortLivedTokenConfig() {
        // lowerRefreshBoundMillis 設 0：只讓比例策略決定更新時機，
        // 否則 1 秒壽命的 token 會落在「下限已過」而被要求立刻不斷更新
        var tokenManagerConfig = new TokenManagerConfig(
                0.5f, 0, 1_000, new TokenManagerConfig.RetryPolicy(5, 100));

        IdentityProvider identityProvider = () -> {
            tokenRequests.incrementAndGet();
            Identity identity = currentIdentity.get();
            long now = System.currentTimeMillis();
            return new SimpleToken(
                    identity.user(), identity.secret(), now + TOKEN_LIFETIME.toMillis(), now, Map.of());
        };
        return new TokenAuthConfig(tokenManagerConfig, () -> identityProvider);
    }

    // ── Redis 指令工具 ────────────────────────────────────────────────────────

    /**
     * 回傳目前連線所認證的 ACL 使用者。
     *
     * <p>Spring Data Redis 沒有 {@code ACL WHOAMI} 的型別化 API，故走 {@code execute}；
     * 未列於型別對照表的指令預設以 {@code ByteArrayOutput} 解析，回傳 {@code byte[]}。
     */
    private static String whoAmI(RedisConnection conn) {
        Object reply = conn.execute("ACL", "WHOAMI".getBytes(StandardCharsets.UTF_8));
        return new String((byte[]) reply, StandardCharsets.UTF_8);
    }

    private static void setClientName(RedisConnection conn, String name) {
        conn.execute("CLIENT",
                "SETNAME".getBytes(StandardCharsets.UTF_8),
                name.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code CLIENT} 在 Spring Data Redis 的型別對照表中對應 {@code StatusOutput}，
     * 因此回傳的是已解碼的字串而非 {@code byte[]}。
     */
    private static String clientName(RedisConnection conn) {
        return (String) conn.execute("CLIENT", "GETNAME".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 一組「使用者名稱 + token 內容」，對應 Entra ID 的 {@code oid} claim 與 access token。
     */
    private record Identity(String user, String secret) {
    }
}
