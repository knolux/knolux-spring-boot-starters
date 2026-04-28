package com.knolux.redis;

import io.lettuce.core.ReadFrom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

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
    void parseDb_nonNumeric_returnsZero() {
        assertThat(RedisUriUtils.parseDb("/abc")).isZero();
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
