plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "br.malhaviva.core.data"
}

dependencies {
    implementation(projects.core.domain)

    testImplementation(libs.junit)
}
