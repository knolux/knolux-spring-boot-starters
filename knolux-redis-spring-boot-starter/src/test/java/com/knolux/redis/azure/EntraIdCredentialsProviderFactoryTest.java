package com.knolux.redis.azure;

import com.knolux.redis.KnoluxRedisProperties;
import com.knolux.redis.azure.KnoluxRedisEntraIdProperties.IdentityType;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import redis.clients.authentication.core.IdentityProvider;
import redis.clients.authentication.core.SimpleToken;
import redis.clients.authentication.core.Token;
import redis.clients.authentication.core.TokenAuthConfig;
import redis.clients.authentication.core.TokenManagerConfig;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@link EntraIdCredentialsProviderFactory} 的單元測試。
 *
 * <p>token 來源以 {@code redis-authx-core} 的 {@link IdentityProvider} SPI 自製，
 * 不需要 Azure 環境即可驗證串流憑證的行為與生命週期。
 */
class EntraIdCredentialsProviderFactoryTest {

    private KnoluxRedisProperties props() {
        KnoluxRedisProperties p = new KnoluxRedisProperties();
        p.setTimeoutMs(Duration.ofSeconds(3));
        p.getAzure().getEntraId().setEnabled(true);
        return p;
    }

    /**
     * 建立一組不觸碰網路的 {@link TokenAuthConfig}：
     * {@code IdentityProviderConfig} 與 {@link IdentityProvider} 都是函式介面，
     * 可直接以 lambda 提供固定的假 token。
     */
    private TokenAuthConfig fakeTokenAuthConfig(AtomicInteger counter) {
        TokenManagerConfig tokenManagerConfig = new TokenManagerConfig(
                0.75f, 100, 1_000, new TokenManagerConfig.RetryPolicy(1, 10));
        IdentityProvider identityProvider = () -> fakeToken("user-" + counter.incrementAndGet());
        return new TokenAuthConfig(tokenManagerConfig, () -> identityProvider);
    }

    private Token fakeToken(String user) {
        long now = System.currentTimeMillis();
        return new SimpleToken(user, "token-value", now + Duration.ofMinutes(30).toMillis(), now, Map.of());
    }

    // ── 連線前置條件（FR-041 / FR-042 / FR-043）────────────────────────────────

