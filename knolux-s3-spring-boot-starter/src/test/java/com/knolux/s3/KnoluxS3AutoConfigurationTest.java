package com.knolux.s3;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KnoluxS3AutoConfiguration} 的 Bean 建立測試。
 *
 * <p>使用 {@link ApplicationContextRunner} 在不啟動完整應用程式的前提下，
 * 驗證各種設定組合下 Bean 的建立行為與 {@code @ConditionalOnMissingBean} 覆寫機制。
 */
class KnoluxS3AutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KnoluxS3AutoConfiguration.class))
            .withPropertyValues(
                    "knolux.s3.endpoint=http://fake-s3:9000",
                    "knolux.s3.access-key=testkey",
                    "knolux.s3.secret-key=testsecret"
            );

    // ── Bean 建立 ─────────────────────────────────────────────────────────────

    @Test
    void withValidConfig_shouldCreateClientFactory() {
        runner.run(ctx ->
                assertThat(ctx).hasSingleBean(KnoluxS3ClientFactory.class)
        );
    }

    @Test
    void withValidConfig_shouldCreateTemplate() {
        runner.run(ctx ->
                assertThat(ctx).hasSingleBean(KnoluxS3Template.class)
        );
    }

    @Test
    void properties_shouldBeAvailableAsBean() {
        runner.run(ctx ->
                assertThat(ctx).hasSingleBean(KnoluxS3Properties.class)
        );
    }

    @Test
    void withValidConfig_shouldCreateAllThreeBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(KnoluxS3ClientFactory.class);
            assertThat(ctx).hasSingleBean(KnoluxS3Template.class);
            assertThat(ctx).hasSingleBean(KnoluxS3Properties.class);
        });
    }

    // ── ConditionalOnMissingBean ──────────────────────────────────────────────

    @Test
    void userDefinedClientFactory_shouldNotBeOverridden() {
        KnoluxS3ConnectionDetails customDetails = new KnoluxS3ConnectionDetails(
                "http://custom-host:9000", "ap-east-1", "custom-key", "custom-secret",
                true, false, "", false
        );

        runner.withBean(
                "customFactory",
                KnoluxS3ClientFactory.class,
                () -> new KnoluxS3ClientFactory(customDetails)
        ).run(ctx -> {
            assertThat(ctx).hasSingleBean(KnoluxS3ClientFactory.class);
            KnoluxS3ClientFactory factory = ctx.getBean(KnoluxS3ClientFactory.class);
            // 確認是使用者定義的 Bean（以 bean name 識別）
            assertThat(ctx.getBeanNamesForType(KnoluxS3ClientFactory.class)).containsExactly("customFactory");
        });
    }

    @Test
    void userDefinedTemplate_shouldNotBeOverridden() {
        KnoluxS3ConnectionDetails details = new KnoluxS3ConnectionDetails(
                "http://fake-s3:9000", "us-east-1", "k", "s", true, false, "", false
        );
        KnoluxS3ClientFactory factory = new KnoluxS3ClientFactory(details);

        runner.withBean("customTemplate", KnoluxS3Template.class,
                () -> new KnoluxS3Template(factory)
        ).run(ctx -> {
            assertThat(ctx).hasSingleBean(KnoluxS3Template.class);
            assertThat(ctx.getBeanNamesForType(KnoluxS3Template.class)).containsExactly("customTemplate");
        });
    }

    // ── 各部署場景的 Properties 設定 ─────────────────────────────────────────

    @Test
    void k8sInternalConfig_shouldCreateBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KnoluxS3AutoConfiguration.class))
                .withPropertyValues(
                        "knolux.s3.endpoint=http://seaweedfs.seaweedfs.svc.cluster.local:8333",
                        "knolux.s3.access-key=key",
                        "knolux.s3.secret-key=secret",
                        "knolux.s3.force-path-style=true"
                )
                .run(ctx -> assertThat(ctx).hasSingleBean(KnoluxS3Template.class));
    }

    @Test
    void nginxProxyConfig_shouldCreateBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KnoluxS3AutoConfiguration.class))
                .withPropertyValues(
                        "knolux.s3.endpoint=https://aic.csh.org.tw",
                        "knolux.s3.access-key=key",
                        "knolux.s3.secret-key=secret",
                        "knolux.s3.remove-path-prefix=true",
                        "knolux.s3.path-prefix=/cluster/s3"
                )
                .run(ctx -> assertThat(ctx).hasSingleBean(KnoluxS3Template.class));
    }

    @Test
    void trustSelfSignedConfig_shouldCreateBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KnoluxS3AutoConfiguration.class))
                .withPropertyValues(
                        "knolux.s3.endpoint=https://s3.aic.org.tw",
                        "knolux.s3.access-key=key",
                        "knolux.s3.secret-key=secret",
                        "knolux.s3.trust-self-signed=true"
                )
                .run(ctx -> assertThat(ctx).hasSingleBean(KnoluxS3Template.class));
    }

    @Test
    void awsS3Config_withoutEndpoint_shouldCreateBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KnoluxS3AutoConfiguration.class))
                .withPropertyValues(
                        "knolux.s3.region=us-east-1",
                        "knolux.s3.access-key=AKIAIOSFODNN7EXAMPLE",
                        "knolux.s3.secret-key=wJalrXUtnFEMI",
                        "knolux.s3.force-path-style=false"
                )
                .run(ctx -> assertThat(ctx).hasSingleBean(KnoluxS3Template.class));
    }

    @Test
    void userDefinedS3ClientProvider_shouldSuppressDefaultFactory() {
        S3ClientProvider mockProvider = new S3ClientProvider() {
            @Override
            public software.amazon.awssdk.services.s3.S3AsyncClient getClient(KnoluxS3ConnectionDetails details) {
                return null;
            }
            @Override
            public void close() {}
        };

        runner.withBean("customProvider", S3ClientProvider.class, () -> mockProvider)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(S3ClientProvider.class);
                    // 預設 KnoluxS3ClientFactory 不應建立（@ConditionalOnMissingBean(S3ClientProvider.class)）
                    assertThat(ctx).doesNotHaveBean(KnoluxS3ClientFactory.class);
                    // KnoluxS3Template 應使用 mock provider
                    assertThat(ctx).hasSingleBean(KnoluxS3Template.class);
                });
    }

    // ── Virtual Thread Executor ───────────────────────────────────────────────

    @Test
    void withVirtualThreadsEnabled_shouldCreateVtExecutor() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KnoluxS3AutoConfiguration.class))
                .withPropertyValues(
                        "knolux.s3.endpoint=http://fake-s3:9000",
                        "knolux.s3.access-key=k",
                        "knolux.s3.secret-key=s",
                        "spring.threads.virtual.enabled=true"
                )
                .run(ctx -> {
                    assertThat(ctx).hasBean("knoluxS3Executor");
                    assertThat(ctx).hasSingleBean(KnoluxS3Template.class);
                });
    }

    @Test
    void withoutVirtualThreads_shouldUseCommonPoolExecutor() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("knoluxS3Executor");
            assertThat(ctx).hasSingleBean(KnoluxS3Template.class);
        });
    }
}
