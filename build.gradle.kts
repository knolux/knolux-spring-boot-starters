plugins {
    `java-library`
    `maven-publish`
    id("org.springframework.boot") version "4.0.5" apply false
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.knolux"
version = "1.0.0"
description = "Redis Starter for Spring Boot, Support Sentinel and Directly"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.5")
    }
}

dependencies {
    // Starter 核心
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Actuator 健康檢查（compileOnly：使用端自行決定是否引入）
    compileOnly("org.springframework.boot:spring-boot-starter-actuator")

    // IDE 提示（application.yml 自動補全）
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // 測試
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Knolux Redis Starter")
                description.set("Redis Starter for Spring Boot, Support Sentinel and Directly")
            }
        }
    }
}