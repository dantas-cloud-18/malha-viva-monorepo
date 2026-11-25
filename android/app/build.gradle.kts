import java.net.URI
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Base da API: -Pmalhaviva.apiBaseUrl=... ; debug aponta para o host do emulador, release exige https.
val apiBaseUrl: String? = providers.gradleProperty("malhaviva.apiBaseUrl").orNull
// TODO(publicacao): unico lugar do placeholder; troque pelo dominio real (ou passe -Pmalhaviva.apiBaseUrl=https://...).
val releaseApiBaseUrl: String = apiBaseUrl ?: "https://api.malhaviva.example"
val buildsRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
check(!buildsRelease || releaseApiBaseUrl.startsWith("https://")) {
    "malhaviva.apiBaseUrl precisa ser https:// no release (recebido: $releaseApiBaseUrl)"
}

// TLS pinning (somente release). DESLIGADO por padrao: o dominio e os certificados reais ainda nao existem.
// Para ligar: -Pmalhaviva.pinSha256=<spki-b64-atual>,<spki-b64-backup> -Pmalhaviva.pinExpiration=AAAA-MM-DD
// (opcional: -Pmalhaviva.pinDomain=host). Exige pin de backup; veja android/SECURITY-NOTES.md.
val pinSha256: List<String> =
    providers
        .gradleProperty("malhaviva.pinSha256")
        .orNull
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
val pinExpiration: String = providers.gradleProperty("malhaviva.pinExpiration").getOrElse("")
val pinDomain: String =
    providers.gradleProperty("malhaviva.pinDomain").getOrElse(URI(releaseApiBaseUrl).host.orEmpty())

// Assinatura do release: MALHAVIVA_KEYSTORE_PATH, _PASSWORD, _ALIAS e _KEY_PASSWORD, lidas do ambiente ou de
// propriedades do Gradle (~/.gradle/gradle.properties). Nunca versionadas; se faltar qualquer uma, o release sai sem assinatura.
fun signingValue(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

val keystorePath = signingValue("MALHAVIVA_KEYSTORE_PATH")
val keystorePassword = signingValue("MALHAVIVA_KEYSTORE_PASSWORD")
val signKeyAlias = signingValue("MALHAVIVA_KEY_ALIAS")
val signKeyPassword = signingValue("MALHAVIVA_KEY_PASSWORD")
val hasSigning = listOf(keystorePath, keystorePassword, signKeyAlias, signKeyPassword).all { it != null }

abstract class GenerateNetworkSecurityConfig : DefaultTask() {
    @get:Input
    abstract val pins: ListProperty<String>

    @get:Input
    abstract val expiration: Property<String>

    @get:Input
    abstract val domain: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val pinList = pins.get()
        val xml =
            if (pinList.isEmpty()) {
                // Sem pinning configurado: apenas confia na CA do sistema e proibe cleartext.
                buildString {
                    appendLine("<network-security-config>")
                    appendLine("    <base-config cleartextTrafficPermitted=\"false\" />")
                    appendLine("</network-security-config>")
                }
            } else {
                check(pinList.size >= 2) { "pinning ligado exige pelo menos 2 pins (atual + BACKUP)" }
                pinList.forEach {
                    check(runCatching { Base64.getDecoder().decode(it).size == 32 }.getOrDefault(false)) {
                        "pin invalido (esperado SHA-256 de SPKI em base64, 32 bytes): $it"
                    }
                }
                check(Regex("""\d{4}-\d{2}-\d{2}""").matches(expiration.get())) {
                    "pinning ligado exige -Pmalhaviva.pinExpiration=AAAA-MM-DD"
                }
                check(domain.get().isNotEmpty()) { "dominio do pinning vazio (use -Pmalhaviva.pinDomain)" }
                buildString {
                    appendLine("<network-security-config>")
                    appendLine("    <base-config cleartextTrafficPermitted=\"false\" />")
                    appendLine("    <domain-config cleartextTrafficPermitted=\"false\">")
                    appendLine("        <domain includeSubdomains=\"false\">${domain.get()}</domain>")
                    appendLine("        <pin-set expiration=\"${expiration.get()}\">")
                    pinList.forEach { appendLine("            <pin digest=\"SHA-256\">$it</pin>") }
                    appendLine("        </pin-set>")
                    appendLine("    </domain-config>")
                    appendLine("</network-security-config>")
                }
            }
        val file = outputDir.get().asFile.resolve("xml/network_security_config.xml")
        file.parentFile.mkdirs()
        file.writeText(xml)
    }
}

val generateNetSecConfig =
    tasks.register<GenerateNetworkSecurityConfig>("generateReleaseNetworkSecurityConfig") {
        pins.set(pinSha256)
        expiration.set(pinExpiration)
        domain.set(pinDomain)
        outputDir.set(layout.buildDirectory.dir("generated/netsec/release"))
    }

android {
    namespace = "br.malhaviva.app"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "br.malhaviva.app"
        minSdk = 26
        targetSdk = 37
        // Cada envio ao Play exige versionCode maior: -Pmalhaviva.versionCode=N no CI.
        versionCode = providers.gradleProperty("malhaviva.versionCode").map { it.toInt() }.getOrElse(1)
        versionName = providers.gradleProperty("malhaviva.versionName").getOrElse("0.1.0")
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(checkNotNull(keystorePath))
                storePassword = keystorePassword
                keyAlias = signKeyAlias
                keyPassword = signKeyPassword
            }
        }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl ?: "http://10.0.2.2:8080"}\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            // Piloto (-Pmalhaviva.pilot=true): sem Play Integrity, envia o token de teste que o servidor de piloto
            // aceita com MALHAVIVA_DEV_INTEGRITY=true. Build de loja deixa false: nenhum lote sai sem atestacao real.
            buildConfigField(
                "boolean",
                "PILOT_INTEGRITY",
                providers.gradleProperty("malhaviva.pilot").getOrElse("false"),
            )
            isDebuggable = false
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
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateNetSecConfig,
            GenerateNetworkSecurityConfig::outputDir,
        )
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

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.coroutines.test)
}
