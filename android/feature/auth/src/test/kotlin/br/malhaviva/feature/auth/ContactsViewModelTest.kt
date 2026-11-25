package br.malhaviva.feature.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ContactsViewModelTest {
    @get:Rule
    val main = MainRule()

    private val env = TestEnv()
    private val t = env.transport

    private val none = """{"contacts_verified":{"phone":false,"email":true},"pending_changes":[]}"""

    private suspend fun opened(first: String = none): ContactsViewModel {
        env.signIn()
        t.on("GET /me/contacts", json(200, first))
        return ContactsViewModel(env.session, env.account).also { it.act(ContactsIntent.Open) }
    }

    @Test
    fun `open lists verification flags and pending changes`() =
        runTest {
            val vm =
                opened(
                    """{"contacts_verified":{"phone":true,"email":false},"pending_changes":""" +
                        """[{"id":"c1","channel":"phone","effective_at":"2026-12-01T10:00:00Z","confirmed":false}]}""",
                )
            val state = vm.state.value
            assertTrue(state.phoneVerified)
            assertFalse(state.emailVerified)
            assertEquals("c1", state.pending.single().id)
            assertFalse(state.busy)
        }

    @Test
    fun `adding a new contact asks the password, then verifies the otp with the password`() =
        runTest {
            val vm = opened()
            t.on("POST /me/contacts", json(202, """{"status":"verification_sent"}"""))
            t.on("POST /me/contacts/confirm", json(204))
            t.on(
                "GET /me/contacts",
                json(200, """{"contacts_verified":{"phone":true,"email":true},"pending_changes":[]}"""),
            )
            vm.act(ContactsIntent.StartAdd(ContactChannel.Phone))
            vm.act(ContactsIntent.ValueChanged("+55 (11) 99999-8888"))
            vm.act(ContactsIntent.SubmitAdd)
            assertTrue("sem senha nada e enviado", t.sent("POST /me/contacts").isEmpty())
            vm.act(ContactsIntent.PasswordChanged("senha-longa-1"))
            vm.act(ContactsIntent.SubmitAdd)
            val sent = t.bodyOf(t.sent("POST /me/contacts").single())
            assertEquals("phone", sent.getString("channel"))
            assertEquals("+5511999998888", sent.getString("value"))
            assertEquals("senha-longa-1", sent.getString("current_password"))
            assertEquals(ContactsStep.Confirm, vm.state.value.step)
            assertEquals(AuthMessage.ContactSent, vm.state.value.message)

            vm.act(ContactsIntent.CodeChanged("123456"))
            vm.act(ContactsIntent.SubmitConfirm)
            val confirm = t.bodyOf(t.sent("POST /me/contacts/confirm").single())
            assertEquals("123456", confirm.getString("code"))
            assertEquals("senha-longa-1", confirm.getString("current_password"))
            assertFalse(confirm.has("change_id"))
            val done = vm.state.value
            assertEquals(ContactsStep.List, done.step)
            assertEquals(AuthMessage.ContactConfirmed, done.message)
            assertEquals("", done.password)
            assertEquals("", done.code)
            assertTrue(done.phoneVerified)
        }

    @Test
    fun `changing a verified contact keeps a pending change that confirms without the password`() =
        runTest {
            val vm = opened()
            t.on(
                "POST /me/contacts",
                json(202, """{"status":"change_pending","change_id":"c9","effective_at":"2026-12-02T10:00:00Z"}"""),
            )
            t.on("POST /me/contacts/confirm", json(204))
            t.on("GET /me/contacts", json(200, none))
            vm.act(ContactsIntent.StartAdd(ContactChannel.Email))
            vm.act(ContactsIntent.ValueChanged("novo@example.com"))
            vm.act(ContactsIntent.PasswordChanged("senha-longa-1"))
            vm.act(ContactsIntent.SubmitAdd)
            val pending = vm.state.value
            assertEquals("c9", pending.changeId)
            assertEquals("", pending.password)
            assertEquals(AuthMessage.ContactChangePending, pending.message)
            vm.act(ContactsIntent.CodeChanged("654321"))
            vm.act(ContactsIntent.SubmitConfirm)
            val confirm = t.bodyOf(t.sent("POST /me/contacts/confirm").single())
            assertEquals("c9", confirm.getString("change_id"))
            assertFalse(confirm.has("current_password"))
            assertEquals(AuthMessage.ContactChangePending, vm.state.value.message)
        }

    @Test
    fun `cancel removes the pending change`() =
        runTest {
            val vm =
                opened(
                    """{"contacts_verified":{"phone":true,"email":true},"pending_changes":""" +
                        """[{"id":"c1","channel":"email","effective_at":"x","confirmed":true}]}""",
                )
            t.on("DELETE /me/contact-changes/c1", json(204))
            vm.act(ContactsIntent.Cancel("c1"))
            assertTrue(
                vm.state.value.pending
                    .isEmpty(),
            )
            assertEquals(AuthMessage.ContactChangeCanceled, vm.state.value.message)
        }

    @Test
    fun `wrong current password and rate limit show their messages`() =
        runTest {
            val vm = opened()
            t.on(
                "POST /me/contacts",
                problem(403, "invalid_password"),
                problem(
                    429,
                    headers =
                        mapOf(
                            "retry-after" to "20",
                        ),
                ),
            )
            vm.act(ContactsIntent.StartAdd(ContactChannel.Email))
            vm.act(ContactsIntent.ValueChanged("a@example.com"))
            vm.act(ContactsIntent.PasswordChanged("senha-errada-1"))
            vm.act(ContactsIntent.SubmitAdd)
            assertEquals(AuthMessage.InvalidPassword, vm.state.value.message)
            assertEquals(ContactsStep.Add, vm.state.value.step)
            vm.act(ContactsIntent.SubmitAdd)
            assertEquals(AuthMessage.RateLimited, vm.state.value.message)
            assertEquals(20L, vm.state.value.retryAfterSeconds)
        }

    @Test
    fun `phone and email values are validated before sending`() {
        val phone = ContactsState(channel = ContactChannel.Phone, password = "x")
        assertFalse(phone.copy(value = "+55").canAdd)
        assertFalse(phone.copy(value = "11999998888").canAdd)
        assertTrue(phone.copy(value = "+5511999998888").canAdd)
        val email = ContactsState(channel = ContactChannel.Email, password = "x")
        assertFalse(email.copy(value = "sem-arroba").canAdd)
        assertTrue(email.copy(value = "a@example.com").canAdd)
    }
}
