# Design: Javadoc + GitHub Pages

**Date:** 2026-04-16  
**Status:** Approved

## Goal

Generate Traditional Chinese Javadoc for all public classes in `:knolux-redis-spring-boot-starter` and publish it to GitHub Pages automatically on every module release tag.

## Approach

Standard Javadoc (JDK built-in) via Gradle, deployed with GitHub's official `actions/deploy-pages`. No extra branch needed — GitHub manages the Pages content.

## Javadoc Content

All three public classes get complete Traditional Chinese Javadoc:

| Class | Status | Action |
|-------|--------|--------|
| `KnoluxRedisProperties` | Partial Chinese Javadoc | Complete getter/setter docs |
| `KnoluxRedisAutoConfiguration` | None | Add class-level + `@Bean` method docs |
| `KnoluxRedisHealthIndicator` | None | Add class-level + `health()` method docs |

## Gradle Configuration

Add to `subprojects {}` block in root `build.gradle.kts`:

```kotlin
tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        charSet("UTF-8")
        encoding("UTF-8")
        docEncoding("UTF-8")
        locale("zh_TW")
        addStringOption("Xdoclint:none", "-quiet")
    }
}
```

`Xdoclint:none` suppresses warnings that would fail the build on imperfect Javadoc.

## GitHub Actions Workflow

New file: `.github/workflows/javadoc.yml`

- **Trigger:** `push` to tags matching `*/v*` (same pattern as `publish.yml`)
- **Module extraction:** Same logic as `publish.yml` — parses `knolux-redis-spring-boot-starter/v1.0.1` → `MODULE=knolux-redis-spring-boot-starter`
- **Steps:**
  1. Checkout, setup Java 25 (temurin), setup Gradle
  2. Extract module name from tag
  3. `./gradlew :$MODULE:javadoc`
  4. `actions/configure-pages@v4`
  5. `actions/upload-pages-artifact@v3` — uploads `$MODULE/build/docs/javadoc`
  6. `actions/deploy-pages@v4` — deploys to Pages
- **Permissions:** `contents: read`, `pages: write`, `id-token: write`
- **Environment:** `github-pages` (required by `actions/deploy-pages`)
- **Concurrency:** `group: pages`, `cancel-in-progress: false`

## Published URL

`https://knolux.github.io/knolux-spring-boot-starters/`

## Manual Setup Required (one-time)

GitHub repo → **Settings** → **Pages** → **Source** → set to **GitHub Actions**

## Out of Scope

- Multi-module index page (single module for now; revisit when second module is added)
- Custom CSS/styling (standard Javadoc HTML is sufficient)
- Versioned docs (always reflects latest release)
