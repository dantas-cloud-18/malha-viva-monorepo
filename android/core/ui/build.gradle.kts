plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "br.malhaviva.core.ui"
}

dependencies {
    api(libs.androidx.lifecycle.viewmodel)
    api(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
}
