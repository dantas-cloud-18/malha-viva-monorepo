package br.malhaviva.core.data.risk

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRiskSignalsTest {
    private fun signals(
        su: Boolean = false,
        tags: String? = "release-keys",
        fingerprint: String? = "google/panther/panther:14/release-keys",
        hardware: String? = "tensor",
        debugger: Boolean = false,
    ) = BasicDeviceRiskSignals(
        fileExists = { su && it == "/system/xbin/su" },
        buildTags = { tags },
        fingerprint = { fingerprint },
        hardware = { hardware },
        debuggerConnected = { debugger },
    ).collect()

    @Test
    fun `aparelho limpo nao gera sinais`() = assertEquals(emptyList<RiskSignal>(), signals())

    @Test
    fun `detecta su test-keys debugger e emulador`() {
        assertEquals(listOf(RiskSignal.ROOT_BINARY), signals(su = true))
        assertEquals(listOf(RiskSignal.TEST_KEYS), signals(tags = "test-keys"))
        assertEquals(listOf(RiskSignal.DEBUGGER_ATTACHED), signals(debugger = true))
        assertEquals(listOf(RiskSignal.EMULATOR), signals(hardware = "ranchu"))
        assertEquals(listOf(RiskSignal.EMULATOR), signals(fingerprint = "generic/sdk_gphone"))
    }

    @Test
    fun `valores nulos nao quebram`() = assertEquals(emptyList<RiskSignal>(), signals(tags = null, fingerprint = null))
}
