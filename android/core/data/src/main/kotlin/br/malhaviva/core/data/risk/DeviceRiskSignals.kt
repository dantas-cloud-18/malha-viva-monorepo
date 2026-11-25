package br.malhaviva.core.data.risk

import android.os.Build
import android.os.Debug
import java.io.File

enum class RiskSignal { ROOT_BINARY, TEST_KEYS, DEBUGGER_ATTACHED, EMULATOR }

/**
 * Sinais de risco do aparelho. SINAL FRACO E CONTORNAVEL: nunca bloqueia o usuario, apenas informa
 * (ex.: reduzir confianca de antifraude). Atestacao real e o Play Integrity (NAO integrado nesta fase).
 * Nenhum dado sai do aparelho.
 */
interface DeviceRiskSignals {
    fun collect(): List<RiskSignal>
}

class BasicDeviceRiskSignals(
    private val fileExists: (String) -> Boolean = { File(it).exists() },
    private val buildTags: () -> String? = { Build.TAGS },
    private val fingerprint: () -> String? = { Build.FINGERPRINT },
    private val hardware: () -> String? = { Build.HARDWARE },
    private val debuggerConnected: () -> Boolean = { Debug.isDebuggerConnected() },
) : DeviceRiskSignals {
    override fun collect(): List<RiskSignal> =
        buildList {
            if (SU_PATHS.any(fileExists)) add(RiskSignal.ROOT_BINARY)
            if (buildTags()?.contains("test-keys") == true) add(RiskSignal.TEST_KEYS)
            if (debuggerConnected()) add(RiskSignal.DEBUGGER_ATTACHED)
            if (looksLikeEmulator()) add(RiskSignal.EMULATOR)
        }

    private fun looksLikeEmulator(): Boolean {
        val fp = fingerprint().orEmpty()
        val hw = hardware().orEmpty()
        return fp.startsWith("generic") || fp.contains("emulator") || hw == "goldfish" || hw == "ranchu"
    }

    private companion object {
        val SU_PATHS =
            listOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/system/sd/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/su/bin/su",
                "/system/app/Superuser.apk",
            )
    }
}
