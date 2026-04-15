# Multi-Module Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the single-module project into a Gradle multi-module monorepo named `knolux-starters`, with the existing Redis starter as `:knolux-redis`.

**Architecture:** Root project is an aggregator only (no artifact published). Shared Gradle config lives in `subprojects {}` in root `build.gradle.kts`. Versions centralized in `gradle/libs.versions.toml`. Each submodule declares only its own dependencies; its version lives in its own `gradle.properties` so the publish workflow can override it with `-Pversion=X`.

**Tech Stack:** Gradle 9.x Kotlin DSL, Spring Boot 4.0.5, Java 25 toolchain, GitHub Actions

---

## File Map

| Action | Path |
|--------|------|
| Move | `src/` → `knolux-redis/src/` |
| Create | `gradle/libs.versions.toml` |
| Rewrite | `settings.gradle.kts` |
| Rewrite | `build.gradle.kts` |
| Create | `knolux-redis/gradle.properties` |
| Create | `knolux-redis/build.gradle.kts` |
| Rewrite | `.github/workflows/publish.yml` |
| Modify | `.github/workflows/ci.yml` |
| Update | `CLAUDE.md` |

---

### Task 1: Move source tree into knolux-redis/

**Files:**
- Move: `src/` → `knolux-redis/src/`

- [ ] **Step 1: Create submodule directory**

```bash
mkdir knolux-redis
```

- [ ] **Step 2: Move src/ with git to preserve history**

```bash
git mv src knolux-redis/src
```

- [ ] **Step 3: Verify the move**

```bash
ls knolux-redis/src/main/java/com/knolux/redis/
```

Expected output includes: `KnoluxRedisAutoConfiguration.java  KnoluxRedisHealthIndicator.java  KnoluxRedisProperties.java`

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: move src/ into knolux-redis/src/"
```

---

### Task 2: Create the version catalog

**Files:**
- Create: `gradle/libs.versions.toml`

- [ ] **Step 1: Create the file**

`gradle/libs.versions.toml`:
```toml
[versions]
spring-boot                  = "4.0.5"
spring-dependency-management = "1.1.7"
testcontainers-redis         = "2.2.2"

[libraries]
spring-boot-autoconfigure           = { module = "org.springframework.boot:spring-boot-autoconfigure" }
spring-boot-starter-data-redis      = { module = "org.springframework.boot:spring-boot-starter-data-redis" }
spring-boot-starter-actuator        = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-boot-configuration-processor = { module = "org.springframework.boot:spring-boot-configuration-processor" }
spring-boot-starter-test            = { module = "org.springframework.boot:spring-boot-starter-test" }
spring-boot-testcontainers          = { module = "org.springframework.boot:spring-boot-testcontainers" }
testcontainers-redis                = { module = "com.redis:testcontainers-redis", version.ref = "testcontainers-redis" }
junit-platform-launcher             = { module = "org.junit.platform:junit-platform-launcher" }

[plugins]
spring-boot                  = { id = "org.springframework.boot",        version.ref = "spring-boot" }
spring-dependency-management = { id = "io.spring.dependency-management", version.ref = "spring-dependency-management" }
```

Note: Spring Boot-managed libraries omit version — the BOM handles them. Only `testcontainers-redis` is outside the BOM and needs an explicit version.

- [ ] **Step 2: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "build: add version catalog"
```

---

### Task 3: Update settings.gradle.kts

**Files:**
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Replace the entire file**

`settings.gradle.kts`:
```kotlin
rootProject.name = "knolux-starters"

include(":knolux-redis")
```

The `gradle/libs.versions.toml` file is auto-detected by Gradle as the default version catalog named `libs` — no extra configuration needed here.

- [ ] **Step 2: Commit**

```bash
git add settings.gradle.kts
git commit -m "build: rename root to knolux-starters, include :knolux-redis"
```

---

### Task 4: Rewrite root build.gradle.kts

**Files:**
- Rewrite: `build.gradle.kts`

- [ ] **Step 1: Replace the entire file**

`build.gradle.kts`:
```kotlin
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    `java-library` apply false
    `maven-publish` apply false
    id("org.springframework.boot") version "4.0.5" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.knolux"
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
        withSourcesJar()
    }

    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.5")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                versionMapping {
                    usage("java-api") { fromResolutionOf("runtimeClasspath") }
                    usage("java-runtime") { fromResolutionResult() }
                }
                pom {
                    name.set(project.name)
                    description.set(project.description ?: "")
                    url.set("https://github.com/knolux/knolux-starters")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/knolux/knolux-starters")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: ""
                    password = System.getenv("GITHUB_TOKEN") ?: ""
                }
            }
        }
    }
}
```

