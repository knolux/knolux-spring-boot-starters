# Multi-Module Restructure Design

**Date:** 2026-04-15  
**Project:** knolux-redis-starter → knolux-starters  
**Status:** Approved

---

## Goal

Convert the current single-module project into a Gradle multi-module monorepo. The root project (`knolux-starters`) acts as an aggregator and never publishes an artifact. The existing Redis starter becomes the first submodule (`:knolux-redis`). Future starters (e.g., `:knolux-kafka`) are added by creating a directory and one `include()` line.

---

## Directory Structure

```
knolux-starters/                        ← root (no artifact published)
├── settings.gradle.kts                 ← rootProject.name = "knolux-starters", include(":knolux-redis")
├── build.gradle.kts                    ← shared config: toolchain, BOM, publishing template
├── gradle/
│   └── libs.versions.toml              ← Version Catalog (all dependency versions)
├── CLAUDE.md
├── README.md
│
└── knolux-redis/                       ← submodule :knolux-redis
    ├── build.gradle.kts                ← description + module-specific deps (no version here)
    ├── gradle.properties               ← version=1.0.0 (overridable via -Pversion)
    ├── README.md
    └── src/
        ├── main/java/com/knolux/redis/
        │   ├── KnoluxRedisAutoConfiguration.java
        │   ├── KnoluxRedisHealthIndicator.java
        │   └── KnoluxRedisProperties.java
        ├── main/resources/
        │   ├── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
        │   └── application.yaml
        └── test/java/com/knolux/redis/
            ├── KnoluxRedisAutoConfigurationTest.java
            ├── KnoluxRedisHealthIndicatorTest.java
            ├── KnoluxRedisHealthIndicatorIntegrationTest.java
            ├── KnoluxRedisPropertiesTest.java
            ├── KnoluxRedisSentinelIntegrationTest.java
            └── KnoluxRedisStandaloneIntegrationTest.java
```

**Migration:** `src/` at root moves wholesale into `knolux-redis/src/`. Root no longer has a `src/` directory.

---

## Root `build.gradle.kts`

Responsibilities: declare plugins (with versions), set shared `group`, apply shared config to all subprojects via `subprojects {}`.

```kotlin
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

    java {
        toolchain { languageVersion = JavaLanguageVersion.of(25) }
        withSourcesJar()
    }

    dependencyManagement {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.5")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                versionMapping {
                    usage("java-api") { fromResolutionOf("runtimeClasspath") }
                    usage("java-runtime") { fromResolutionResult() }
                }
                pom {
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

**Key rule:** `version` is NOT set in root or in submodule `build.gradle.kts`. Each submodule declares its version in its own `gradle.properties` so the publish workflow can override it with `-Pversion=X`.

---

## `gradle/libs.versions.toml`

```toml
[versions]
spring-boot                  = "4.0.5"
spring-dependency-management = "1.1.7"
testcontainers-redis         = "2.2.2"

[libraries]
spring-boot-autoconfigure            = { module = "org.springframework.boot:spring-boot-autoconfigure" }
spring-boot-starter-data-redis       = { module = "org.springframework.boot:spring-boot-starter-data-redis" }
spring-boot-starter-actuator         = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-boot-configuration-processor  = { module = "org.springframework.boot:spring-boot-configuration-processor" }
spring-boot-starter-test             = { module = "org.springframework.boot:spring-boot-starter-test" }
spring-boot-testcontainers           = { module = "org.springframework.boot:spring-boot-testcontainers" }
testcontainers-redis                 = { module = "com.redis:testcontainers-redis", version.ref = "testcontainers-redis" }
junit-platform-launcher              = { module = "org.junit.platform:junit-platform-launcher" }

[plugins]
spring-boot                  = { id = "org.springframework.boot",       version.ref = "spring-boot" }
spring-dependency-management = { id = "io.spring.dependency-management", version.ref = "spring-dependency-management" }
```

Spring Boot-managed libraries omit their version (BOM handles it). Only out-of-BOM libraries (e.g., `testcontainers-redis`) carry an explicit version reference.

---

## `knolux-redis/gradle.properties`

```properties
version=1.0.0
```

Version lives here — not in `build.gradle.kts` — so that the publish workflow's `-Pversion=X` flag can override it. Gradle's `-P` flag overrides `gradle.properties` values; a hardcoded `version = "..."` in the build script would not be overridable.

## `knolux-redis/build.gradle.kts`

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

Only `description` and module-specific dependencies. Version is in `gradle.properties`; everything else is inherited from root.

---

## `settings.gradle.kts`

```kotlin
rootProject.name = "knolux-starters"

include(":knolux-redis")
```

Adding a new module in future: create `knolux-foo/` directory + append `include(":knolux-foo")` here.

---

## CI/CD — GitHub Workflows

### `ci.yml` (unchanged logic)

Triggers on push/PR to `main` and `dev`. `./gradlew test` automatically runs all submodule tests — no changes needed to the test step.

### `publish.yml` (rewritten for module-scoped tags)

**Tag format:** `knolux-redis/v1.0.1`

```yaml
on:
  push:
    tags:
      - '*/v*'    # matches knolux-redis/v1.0.1, knolux-kafka/v2.0.0, etc.

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

      - name: Extract module and version
        id: meta
        run: |
          TAG=${GITHUB_REF_NAME}
          MODULE=${TAG%/v*}
          VERSION=${TAG#*/v}
          echo "module=$MODULE" >> $GITHUB_OUTPUT
          echo "version=$VERSION" >> $GITHUB_OUTPUT

      - name: Run tests
        run: ./gradlew :${{ steps.meta.outputs.module }}:test

      - name: Publish
        run: ./gradlew :${{ steps.meta.outputs.module }}:publish -Pversion=${{ steps.meta.outputs.version }}
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

**Release flow:**
```bash
git tag knolux-redis/v1.0.1
git push origin knolux-redis/v1.0.1
# → tests and publishes :knolux-redis only
```

---

## What Does NOT Change

- Package names: `com.knolux.redis` unchanged
- Maven coordinates: `com.knolux:knolux-redis:x.y.z` unchanged
- All Java source files: moved in place, no edits needed
- `KnoluxRedisAutoConfiguration`, `KnoluxRedisProperties`, `KnoluxRedisHealthIndicator`: untouched
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: untouched
