plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "br.malhaviva.feature.trains"
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.core.designsystem)
    // So para reaproveitar o CorridorParser (tracado e estacoes da malha); sem acoplamento de UI.
    implementation(projects.feature.tracking)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.osmdroid)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.coroutines.test)
}
