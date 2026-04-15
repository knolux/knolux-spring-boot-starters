# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

`knolux-starters` is a Gradle multi-module monorepo publishing Spring Boot auto-configuration starters to GitHub Packages. The root project is an aggregator only — it publishes no artifact. Each submodule is an independently versioned starter.

Current modules:
- `:knolux-redis` — Redis starter supporting Standalone (`redis://`) and Sentinel (`redis-sentinel://`) modes

## Commands

```bash
# Run all tests (all modules)
./gradlew test

# Run tests for a specific module
./gradlew :knolux-redis:test

# Run a single test class
./gradlew :knolux-redis:test --tests "com.knolux.redis.KnoluxRedisAutoConfigurationTest"

# Run a single test method
./gradlew :knolux-redis:test --tests "com.knolux.redis.KnoluxRedisAutoConfigurationTest.standalone_shouldCreateAllBeans"

# Build everything
./gradlew build

# Publish a module (CI does this; requires GITHUB_TOKEN + GITHUB_ACTOR)
./gradlew :knolux-redis:publish -Pversion=1.0.1
```

Test reports: `<module>/build/reports/tests/test/`

## Adding a New Module

1. Create `knolux-<name>/` directory
2. Add `include(":knolux-<name>")` to `settings.gradle.kts`
3. Create `knolux-<name>/gradle.properties` with `version=1.0.0`
4. Create `knolux-<name>/build.gradle.kts` with only `description` and `dependencies {}`
5. All other config (toolchain, BOM, publishing) is inherited from root `subprojects {}`

## Version Management

- Module versions live in `<module>/gradle.properties` (`version=x.y.z`)
- Do NOT hardcode `version = "..."` in `build.gradle.kts` — it would override the publish workflow's `-Pversion=X` flag
- All dependency versions are in `gradle/libs.versions.toml`
- Spring Boot-managed libraries omit version in the catalog (the BOM handles them)

## Publishing

Artifacts publish to GitHub Packages on module-scoped git tags:
```bash
git tag knolux-redis/v1.0.1
git push origin knolux-redis/v1.0.1
```
Only the tagged module is tested and published. Other modules are not affected.

## Architecture

### Root project

- `build.gradle.kts` — declares third-party plugins (with versions, `apply false`), sets `group = "com.knolux"`, and configures all subprojects via `subprojects {}`: Java 25 toolchain, Spring Boot 4.0.5 BOM, `useJUnitPlatform()`, publishing template
- `gradle/libs.versions.toml` — version catalog; auto-detected by Gradle as `libs`
- `settings.gradle.kts` — root name + `include()` for each module

### :knolux-redis

Spring Boot auto-configuration starter for Redis. Core files:

1. **`KnoluxRedisProperties`** — binds `knolux.redis.*` (url, timeout-ms, read-from)
2. **`KnoluxRedisAutoConfiguration`** — `@AutoConfiguration` that creates `LettuceConnectionFactory` (Sentinel or Standalone based on URL scheme), `StringRedisTemplate`, `RedisTemplate<String, Object>`. All beans are `@ConditionalOnMissingBean`
3. **`KnoluxRedisHealthIndicator`** — Spring Actuator health contributor, `@ConditionalOnClass(HealthIndicator.class)`
4. **`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`** — Spring Boot 3+ auto-configuration registration

URL parsing: `redis://` → `buildStandaloneFactory()`, `redis-sentinel://` → `buildSentinelFactory()`. Password extracted from userinfo (`:password` before `@`).

### Testing

- **Unit/slice tests** (no Docker): `KnoluxRedisPropertiesTest`, `KnoluxRedisAutoConfigurationTest`, `KnoluxRedisHealthIndicatorTest` — use `ApplicationContextRunner` or mocks
- **Integration tests** (require Docker): `KnoluxRedisStandaloneIntegrationTest`, `KnoluxRedisSentinelIntegrationTest`, `KnoluxRedisHealthIndicatorIntegrationTest` — use Testcontainers
