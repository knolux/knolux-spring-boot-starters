package com.knolux.redis.connection;

import com.knolux.redis.KnoluxRedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslVerifyMode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.RedisCredentialsProviderFactory;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LettuceClientConfigurationFactory} 的單元測試。
 *
 * <p>此工廠是三個連線 builder 共用的組裝點，因此 TLS、逾時、讀取策略、
 * {@link ClientOptions} 與憑證提供者的行為只需在此驗證一次。
 */
class LettuceClientConfigurationFactoryTest {

    private KnoluxRedisProperties props() {
        KnoluxRedisProperties p = new KnoluxRedisProperties();
        p.setTimeoutMs(Duration.ofMillis(1500));
        p.setReadFrom("MASTER");
        return p;
    }

    private LettuceClientConfigurationFactory factory() {
        return new LettuceClientConfigurationFactory(null);
    }

    // ── TLS ───────────────────────────────────────────────────────────────────

    @Test
    void plaintextScheme_doesNotEnableSsl() {
        LettuceClientConfiguration config =
                factory().create(URI.create("redis://localhost:6379"), props());
        assertThat(config.isUseSsl()).isFalse();
    }

    @Test
    void tlsScheme_enablesSsl() {
        LettuceClientConfiguration config =
                factory().create(URI.create("rediss://localhost:6379"), props());
        assertThat(config.isUseSsl()).isTrue();
    }

    @Test
    void tlsClusterScheme_enablesSsl() {
        LettuceClientConfiguration config =
                factory().create(URI.create("rediss-cluster://localhost:10000"), props());
        assertThat(config.isUseSsl()).isTrue();
    }

    @Test
    void verifyPeerFull_verifiesPeer() {
        KnoluxRedisProperties p = props();
        p.getSsl().setVerifyPeer(SslVerifyMode.FULL);
        LettuceClientConfiguration config =
                factory().create(URI.create("rediss://localhost:6379"), p);
        assertThat(config.getVerifyMode()).isEqualTo(SslVerifyMode.FULL);
    }

    @Test
    void verifyPeerNone_disablesVerification() {
        // NONE 讓連線可被中間人攔截，僅限測試環境；實作須同時記錄 WARN
        KnoluxRedisProperties p = props();
        p.getSsl().setVerifyPeer(SslVerifyMode.NONE);
        LettuceClientConfiguration config =
                factory().create(URI.create("rediss://localhost:6379"), p);
        assertThat(config.getVerifyMode()).isEqualTo(SslVerifyMode.NONE);
    }

    @Test
    void verifyPeerCa_skipsHostnameVerificationOnly() {
        KnoluxRedisProperties p = props();
        p.getSsl().setVerifyPeer(SslVerifyMode.CA);
        LettuceClientConfiguration config =
                factory().create(URI.create("rediss://localhost:6379"), p);
        assertThat(config.getVerifyMode()).isEqualTo(SslVerifyMode.CA);
    }

    @Test
    void startTls_isPropagated() {
        KnoluxRedisProperties p = props();
        p.getSsl().setStartTls(true);
        LettuceClientConfiguration config =
                factory().create(URI.create("rediss://localhost:6379"), p);
        assertThat(config.isStartTls()).isTrue();
    }

    @Test
    void sslSettings_areIgnoredForPlaintextScheme() {
        // 明文 scheme 下即使設了 ssl.*，也不應意外開啟 TLS
        KnoluxRedisProperties p = props();
        p.getSsl().setStartTls(true);
        p.getSsl().setVerifyPeer(SslVerifyMode.NONE);
        LettuceClientConfiguration config =
                factory().create(URI.create("redis://localhost:6379"), p);
        assertThat(config.isUseSsl()).isFalse();
        assertThat(config.isStartTls()).isFalse();
    }

    // ── 逾時與讀取策略 ─────────────────────────────────────────────────────────

    @Test
    void commandTimeout_isPropagated() {
        LettuceClientConfiguration config =
                factory().create(URI.create("redis://localhost:6379"), props());
        assertThat(config.getCommandTimeout()).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void masterReadFrom_isNotSet() {
        // 純 MASTER / UPSTREAM 不設定 readFrom，Lettuce 便不啟動 topology refresh
        LettuceClientConfiguration config =
                factory().create(URI.create("redis://localhost:6379"), props());
        assertThat(config.getReadFrom()).isEmpty();
    }

    @Test
    void nonMasterReadFrom_isSet() {
        KnoluxRedisProperties p = props();
        p.setReadFrom("REPLICA_PREFERRED");
        LettuceClientConfiguration config =
                factory().create(URI.create("redis://localhost:6379"), p);
        assertThat(config.getReadFrom()).isPresent();
    }

    // ── ClientOptions / 憑證提供者 ────────────────────────────────────────────

    @Test
    void withoutCredentialsProvider_noFactoryIsRegistered() {
        LettuceClientConfiguration config =
                factory().create(URI.create("redis://localhost:6379"), props());
        assertThat(config.getRedisCredentialsProviderFactory()).isEmpty();
    }

    @Test
    void withoutCredentialsProvider_reauthenticateBehaviorStaysDefault() {
        // 沒有串流式憑證來源時不應強加 ON_NEW_CREDENTIALS，
        // 以免對既有的靜態密碼使用者改變行為
        LettuceClientConfiguration config =
                factory().create(URI.create("redis://localhost:6379"), props());
        assertThat(config.getClientOptions())
                .get()
                .extracting(ClientOptions::getReauthenticateBehaviour)
                .isEqualTo(ClientOptions.ReauthenticateBehavior.DEFAULT);
    }

    @Test
    void withCredentialsProvider_factoryIsRegistered() {
        RedisCredentialsProviderFactory credentials = new RedisCredentialsProviderFactory() {
        };
        LettuceClientConfiguration config = new LettuceClientConfigurationFactory(credentials)
                .create(URI.create("rediss://localhost:6379"), props());
        assertThat(config.getRedisCredentialsProviderFactory()).contains(credentials);
    }

    @Test
    void withCredentialsProvider_enablesReauthenticateOnNewCredentials() {
        // 沒有此設定，token 輪替不會套用到現存連線 —— token 到期時服務會集體失效
        RedisCredentialsProviderFactory credentials = new RedisCredentialsProviderFactory() {
        };
        LettuceClientConfiguration config = new LettuceClientConfigurationFactory(credentials)
                .create(URI.create("rediss://localhost:6379"), props());
        assertThat(config.getClientOptions())
                .get()
                .extracting(ClientOptions::getReauthenticateBehaviour)
                .isEqualTo(ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS);
    }
}
