# Spring Boot Starter Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the `knolux-redis` module to `knolux-redis-spring-boot-starter` throughout all Gradle config, workflows, and documentation — Java code untouched.

**Architecture:** Directory rename via `git mv` drives the change; Gradle's module name derives from directory. All other edits are text substitutions in config and docs files.

**Tech Stack:** Gradle (Kotlin DSL), GitHub Actions, Markdown

---

### Task 1: Rename module directory and update Gradle settings

**Files:**
- Rename: `knolux-redis/` → `knolux-redis-spring-boot-starter/`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Rename the module directory with git**

```bash
git mv knolux-redis knolux-redis-spring-boot-starter
```

Expected output: no output (silent success). Verify with:
```bash
ls
```
You should see `knolux-redis-spring-boot-starter/` and no `knolux-redis/`.

- [ ] **Step 2: Update `settings.gradle.kts`**

Replace the entire file content with:

```kotlin
rootProject.name = "knolux-spring-boot-starters"

include(":knolux-redis-spring-boot-starter")
```

- [ ] **Step 3: Verify the build compiles and tests pass**

```bash
./gradlew :knolux-redis-spring-boot-starter:build
```

Expected: `BUILD SUCCESSFUL`. If Gradle reports "project not found", check that `settings.gradle.kts` has the correct include name.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts
git commit -m "refactor: rename module to knolux-redis-spring-boot-starter"
```

---

### Task 2: Update GitHub Packages URLs in root `build.gradle.kts`

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Update the POM project URL**

In `build.gradle.kts`, find line:
```kotlin
url.set("https://github.com/knolux/knolux-starter")
```
Replace with:
```kotlin
url.set("https://github.com/knolux/knolux-spring-boot-starters")
```

- [ ] **Step 2: Update the GitHub Packages repository URL**

In the same file, find line:
```kotlin
url = uri("https://maven.pkg.github.com/knolux/knolux-starter")
```
Replace with:
```kotlin
url = uri("https://maven.pkg.github.com/knolux/knolux-spring-boot-starters")
```

- [ ] **Step 3: Verify Gradle still resolves correctly**

```bash
./gradlew :knolux-redis-spring-boot-starter:generatePomFileForMavenPublication
```

Expected: `BUILD SUCCESSFUL`. Check the generated POM at:
`knolux-redis-spring-boot-starter/build/publications/maven/pom-default.xml`
and confirm the `<url>` field shows `knolux-spring-boot-starters`.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "refactor: update GitHub Packages URLs to knolux-spring-boot-starters"
```

---

### Task 3: Update GitHub Actions publish workflow

**Files:**
- Modify: `.github/workflows/publish.yml`

- [ ] **Step 1: Update the tag pattern example comment**

In `.github/workflows/publish.yml`, find line:
```yaml
      - '*/v*'    # e.g. knolux-redis/v1.0.1
```
Replace with:
```yaml
      - '*/v*'    # e.g. knolux-redis-spring-boot-starter/v1.0.1
```

No other changes needed — the extraction logic (`TAG%/v*` and `TAG#*/v`) handles any module name.

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/publish.yml
git commit -m "docs: update publish workflow tag example to new module name"
```

---

### Task 4: Update root `README.md`

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the Modules table**

Find:
```markdown
| [knolux-redis](./knolux-redis/README.md) | `com.knolux:knolux-redis` | Redis starter — Sentinel & Standalone via `REDIS_URL` |
```
Replace with:
```markdown
| [knolux-redis-spring-boot-starter](./knolux-redis-spring-boot-starter/README.md) | `com.knolux:knolux-redis-spring-boot-starter` | Redis starter — Sentinel & Standalone via `REDIS_URL` |
```

- [ ] **Step 2: Update Gradle dependency snippet**

Find:
```kotlin
    implementation("com.knolux:knolux-redis:1.0.0")
```
Replace with:
```kotlin
    implementation("com.knolux:knolux-redis-spring-boot-starter:1.0.0")
```

- [ ] **Step 3: Update Maven dependency snippet**

Find:
```xml
    <artifactId>knolux-redis</artifactId>
```
Replace with:
```xml
    <artifactId>knolux-redis-spring-boot-starter</artifactId>
```

- [ ] **Step 4: Update release tag examples**

Find:
```bash
git tag knolux-redis/v1.0.1
git push origin knolux-redis/v1.0.1
```
Replace with:
```bash
git tag knolux-redis-spring-boot-starter/v1.0.1
git push origin knolux-redis-spring-boot-starter/v1.0.1
```

- [ ] **Step 5: Update GitHub Packages repository URL in README**

Find:
```kotlin
        url = uri("https://maven.pkg.github.com/knolux/knolux-starters")
