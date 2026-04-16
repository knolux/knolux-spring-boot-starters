# Design: Spring Boot Starter Naming Refactor

**Date:** 2026-04-16  
**Status:** Approved

## Goal

Rename the project artifacts and module directories to follow the Spring Boot community starter naming convention: `{brand}-{feature}-spring-boot-starter`.

## Chosen Approach: Option A (Minimal, artifact-level only)

Only the Gradle artifact ID, module directory, and related config/docs are renamed. Java packages, class names, and property prefixes are untouched.

## Naming Changes

| Layer | Before | After |
|-------|--------|-------|
| Root project name (`settings.gradle.kts`) | `knolux-starters` | `knolux-spring-boot-starters` |
| Module directory | `knolux-redis/` | `knolux-redis-spring-boot-starter/` |
| Maven artifact ID | `com.knolux:knolux-redis` | `com.knolux:knolux-redis-spring-boot-starter` |
| Java package | `com.knolux.redis` | **unchanged** |
| Class names | `KnoluxRedisAutoConfiguration`, etc. | **unchanged** |
| Config property prefix | `knolux.redis` | **unchanged** |
| GitHub Packages repo URL | `knolux/knolux-starter` | `knolux/knolux-spring-boot-starters` |
| Publish tag format | `knolux-redis/v*` | `knolux-redis-spring-boot-starter/v*` |

## Files to Change

### `settings.gradle.kts`
- `rootProject.name = "knolux-spring-boot-starters"`
- `include(":knolux-redis-spring-boot-starter")`

### `build.gradle.kts`
- POM `url` and GitHub Packages `url`: replace `knolux-starter` with `knolux-spring-boot-starters`

### Module directory
- Rename `knolux-redis/` → `knolux-redis-spring-boot-starter/`
- Internal files (`build.gradle.kts`, `gradle.properties`, `src/`) move with the directory; no content changes

### `.github/workflows/publish.yml`
- Tag pattern: `knolux-redis-spring-boot-starter/v*`
- Extract logic already handles arbitrary module names — no code logic changes needed

### `.github/workflows/ci.yml`
- No changes (uses `./gradlew test`, module-name agnostic)

### `README.md` and `knolux-redis-spring-boot-starter/README.md`
- Update artifact ID references to `com.knolux:knolux-redis-spring-boot-starter`
- Update module name references
- Update tag examples: `git tag knolux-redis-spring-boot-starter/v1.0.1`

### `CLAUDE.md`
- Update module name in commands (`:knolux-redis:` → `:knolux-redis-spring-boot-starter:`)
- Update module directory path references

## Out of Scope

- Java package names (unchanged)
- Class names (unchanged)
- `knolux.redis` config property prefix (unchanged)
- GitHub repo rename — **must be done manually on GitHub website**: rename repo to `knolux-spring-boot-starters`
- Local working directory name (`knolux-redis-starter/`) — optional, no functional impact

## GitHub Repo Manual Step

Before or after the code changes, go to GitHub → repository Settings → rename from current name to `knolux-spring-boot-starters`.
