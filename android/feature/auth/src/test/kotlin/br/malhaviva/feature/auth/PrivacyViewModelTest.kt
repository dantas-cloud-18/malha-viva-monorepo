package br.malhaviva.feature.auth

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrivacyViewModelTest {
    @get:Rule
    val main = MainRule()

    private val env = TestEnv()
    private val t = env.transport

    private val none =
        """{"policy_version":"2026-11-25","consents":[""" +
            """{"purpose":"location","granted":false,"current_policy_version":"2026-11-25"},""" +
            """{"purpose":"marketing","granted":false,"current_policy_version":"2026-11-25"}]}"""

    private suspend fun opened(first: String = none): PrivacyViewModel {
        env.signIn()
        t.on("GET /me/consents", json(200, first))
        return PrivacyViewModel(env.session, env.privacy).also { it.act(PrivacyIntent.Open) }
    }

    @Test
    fun `nothing starts granted`() =
        runTest {
            val state = opened().state.value
            assertFalse(state.location)
            assertFalse(state.marketing)
            assertFalse(state.busy)
        }

    @Test
    fun `granting location sends the single policy version constant and revoking is one call`() =
        runTest {
            val vm = opened()
            t.on(
                "PUT /me/consents/location",
                json(200, """{"purpose":"location","granted":true,"current_policy_version":"x"}"""),
            )
            t.on("DELETE /me/consents/location", json(204))
            vm.act(PrivacyIntent.Toggle(ConsentPurpose.Location, true))
            assertEquals(
                POLICY_VERSION,
                t.bodyOf(t.sent("PUT /me/consents/location").single()).getString("policy_version"),
            )
            assertTrue(vm.state.value.location)
            assertEquals(AuthMessage.ConsentSaved, vm.state.value.message)
            vm.act(PrivacyIntent.Toggle(ConsentPurpose.Location, false))
            assertEquals(1, t.sent("DELETE /me/consents/location").size)
            assertFalse(vm.state.value.location)
        }

    @Test
    fun `marketing forbidden for minors disables the switch`() =
        runTest {
            val vm = opened()
            t.on("PUT /me/consents/marketing", problem(403, "marketing_forbidden_for_minors"))
            vm.act(PrivacyIntent.Toggle(ConsentPurpose.Marketing, true))
            val state = vm.state.value
            assertTrue(state.marketingForbidden)
            assertFalse(state.marketing)
            assertEquals(AuthMessage.MarketingForbidden, state.message)
        }

    @Test
    fun `policy mismatch asks to update the app`() =
        runTest {
            val vm = opened()
            t.on("PUT /me/consents/location", problem(409, "policy_version_mismatch"))
            vm.act(PrivacyIntent.Toggle(ConsentPurpose.Location, true))
            assertEquals(AuthMessage.PolicyMismatch, vm.state.value.message)
            assertFalse(vm.state.value.location)
        }

    @Test
    fun `loads granted state and history`() =
        runTest {
            val vm =
                opened(
                    """{"policy_version":"2026-11-25","consents":[""" +
                        """{"purpose":"location","granted":true,"current_policy_version":"2026-11-25",""" +
                        """"needs_reconsent":true},""" +
                        """{"purpose":"marketing","granted":false,"current_policy_version":"2026-11-25"}]}""",
                )
            assertTrue(vm.state.value.location)
            assertTrue(vm.state.value.needsReconsent)
            t.on(
                "GET /me/consents/history",
                json(
                    200,
                    """[{"purpose":"location","action":"granted","policy_version":"2026-11-25",""" +
                        """"occurred_at":"2026-11-25T10:00:00Z"}]""",
                ),
            )
            vm.act(PrivacyIntent.ShowHistory)
            assertEquals(PrivacyStep.History, vm.state.value.step)
            assertEquals(
                "granted",
                vm.state.value.history
                    .single()
                    .action,
            )
        }

    @Test
    fun `export keeps the json only until it is consumed`() =
        runTest {
            val vm = opened()
            t.on("GET /me/export", json(200, """{"generated_at":"x","account":{"handle":"@ana"}}"""))
            vm.act(PrivacyIntent.Export)
            val json = vm.state.value.exportJson
            assertNotNull(json)
            assertEquals("@ana", JSONObject(json!!).getJSONObject("account").getString("handle"))
            vm.act(PrivacyIntent.ExportConsumed)
            assertNull(vm.state.value.exportJson)
        }

    @Test
    fun `editing the handle sends PATCH me with the normalized handle`() =
        runTest {
            val vm = opened()
            t.on("PATCH /me", json(200, """{"handle":"@nova","age_bracket":"teen_12_17","status":"active"}"""))
            vm.act(PrivacyIntent.StartEditHandle)
            vm.act(PrivacyIntent.HandleChanged("a"))
            vm.act(PrivacyIntent.SubmitHandle)
            assertTrue("handle invalido nao sai", t.sent("PATCH /me").isEmpty())
            vm.act(PrivacyIntent.HandleChanged("nova"))
            vm.act(PrivacyIntent.SubmitHandle)
            assertEquals("@nova", t.bodyOf(t.sent("PATCH /me").single()).getString("handle"))
            assertEquals(AuthMessage.HandleUpdated, vm.state.value.message)
            assertEquals(PrivacyStep.Main, vm.state.value.step)
            assertTrue("faixa 12-17 esconde o marketing", vm.state.value.marketingForbidden)
        }

    @Test
    fun `handle conflict shows handle taken`() =
        runTest {
            val vm = opened()
            t.on("PATCH /me", problem(409))
            vm.act(PrivacyIntent.StartEditHandle)
            vm.act(PrivacyIntent.HandleChanged("nova"))
            vm.act(PrivacyIntent.SubmitHandle)
            assertEquals(AuthMessage.HandleTaken, vm.state.value.message)
        }

    @Test
    fun `delete needs the password, then clears the session`() =
        runTest {
            val vm = opened()
            t.on("DELETE /me", problem(403, "invalid_password"), json(204))
            t.on("POST /auth/logout", json(204))
            vm.act(PrivacyIntent.StartDelete)
            vm.act(PrivacyIntent.SubmitDelete)
            assertTrue("sem senha nada e enviado", t.sent("DELETE /me").isEmpty())
            vm.act(PrivacyIntent.PasswordChanged("errada-123"))
            vm.act(PrivacyIntent.SubmitDelete)
            assertEquals(AuthMessage.InvalidPassword, vm.state.value.message)
            assertFalse(vm.state.value.deleted)
            vm.act(PrivacyIntent.SubmitDelete)
            assertEquals("errada-123", t.bodyOf(t.sent("DELETE /me").last()).getString("current_password"))
            val done = vm.state.value
            assertTrue(done.deleted)
            assertEquals("", done.password)
            assertEquals(SessionState.LoggedOut, env.session.state.value)
        }
}