    @Test
    void rejectsPlaintextStandaloneScheme() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdCredentialsProviderFactory.forConnection(
                        URI.create("redis://mycache.eastus.redis.azure.net:10000"), props()))
                .withMessageContaining("rediss://")
                .withMessageContaining("redis://");
    }

    @Test
    void rejectsPlaintextClusterScheme() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdCredentialsProviderFactory.forConnection(
                        URI.create("redis-cluster://mycache.eastus.redis.azure.net:10000"), props()))
                .withMessageContaining("rediss-cluster://");
    }

    @Test
    void rejectsSentinelScheme() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdCredentialsProviderFactory.forConnection(
                        URI.create("rediss-sentinel://sentinel:26379/mymaster"), props()))
                .withMessageContaining("Sentinel");
    }

    @Test
    void rejectsPasswordInUrl() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdCredentialsProviderFactory.forConnection(
                        URI.create("rediss://:secret@mycache.eastus.redis.azure.net:10000"), props()))
                .withMessageContaining("密碼");
    }

    @Test
    void acceptsTlsStandaloneScheme() {
        var factory = EntraIdCredentialsProviderFactory.forConnection(
                URI.create("rediss://mycache.eastus.redis.azure.net:10000"), props());
        assertThat(factory).isNotNull();
    }

    @Test
    void acceptsTlsClusterScheme() {
        var factory = EntraIdCredentialsProviderFactory.forConnection(
                URI.create("rediss-cluster://mycache.eastus.redis.azure.net:10000"), props());
        assertThat(factory).isNotNull();
    }

    @Test
    void acceptsTlsSchemeCaseInsensitively() {
        var factory = EntraIdCredentialsProviderFactory.forConnection(
                URI.create("REDISS://mycache.eastus.redis.azure.net:10000"), props());
        assertThat(factory).isNotNull();
    }

    @Test
    void propagatesIdentityValidationFailures() {
        KnoluxRedisProperties p = props();
        p.getAzure().getEntraId().setIdentity(IdentityType.USER_ASSIGNED);

        // forConnection 只是把 URI 前置條件疊在身分設定驗證之前，兩層都必須生效
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdCredentialsProviderFactory.forConnection(
                        URI.create("rediss://mycache.eastus.redis.azure.net:10000"), p))
                .withMessageContaining("user-assigned-id");
    }

    @Test
    void tokenRequestTimeoutNotShorterThanCommandTimeout_isAcceptedWithWarning() {
        KnoluxRedisProperties p = props();
        p.setTimeoutMs(Duration.ofSeconds(1));
        p.getAzure().getEntraId().setTokenRequestTimeout(Duration.ofSeconds(2));

        // FR-046 刻意只記 WARN：這是可疑而非不可能的組合，
        // 直接擋下會讓「連線逾時很短但 IdP 回應偏慢」的環境無法啟動
        assertThat(EntraIdCredentialsProviderFactory.forConnection(
                URI.create("rediss://mycache.eastus.redis.azure.net:10000"), p)).isNotNull();
    }

    // ── 憑證提供者行為 ─────────────────────────────────────────────────────────

    @Test
    void createCredentialsProvider_streamsTokenCredentials() {
        AtomicInteger counter = new AtomicInteger();
        try (var factory = new EntraIdCredentialsProviderFactory(fakeTokenAuthConfig(counter))) {
            RedisCredentialsProvider provider =
                    factory.createCredentialsProvider(new RedisStandaloneConfiguration("localhost", 6379));

            assertThat(provider).isNotNull();
            // 串流是 ON_NEW_CREDENTIALS 重新驗證的前提：非串流的 provider
            // 不會在 token 輪替時通知既有連線
            assertThat(provider.supportsStreaming()).isTrue();

            RedisCredentials credentials = provider.resolveCredentials().block(Duration.ofSeconds(5));
            assertThat(credentials).isNotNull();
            assertThat(credentials.getUsername()).isEqualTo("user-1");
            assertThat(credentials.getPassword()).isEqualTo("token-value".toCharArray());
        }
    }

    @Test
    void createCredentialsProvider_isMemoizedAcrossCalls() {
        AtomicInteger counter = new AtomicInteger();
        try (var factory = new EntraIdCredentialsProviderFactory(fakeTokenAuthConfig(counter))) {
            var first = factory.createCredentialsProvider(new RedisStandaloneConfiguration("localhost", 6379));
            var second = factory.createCredentialsProvider(new RedisStandaloneConfiguration("other", 6379));

            // 每次都新建會多開一組更新排程與執行緒池，且各自持有不同的 token
            assertThat(first).isSameAs(second);
        }
    }

    @Test
    void doesNotRequestTokenUntilCredentialsProviderIsRequested() {
        AtomicInteger counter = new AtomicInteger();
        try (var factory = new EntraIdCredentialsProviderFactory(fakeTokenAuthConfig(counter))) {
            assertThat(factory).isNotNull();
            // 延後建立才能讓「Bean 已裝配但尚未連線」的情境不觸發 IdP 請求
            assertThat(counter).hasValue(0);
        }
    }

    @Test
    void close_withoutUse_isNoOp() {
        var factory = new EntraIdCredentialsProviderFactory(fakeTokenAuthConfig(new AtomicInteger()));
        factory.close();
        factory.close();
    }

    @Test
    void close_isIdempotentAfterUse() {
        var factory = new EntraIdCredentialsProviderFactory(fakeTokenAuthConfig(new AtomicInteger()));
        factory.createCredentialsProvider(new RedisStandaloneConfiguration("localhost", 6379));

        factory.close();
        factory.close();
    }
}
