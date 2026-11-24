plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "br.malhaviva.core.designsystem"
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.material3)
    implementation(libs.compose.ui)

    testImplementation(libs.junit)
}
