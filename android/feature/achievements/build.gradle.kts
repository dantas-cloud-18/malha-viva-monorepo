plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "br.malhaviva.feature.achievements"
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.ui)
    implementation(projects.core.designsystem)
}