```
Replace with:
```kotlin
        url = uri("https://maven.pkg.github.com/knolux/knolux-spring-boot-starters")
```

Also find the Maven `<repository>` block:
```xml
    <url>https://maven.pkg.github.com/knolux/knolux-starters</url>
```
Replace with:
```xml
    <url>https://maven.pkg.github.com/knolux/knolux-spring-boot-starters</url>
```

- [ ] **Step 6: Commit**

```bash
git add README.md
git commit -m "docs: update root README for new module name and repo URL"
```

---

### Task 5: Update module `README.md`

**Files:**
- Modify: `knolux-redis-spring-boot-starter/README.md`

- [ ] **Step 1: Update the title**

Find:
```markdown
# knolux-redis
```
Replace with:
```markdown
# knolux-redis-spring-boot-starter
```

- [ ] **Step 2: Update Gradle dependency snippet**

Find:
```kotlin
    implementation("com.knolux:knolux-redis:1.0.0")
```
Replace with:
```kotlin
    implementation("com.knolux:knolux-redis-spring-boot-starter:1.0.0")
```

- [ ] **Step 3: Update Maven dependency snippet**

Find:
```xml
    <artifactId>knolux-redis</artifactId>
```
Replace with:
```xml
    <artifactId>knolux-redis-spring-boot-starter</artifactId>
```

- [ ] **Step 4: Update the Running Tests section**

Find:
```bash
./gradlew :knolux-redis:test
```
Replace with:
```bash
./gradlew :knolux-redis-spring-boot-starter:test
```

- [ ] **Step 5: Commit**

```bash
git add knolux-redis-spring-boot-starter/README.md
git commit -m "docs: update module README for new artifact name"
```

---

### Task 6: Update `CLAUDE.md`

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the Current modules list**

Find:
```markdown
- `:knolux-redis` — Redis starter supporting Standalone (`redis://`) and Sentinel (`redis-sentinel://`) modes
```
Replace with:
```markdown
- `:knolux-redis-spring-boot-starter` — Redis starter supporting Standalone (`redis://`) and Sentinel (`redis-sentinel://`) modes
```

- [ ] **Step 2: Update all Gradle commands**

Find:
```bash
./gradlew :knolux-redis:test
```
Replace with:
```bash
./gradlew :knolux-redis-spring-boot-starter:test
```

Find:
```bash
./gradlew :knolux-redis:test --tests "com.knolux.redis.KnoluxRedisAutoConfigurationTest"
```
Replace with:
```bash
./gradlew :knolux-redis-spring-boot-starter:test --tests "com.knolux.redis.KnoluxRedisAutoConfigurationTest"
```

Find:
```bash
./gradlew :knolux-redis:test --tests "com.knolux.redis.KnoluxRedisAutoConfigurationTest.standalone_shouldCreateAllBeans"
```
Replace with:
```bash
./gradlew :knolux-redis-spring-boot-starter:test --tests "com.knolux.redis.KnoluxRedisAutoConfigurationTest.standalone_shouldCreateAllBeans"
```

Find:
```bash
./gradlew :knolux-redis:publish -Pversion=1.0.1
```
Replace with:
```bash
./gradlew :knolux-redis-spring-boot-starter:publish -Pversion=1.0.1
```

- [ ] **Step 3: Update the Publishing tag examples**

Find:
```bash
git tag knolux-redis/v1.0.1
git push origin knolux-redis/v1.0.1
```
Replace with:
```bash
git tag knolux-redis-spring-boot-starter/v1.0.1
git push origin knolux-redis-spring-boot-starter/v1.0.1
```

- [ ] **Step 4: Update the Architecture section heading**

Find:
```markdown
### :knolux-redis
```
Replace with:
```markdown
### :knolux-redis-spring-boot-starter
```

- [ ] **Step 5: Update test report path**

Find:
```
Test reports: `<module>/build/reports/tests/test/`
```
This is already generic (`<module>`), so no change needed here.

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for new module name"
```

---

### Task 7: Final verification

- [ ] **Step 1: Run the full test suite**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL` with all unit tests passing. Integration tests require Docker — if Docker is not available locally, that is expected and not a blocker.

- [ ] **Step 2: Confirm no remaining old references**

```bash
grep -r "knolux-redis" . \
  --include="*.kts" \
  --include="*.toml" \
  --include="*.yml" \
  --include="*.md" \
  --exclude-dir=".git" \
  --exclude-dir="build"
```

Any result containing `knolux-redis` that is NOT `knolux-redis-spring-boot-starter` is a missed reference that needs fixing.

- [ ] **Step 3: GitHub repo rename (manual)**

Go to GitHub → your repository → **Settings** → **General** → rename from the current name to `knolux-spring-boot-starters`. This must be done in the browser; it cannot be done from code.
