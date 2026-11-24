plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "br.malhaviva.feature.tracking"
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.ui)
    implementation(projects.core.designsystem)
}
