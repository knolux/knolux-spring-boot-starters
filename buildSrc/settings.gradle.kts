// buildSrc 是獨立的 build，預設看不到主專案的 version catalog。
// 明確接上 ../gradle/libs.versions.toml，讓依賴版號仍集中於單一來源
// （憲章「技術與相容性約束」：依賴版本集中於 gradle/libs.versions.toml）。
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
