import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.mynora.singverse.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mynora.singverse.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = providers.gradleProperty("singverseVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("singverseVersionName").orElse("0.1.0").get()
    }
    if (providers.gradleProperty("singverseSignRelease").orNull == "true") {
        val signingValues = listOf("SINGVERSE_TV_KEYSTORE", "SINGVERSE_TV_STORE_PASSWORD", "SINGVERSE_TV_KEY_ALIAS", "SINGVERSE_TV_KEY_PASSWORD").associateWith { System.getenv(it) ?: error("Missing release signing environment: $it") }
        signingConfigs.create("production") {
            storeFile = file(signingValues.getValue("SINGVERSE_TV_KEYSTORE"))
            storePassword = signingValues.getValue("SINGVERSE_TV_STORE_PASSWORD")
            keyAlias = signingValues.getValue("SINGVERSE_TV_KEY_ALIAS")
            keyPassword = signingValues.getValue("SINGVERSE_TV_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("production")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    testImplementation("junit:junit:4.13.2")
    implementation("com.google.zxing:core:3.5.3")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.0.0")
}

// Publish only explicitly signed builds; keys stay in the release environment.
tasks.register("prepareTvRelease") {
    dependsOn("assembleRelease")
    doLast {
        check(providers.gradleProperty("singverseSignRelease").orNull == "true") { "Release signing must be explicitly configured" }
        val code=android.defaultConfig.versionCode ?: error("versionCode missing")
        val name=android.defaultConfig.versionName ?: error("versionName missing")
        check(code>0 && name.matches(Regex("[A-Za-z0-9.+_-]{1,80}"))) { "Invalid release version" }
        val output=layout.buildDirectory.dir("tv-release").get().asFile.apply { mkdirs() }
        layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile.copyTo(File(output,"singverse-tv.apk"),overwrite=true)
        File(output,"release.json").writeText("""{"versionCode":$code,"versionName":"$name"}"""+"\n")
    }
}
