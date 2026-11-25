import com.android.build.api.dsl.LibraryExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    // Lockfiles por modulo, lidos pelo osv-scanner no CI. Atualizar apos mudar dependencias:
    // .\gradlew.bat resolveAndLockAll --write-locks
    // So os classpaths (debug/release e testes): configuracoes internas do AGP sao ambiguas fora de uma variante.
    val lockedClasspath = Regex(".*([Cc]ompileClasspath|[Rr]untimeClasspath)")
    configurations.matching { it.name.matches(lockedClasspath) && !it.name.contains("AndroidTest") }
        .configureEach { resolutionStrategy.activateDependencyLocking() }
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("resolve configuracoes em tempo de execucao")
        doLast {
            configurations.filter { it.isCanBeResolved && it.name.matches(lockedClasspath) && !it.name.contains("AndroidTest") }
                // grafo apenas: artefatos de teste unitario do AGP so resolvem via artifactView da variante
                .forEach { it.incoming.resolutionResult.allComponents }
        }
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }

    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk { version = release(37) { minorApiLevel = 1 } }
            defaultConfig.minSdk = 26
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}
