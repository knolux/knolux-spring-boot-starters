package com.knolux.redis;

import io.lettuce.core.ReadFrom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RedisUriUtils} 的單元測試。
 */
class RedisUriUtilsTest {

    // ── parseReadFrom ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void parseReadFrom_nullOrBlank_returnsReplicaPreferred(String value) {
        assertThat(RedisUriUtils.parseReadFrom(value)).isSameAs(ReadFrom.REPLICA_PREFERRED);
    }

    @ParameterizedTest
    @CsvSource({
            "MASTER,          MASTER",
            "master,          MASTER",
            "UPSTREAM,        MASTER",
            "upstream,        MASTER",
            "MASTER_PREFERRED,   MASTER_PREFERRED",
            "master_preferred,   MASTER_PREFERRED",
            "UPSTREAM_PREFERRED, MASTER_PREFERRED",
            "REPLICA,         REPLICA",
            "replica,         REPLICA",
            "SLAVE,           REPLICA",
            "slave,           REPLICA",
            "REPLICA_PREFERRED,  REPLICA_PREFERRED",
            "replica_preferred,  REPLICA_PREFERRED",
            "SLAVE_PREFERRED,    REPLICA_PREFERRED",
            "slave_preferred,    REPLICA_PREFERRED",
            "LOWEST_LATENCY,  LOWEST_LATENCY",
            "lowest_latency,  LOWEST_LATENCY",
            "NEAREST,         LOWEST_LATENCY",
            "ANY,             ANY",
            "any,             ANY",
            "ANY_REPLICA,     ANY_REPLICA",
            "any_replica,     ANY_REPLICA",
    })
    void parseReadFrom_knownStrategies_resolveCorrectly(String input, String expectedName) {
        ReadFrom expected = switch (expectedName) {
            case "MASTER" -> ReadFrom.MASTER;
            case "MASTER_PREFERRED" -> ReadFrom.MASTER_PREFERRED;
            case "REPLICA" -> ReadFrom.REPLICA;
            case "REPLICA_PREFERRED" -> ReadFrom.REPLICA_PREFERRED;
            case "LOWEST_LATENCY" -> ReadFrom.LOWEST_LATENCY;
            case "ANY" -> ReadFrom.ANY;
            case "ANY_REPLICA" -> ReadFrom.ANY_REPLICA;
            default -> throw new IllegalArgumentException("unknown: " + expectedName);
        };
        assertThat(RedisUriUtils.parseReadFrom(input)).isSameAs(expected);
    }

    @Test
    void parseReadFrom_unknownValue_returnsReplicaPreferred() {
        assertThat(RedisUriUtils.parseReadFrom("TOTALLY_UNKNOWN")).isSameAs(ReadFrom.REPLICA_PREFERRED);
    }

    @Test
    void parseReadFrom_malformedSubnet_fallsBackToReplicaPreferred() {
        // subnet: 前綴交由 Lettuce 解析，CIDR 無效時應被 catch 並退回 REPLICA_PREFERRED
        assertThat(RedisUriUtils.parseReadFrom("subnet:not-a-cidr")).isSameAs(ReadFrom.REPLICA_PREFERRED);
    }

    // ── parsePassword ──────────────────────────────────────────────────────────

    @Test
    void parsePassword_noUserInfo_returnsNull() throws Exception {
        assertThat(RedisUriUtils.parsePassword(new URI("redis://localhost:6379"))).isNull();
    }

    @Test
    void parsePassword_passwordOnly_returnsPassword() throws Exception {
        assertThat(RedisUriUtils.parsePassword(new URI("redis://:secret@localhost:6379"))).isEqualTo("secret");
    }

    @Test
    void parsePassword_usernameAndPassword_returnsPassword() throws Exception {
        assertThat(RedisUriUtils.parsePassword(new URI("redis://user:secret@localhost:6379"))).isEqualTo("secret");
    }

    @Test
    void parsePassword_passwordContainingColon_isPreserved() throws Exception {
        // split(":", 2) 的 limit=2 確保密碼內的冒號被保留
        assertThat(RedisUriUtils.parsePassword(new URI("redis://:p4ss:w0rd@host:6379"))).isEqualTo("p4ss:w0rd");
    }

    // ── parseDb ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"/", " "})
    void parseDb_emptyOrRoot_returnsZero(String path) {
        assertThat(RedisUriUtils.parseDb(path)).isZero();
    }

    @Test
    void parseDb_validIndex_returnsIndex() {
        assertThat(RedisUriUtils.parseDb("/3")).isEqualTo(3);
    }

    @Test
    void parseDb_nonNumeric_throwsIllegalArgument() {
        // 非數字 DB 區段為設定錯誤，應 fail-fast 而非靜默退回 DB 0
        assertThatThrownBy(() -> RedisUriUtils.parseDb("/abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DB");
    }

    // ── isTls ─────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "rediss://localhost:6379",
            "REDISS://localhost:6379",
            "rediss-sentinel://localhost:26379/mymaster",
            "rediss-cluster://localhost:10000",
            "Rediss-Cluster://localhost:10000",
    })
    void isTls_tlsSchemes_returnTrue(String url) {
        assertThat(RedisUriUtils.isTls(URI.create(url))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "redis://localhost:6379",
            "redis-sentinel://localhost:26379/mymaster",
            "redis-cluster://localhost:10000",
            "http://localhost:8080",
    })
    void isTls_plaintextOrUnknownSchemes_returnFalse(String url) {
        assertThat(RedisUriUtils.isTls(URI.create(url))).isFalse();
    }

    @Test
    void isTls_schemeStartingWithRedissButNotAnAlias_returnsFalse() {
        // 以字首比對會把 redissomething:// 誤判為 TLS；此處採具名別名表，
        // 未知 scheme 一律 false 並交由 Auto-Configuration 的 orElseThrow 拒絕
        assertThat(RedisUriUtils.isTls(URI.create("redissomething://localhost:6379"))).isFalse();
    }

    // ── baseScheme ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "rediss://localhost:6379,                    redis",
            "REDISS://localhost:6379,                    redis",
            "redis://localhost:6379,                     redis",
            "REDIS://localhost:6379,                     redis",
            "rediss-sentinel://localhost:26379/m,        redis-sentinel",
            "redis-sentinel://localhost:26379/m,         redis-sentinel",
            "rediss-cluster://localhost:10000,           redis-cluster",
            "redis-cluster://localhost:10000,            redis-cluster",
    })
    void baseScheme_stripsTlsMarkerAndLowercases(String url, String expected) {
        assertThat(RedisUriUtils.baseScheme(URI.create(url))).isEqualTo(expected);
    }

    @Test
    void baseScheme_unknownScheme_returnsLowercasedAsIs() {
        // 不做任何猜測式改寫：未知 scheme 原樣回傳，由呼叫端 fail-fast
        assertThat(RedisUriUtils.baseScheme(URI.create("HTTP://localhost:8080"))).isEqualTo("http");
    }

    @Test
    void baseScheme_nullScheme_returnsNull() {
        assertThat(RedisUriUtils.baseScheme(URI.create("//localhost:6379"))).isNull();
    }

    // ── isMasterOnly ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"MASTER", "master", "UPSTREAM", "upstream"})
    void isMasterOnly_masterOrUpstream_returnsTrue(String value) {
        assertThat(RedisUriUtils.isMasterOnly(value)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"REPLICA", "REPLICA_PREFERRED", "ANY"})
    void isMasterOnly_other_returnsFalse(String value) {
        assertThat(RedisUriUtils.isMasterOnly(value)).isFalse();
    }
}
