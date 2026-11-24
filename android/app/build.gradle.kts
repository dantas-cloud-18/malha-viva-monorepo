plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Base da API: -Pmalhaviva.apiBaseUrl=... ; debug aponta para o host do emulador, release exige https.
val apiBaseUrl: String? = providers.gradleProperty("malhaviva.apiBaseUrl").orNull
val releaseApiBaseUrl: String = apiBaseUrl ?: "https://api.malhaviva.invalid"
val buildsRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
check(!buildsRelease || releaseApiBaseUrl.startsWith("https://")) {
    "malhaviva.apiBaseUrl precisa ser https:// no release (recebido: $releaseApiBaseUrl)"
}

android {
    namespace = "br.malhaviva.app"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "br.malhaviva.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl ?: "http://10.0.2.2:8080"}\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.core.designsystem)
    implementation(projects.feature.auth)
    implementation(projects.feature.trains)
    implementation(projects.feature.stations)
    implementation(projects.feature.tracking)
    implementation(projects.feature.alerts)
    implementation(projects.feature.privacy)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.achievements)
    implementation(projects.feature.widgets)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
