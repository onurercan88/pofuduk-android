import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing (upload key) comes from `keystore.properties` in the repo root — never committed:
 *   storeFile=../pofuduk-upload.jks
 *   storePassword=…
 *   keyAlias=pofuduk-upload
 *   keyPassword=…
 * Without it `bundleRelease` produces an unsigned bundle (enough for CI checks).
 */
val keystoreProperties = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

/**
 * Apple Weather (WeatherKit) proxy, from `local.properties` or a Gradle property — never committed:
 *   pofuduk.weatherkitProxyUrl=https://pofuduk-weatherkit.<account>.workers.dev
 *   pofuduk.weatherkitAppKey=…
 * Empty → the app uses Open-Meteo only (CI builds, forks).
 */
val localProperties = rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}
fun buildSetting(name: String): String =
    (providers.gradleProperty(name).orNull ?: localProperties?.getProperty(name) ?: "").trim()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.pofuduk.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pofuduk.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        buildConfigField("String", "WEATHERKIT_PROXY_URL", quoted(buildSetting("pofuduk.weatherkitProxyUrl")))
        buildConfigField("String", "WEATHERKIT_APP_KEY", quoted(buildSetting("pofuduk.weatherkitAppKey")))
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Periodic background forecast refresh (survives reboots, respects battery/network constraints).
    implementation(libs.androidx.work.runtime)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}
