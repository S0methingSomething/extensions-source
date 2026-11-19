import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

dependencies {
    implementation(project(":lib:selexpr"))
}

keiyoushi {
    name = "Toonkotsu"
    versionCode = 7
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://toonkotsu.com"
    }

    deeplink {
        path("/title/..*")
        path("/author/..*")
        path("/tag/..*")
        path("/year/..*")
        path("/group/..*")
    }
}