Key differences from the old root build:
- No `version = "1.0.0"` (version lives in each submodule's `gradle.properties`)
- No `java { sourceCompatibility/targetCompatibility }` — toolchain alone is sufficient
- `apply false` on all plugins so they're on the classpath but not applied to root
- `subprojects {}` applies plugins and shared config to all submodules

- [ ] **Step 2: Commit**

```bash
git add build.gradle.kts
git commit -m "build: rewrite root build.gradle.kts for multi-module layout"
```

---

### Task 5: Create knolux-redis submodule build files

**Files:**
- Create: `knolux-redis/gradle.properties`
- Create: `knolux-redis/build.gradle.kts`

- [ ] **Step 1: Create gradle.properties with the module version**

`knolux-redis/gradle.properties`:
```properties
version=1.0.0
```

This is the correct place for the version. A hardcoded `version = "..."` in `build.gradle.kts` would override `-Pversion=X` from the publish workflow (build script runs after `-P` properties are loaded). `gradle.properties` values ARE overridden by `-Pversion=X`.

- [ ] **Step 2: Create the submodule build file**

`knolux-redis/build.gradle.kts`:
```kotlin
description = "Redis Starter for Spring Boot, supports Sentinel and Standalone"

dependencies {
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter.data.redis)
    compileOnly(libs.spring.boot.starter.actuator)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.redis)
    testRuntimeOnly(libs.junit.platform.launcher)
}
```

Only `description` and dependencies. Everything else (toolchain, BOM, publishing) is inherited from the root `subprojects {}` block. The `libs.*` accessors reference `gradle/libs.versions.toml`.

- [ ] **Step 3: Commit**

```bash
git add knolux-redis/gradle.properties knolux-redis/build.gradle.kts
git commit -m "build: add knolux-redis submodule build files"
```

---

### Task 6: Verify the build

- [ ] **Step 1: Run the full build**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`. All tests in `KnoluxRedisPropertiesTest`, `KnoluxRedisAutoConfigurationTest`, and `KnoluxRedisHealthIndicatorTest` pass. Integration tests (`KnoluxRedisStandaloneIntegrationTest`, `KnoluxRedisSentinelIntegrationTest`, `KnoluxRedisHealthIndicatorIntegrationTest`) require Docker — they may be skipped if Docker is unavailable.

- [ ] **Step 2: Check that the artifact coordinates are correct**

```bash
./gradlew :knolux-redis:generatePomFileForMavenPublication
cat knolux-redis/build/publications/maven/pom-default.xml
```

Expected pom should contain:
```xml
<groupId>com.knolux</groupId>
<artifactId>knolux-redis</artifactId>
<version>1.0.0</version>
```

- [ ] **Step 3: Check that version override works**

```bash
./gradlew :knolux-redis:generatePomFileForMavenPublication -Pversion=1.2.3
cat knolux-redis/build/publications/maven/pom-default.xml | grep version
```

Expected: `<version>1.2.3</version>` — confirms the publish workflow's `-Pversion` flag will work correctly.

- [ ] **Step 4: If build fails, check for these common issues**

- **`Could not find libs.xxx`**: The version catalog is not being picked up. Confirm `gradle/libs.versions.toml` exists and Gradle version is 7.4+.
- **`Could not get unknown property 'java' for project`**: The `java-library` plugin wasn't applied before the `extensions.configure<JavaPluginExtension>` call. Check the `apply(plugin = ...)` order in root `build.gradle.kts`.
- **`Class not found: DependencyManagementExtension`**: Add `import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension` to the top of root `build.gradle.kts`.

---

### Task 7: Rewrite publish.yml for module-scoped tags

**Files:**
- Rewrite: `.github/workflows/publish.yml`

- [ ] **Step 1: Replace the entire file**

`.github/workflows/publish.yml`:
```yaml
name: Publish

on:
  push:
    tags:
      - '*/v*'    # e.g. knolux-redis/v1.0.1

jobs:
  test-and-publish:
    name: Test & Publish
    runs-on: ubuntu-latest

    permissions:
      contents: read
      packages: write

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: 25
          distribution: temurin

      - uses: gradle/actions/setup-gradle@v4

      - name: Extract module and version from tag
        id: meta
        run: |
          TAG=${GITHUB_REF_NAME}          # knolux-redis/v1.0.1
          MODULE=${TAG%/v*}               # knolux-redis
          VERSION=${TAG#*/v}              # 1.0.1
          echo "module=$MODULE" >> $GITHUB_OUTPUT
          echo "version=$VERSION" >> $GITHUB_OUTPUT

      - name: Run module tests
        run: ./gradlew :${{ steps.meta.outputs.module }}:test

      - name: Publish to GitHub Packages
        run: ./gradlew :${{ steps.meta.outputs.module }}:publish -Pversion=${{ steps.meta.outputs.version }}
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

Release flow:
```bash
git tag knolux-redis/v1.0.1
git push origin knolux-redis/v1.0.1
# → CI tests and publishes :knolux-redis at 1.0.1 only
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/publish.yml
git commit -m "ci: rewrite publish workflow for module-scoped tags"
```

---

### Task 8: Update ci.yml test report path

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Update the test report artifact path**

The only change: test reports now live inside the submodule's `build/` directory. Use a glob to cover all current and future modules.

Change this block in `.github/workflows/ci.yml`:
```yaml
      - name: Upload test report
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-report-java${{ matrix.java }}
          path: build/reports/tests/test/
          retention-days: 7
```

To:
```yaml
      - name: Upload test report
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-report-java${{ matrix.java }}
          path: '**/build/reports/tests/test/'
          retention-days: 7
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: update test report path glob for multi-module layout"
```

---

### Task 9: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Replace CLAUDE.md content**

`CLAUDE.md`:
```markdown
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

- `build.gradle.kts` — declares plugins (with versions, `apply false`), sets `group = "com.knolux"`, and configures all subprojects via `subprojects {}`: Java 25 toolchain, Spring Boot 4.0.5 BOM, `useJUnitPlatform()`, publishing template
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
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for multi-module layout"
```
