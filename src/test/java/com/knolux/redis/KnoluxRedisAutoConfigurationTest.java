package com.knolux.redis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class KnoluxRedisAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KnoluxRedisAutoConfiguration.class));

    // ─────────────────────────────────────────────
    // 直連模式（redis://）
    // ─────────────────────────────────────────────

    @Test
    void standalone_shouldCreateAllBeans() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redis://:password@localhost:6379")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class);
                    assertThat(ctx).hasSingleBean(StringRedisTemplate.class);
                    // ✅ 用 hasBean("name") 避免 StringRedisTemplate 被計入
                    assertThat(ctx).hasBean("redisTemplate");
                    assertThat(ctx).hasBean("stringRedisTemplate");
                });
    }

    @Test
    void standalone_withoutPassword_shouldCreateConnectionFactory() {
        contextRunner
                .withPropertyValues("knolux.redis.url=redis://localhost:6379")
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(LettuceConnectionFactory.class)
                );
    }

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

    @Test
    void missingUrl_shouldFailWithIllegalArgumentException() {
        contextRunner
                .run(ctx ->
                        assertThat(ctx)
                                .getFailure()
                                .hasMessageContaining("knolux.redis.url is required")
                );
    }

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