package br.malhaviva.auth.recovery

import br.malhaviva.auth.STRONG_PASSWORD
import br.malhaviva.auth.TestAuth
import br.malhaviva.auth.authRoutes
import br.malhaviva.tracking.ingest.RateLimiter
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOUR_MS = 3_600_000L

class RecoveryRoutesTest {
    private fun app(
        r: TestRecovery,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { route("/v1") { authRoutes(r.components) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(
        path: String,
        body: String,
        bearer: String? = null,
    ): HttpResponse =
        client.post("/v1$path") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.get(
        path: String,
        bearer: String,
    ): HttpResponse = client.get("/v1$path") { header(HttpHeaders.Authorization, "Bearer $bearer") }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.signup(
        handle: String,
        withContacts: Boolean = true,
        phone: String = FAKE_PHONE,
        email: String = FAKE_EMAIL,
    ): HttpResponse {
        val extra =
            if (withContacts) ""","phone":"$phone","email":"$email","captcha_token":"ok"""" else ""
        return post("/auth/accounts", """{"handle":"$handle","password":"$STRONG_PASSWORD"$extra}""")
    }

    private suspend fun ApplicationTestBuilder.login(
        handle: String,
        password: String = STRONG_PASSWORD,
    ): HttpResponse = post("/auth/token", """{"handle":"$handle","password":"$password","device_label":"Pixel"}""")

    private suspend fun ApplicationTestBuilder.tokenOf(handle: String) = login(handle).obj().str("access_token")

    private suspend fun ApplicationTestBuilder.confirm(
        access: String,
        channel: Channel,
        code: String,
        changeId: String? = null,
    ): HttpResponse {
        val change = changeId?.let { ""","change_id":"$it"""" } ?: ""","current_password":"$STRONG_PASSWORD""""
        return post("/me/contacts/confirm", """{"channel":"${channel.wire}","code":"$code"$change}""", access)
    }

    /** Cadastra com os dois contatos e verifica os canais pedidos pelo OTP enviado no cadastro. */
    private suspend fun ApplicationTestBuilder.verified(
        r: TestRecovery,
        handle: String,
        vararg channels: Channel,
    ) {
        assertEquals(HttpStatusCode.Created, signup(handle).status)
        val access = tokenOf(handle)
        channels
            .ifEmpty {
                Channel.entries.toTypedArray()
            }.forEach { assertEquals(HttpStatusCode.NoContent, confirm(access, it, r.fake.lastCode(it)).status) }
    }

    private suspend fun ApplicationTestBuilder.recover(handle: String) =
        post("/auth/recovery", """{"handle":"$handle"}""")

    private suspend fun ApplicationTestBuilder.complete(
        handle: String,
        phone: String?,
        email: String?,
        password: String = NEW_PASSWORD,
    ): HttpResponse {
        val p = phone?.let { ""","phone_code":"$it"""" }.orEmpty()
        val e = email?.let { ""","email_code":"$it"""" }.orEmpty()
        return post("/auth/recovery/complete", """{"handle":"$handle","new_password":"$password"$p$e}""")
    }

    private fun wrongOf(code: String) = if (code == "111111") "222222" else "111111"

    private fun TestRecovery.accountId(handle: String) = auth.accounts.findByHandle(handle)!!.id

    private suspend fun ApplicationTestBuilder.pendingCount(access: String) =
        get("/me/contacts", access)
            .obj()
            .getValue("pending_changes")
            .jsonArray.size

    @Test
    fun `signup stores contacts unverified, sends otps and verification flips the flags`() {
        val r = TestRecovery(TestAuth())
        app(r) {
            val created = signup("@ana_trem")
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(2, r.fake.sent.size)
            val access = tokenOf("@ana_trem")
            val before = get("/me/contacts", access).bodyAsText()
            assertTrue(before.contains(""""phone":false"""), before)
            assertEquals(
                HttpStatusCode.BadRequest,
                confirm(access, Channel.PHONE, wrongOf(r.fake.lastCode(Channel.PHONE))).status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                confirm(access, Channel.PHONE, r.fake.lastCode(Channel.PHONE)).status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                confirm(access, Channel.EMAIL, r.fake.lastCode(Channel.EMAIL)).status,
            )
            val after = get("/me/contacts", access).bodyAsText()
            assertTrue(after.contains(""""phone":true""") && after.contains(""""email":true"""), after)
            listOf(created.bodyAsText(), before, after).forEach {
                assertFalse(it.contains(FAKE_PHONE) || it.contains(FAKE_EMAIL), it)
            }
            val bad = signup("@bia_trem", phone = "21999990000")
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals("invalid_contact", bad.obj().str("detail"))
            assertEquals(HttpStatusCode.Created, signup("@bia_trem", withContacts = false).status)
        }
    }

    @Test
    fun `recovery with both channels needs both otps, notifies and revokes every session`() {
        val r = TestRecovery(TestAuth())
        app(r) {
            verified(r, "@dupla")
            val refreshes = (1..2).map { login("@dupla").obj().str("refresh_token") }
            r.fake.sent.clear()
            val accepted = recover("@dupla")
            assertEquals(HttpStatusCode.Accepted, accepted.status)
            assertEquals("""{"status":"accepted"}""", accepted.bodyAsText())
            assertEquals(
                setOf(FAKE_PHONE, FAKE_EMAIL),
                r.fake.sent
                    .map { it.to }
                    .toSet(),
            )
            assertTrue(r.fake.sent.all { it.code != null })
            val phone = r.fake.lastCode(Channel.PHONE)
            val email = r.fake.lastCode(Channel.EMAIL)
            val onlyPhone = complete("@dupla", phone, null)
            assertEquals(HttpStatusCode.BadRequest, onlyPhone.status)
            assertEquals("invalid_or_expired_code", onlyPhone.obj().str("detail"))
            assertEquals(HttpStatusCode.BadRequest, complete("@dupla", phone, wrongOf(email)).status)
            r.fake.sent.clear()
            assertEquals(HttpStatusCode.NoContent, complete("@dupla", phone, email).status)
            assertEquals(HttpStatusCode.Unauthorized, login("@dupla").status)
            assertEquals(HttpStatusCode.OK, login("@dupla", NEW_PASSWORD).status)
            refreshes.forEach {
                assertEquals(HttpStatusCode.Unauthorized, post("/auth/refresh", """{"refresh_token":"$it"}""").status)
            }
            assertEquals(
                setOf(FAKE_PHONE, FAKE_EMAIL),
                r.fake.sent
                    .map { it.to }
                    .toSet(),
                "aviso de conclusao",
            )
            assertTrue(r.fake.sent.all { it.code == null })
            assertEquals(HttpStatusCode.BadRequest, complete("@dupla", phone, email).status, "OTP de uso unico")
        }
    }

    @Test
    fun `the other channel gets a notice when only one channel is verified`() {
        val r = TestRecovery(TestAuth())
        app(r) {
            verified(r, "@so_celular", Channel.PHONE)
            r.fake.sent.clear()
            assertEquals(HttpStatusCode.Accepted, recover("@so_celular").status)
            val sms = r.fake.to(FAKE_PHONE).single()
            val mail = r.fake.to(FAKE_EMAIL).single()
            assertNotNull(sms.code)
            assertNull(mail.code, "o aviso ao outro canal nao leva o codigo")
            assertTrue(mail.text.contains("recuperacao"))
            r.fake.sent.clear()
            assertEquals(HttpStatusCode.NoContent, complete("@so_celular", sms.code, null).status)
            assertEquals(2, r.fake.sent.size)
        }
    }

    @Test
    fun `responses do not reveal whether the account exists`() {
        val r = TestRecovery(TestAuth())
        app(r) {
            signup("@sem_canal", withContacts = false)
            verified(r, "@com_canal")
            r.fake.sent.clear()
            val seen =
                listOf("@fantasma", "@sem_canal", "@com_canal").map {
                    val res = recover(it)
                    Triple(res.status, res.bodyAsText(), res.headers.names())
                }
            assertEquals(1, seen.toSet().size, "mesmo status, corpo e cabecalhos: $seen")
            assertEquals(
                setOf(FAKE_PHONE, FAKE_EMAIL),
                r.fake.sent
                    .map { it.to }
                    .toSet(),
                "so a conta com canal envia",
            )
            val done =
                listOf("@fantasma", "@sem_canal", "@com_canal").map {
                    val res = complete(it, "123456", "654321")
                    Triple(res.status, res.bodyAsText(), res.headers.names())
                }
            assertEquals(1, done.toSet().size, "falha igual para handle inexistente e OTP errado: $done")
            assertEquals(HttpStatusCode.BadRequest, done.first().first)
        }
    }

    @Test
    fun `otp allows only five attempts and expires after ten minutes`() {
        val t = TestAuth()
        val r = TestRecovery(t)
        app(r) {
            verified(r, "@tentativas")
            recover("@tentativas")
            val phone = r.fake.lastCode(Channel.PHONE)
            val email = r.fake.lastCode(Channel.EMAIL)
            repeat(OTP_MAX_ATTEMPTS) {
                assertEquals(HttpStatusCode.BadRequest, complete("@tentativas", wrongOf(phone), wrongOf(email)).status)
            }
            assertEquals(HttpStatusCode.BadRequest, complete("@tentativas", phone, email).status)
            t.now += HOUR_MS
            recover("@tentativas")
            val p2 = r.fake.lastCode(Channel.PHONE)
            val e2 = r.fake.lastCode(Channel.EMAIL)
            t.now += 11 * 60_000
            assertEquals(HttpStatusCode.BadRequest, complete("@tentativas", p2, e2).status)
        }
    }

    @Test
    fun `contact change applies only after 24 hours, notifies the old channel and needs the password`() {
        val t = TestAuth()
        val r = TestRecovery(t)
        app(r) {
            verified(r, "@troca")
            var access = tokenOf("@troca")
            val body = """{"channel":"phone","value":"$FAKE_PHONE_2"""
            assertEquals("invalid_password", post("/me/contacts", "$body\"}", access).obj().str("detail"))
            assertEquals(
                HttpStatusCode.Forbidden,
                post("/me/contacts", "$body\",\"current_password\":\"errada errada\"}", access).status,
            )
            r.fake.sent.clear()
            val started = post("/me/contacts", "$body\",\"current_password\":\"$STRONG_PASSWORD\"}", access)
            assertEquals(HttpStatusCode.Accepted, started.status)
            val change = started.obj()
            assertEquals("change_pending", change.str("status"))
            assertNotNull(
                r.fake
                    .to(FAKE_PHONE_2)
                    .single()
                    .code,
            )
            assertNull(
                r.fake
                    .to(FAKE_PHONE)
                    .single()
                    .code,
                "canal antigo so recebe aviso",
            )
            assertEquals(
                HttpStatusCode.NoContent,
                confirm(access, Channel.PHONE, r.fake.lastCode(Channel.PHONE), change.str("change_id")).status,
            )
            t.now += 23 * HOUR_MS
            access = tokenOf("@troca")
            assertEquals(1, pendingCount(access))
            r.fake.sent.clear()
            recover("@troca")
            assertTrue(
                r.fake.to(FAKE_PHONE).isNotEmpty() && r.fake.to(FAKE_PHONE_2).isEmpty(),
                "antes de 24 h vale o contato antigo",
            )
            t.now += HOUR_MS + 1
            access = tokenOf("@troca")
            assertEquals(0, pendingCount(access))
            r.fake.sent.clear()
            t.now += HOUR_MS
            recover("@troca")
            assertTrue(r.fake.to(FAKE_PHONE_2).any { it.code != null }, "depois de 24 h vale o novo contato")
            assertTrue(r.fake.to(FAKE_PHONE).none { it.code != null })
        }
    }

    @Test
    fun `owner can cancel a pending change, others cannot, and unconfirmed changes never apply`() {
        val t = TestAuth()
        val r = TestRecovery(t)
        app(r) {
            verified(r, "@cancela")
            assertEquals(HttpStatusCode.Created, signup("@intruso", withContacts = false).status)
            val access = tokenOf("@cancela")
            val started =
                post(
                    "/me/contacts",
                    """{"channel":"email","value":"$FAKE_EMAIL_2","current_password":"$STRONG_PASSWORD"}""",
                    access,
                )
            val id = started.obj().str("change_id")
            val other =
                client.delete("/v1/me/contact-changes/$id") {
                    header(HttpHeaders.Authorization, "Bearer ${tokenOf("@intruso")}")
                }
            assertEquals(HttpStatusCode.NotFound, other.status)
            r.fake.sent.clear()
            val cancelled =
                client.delete(
                    "/v1/me/contact-changes/$id",
                ) { header(HttpHeaders.Authorization, "Bearer $access") }
            assertEquals(HttpStatusCode.NoContent, cancelled.status)
            assertNull(
                r.fake
                    .to(FAKE_EMAIL)
                    .single()
                    .code,
                "canal antigo avisado do cancelamento",
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                confirm(access, Channel.EMAIL, "123456", id).status,
                "troca cancelada nao confirma",
            )
            val second =
                post(
                    "/me/contacts",
                    """{"channel":"email","value":"$FAKE_EMAIL_2","current_password":"$STRONG_PASSWORD"}""",
                    access,
                )
            assertEquals(HttpStatusCode.Accepted, second.status)
            t.now += 25 * HOUR_MS
            assertEquals(r.contacts.find(r.accountId("@cancela")).email, FAKE_EMAIL, "sem OTP do novo canal nao aplica")
            assertEquals(1, pendingCount(tokenOf("@cancela")), "troca sem OTP segue pendente e nunca aplica")
        }
    }

    @Test
    fun `completing a recovery cancels pending contact changes`() {
        val t = TestAuth()
        val r = TestRecovery(t)
        app(r) {
            verified(r, "@sequestro")
            val access = tokenOf("@sequestro")
            val id =
                post(
                    "/me/contacts",
                    """{"channel":"phone","value":"$FAKE_PHONE_2","current_password":"$STRONG_PASSWORD"}""",
                    access,
                ).obj()
                    .str("change_id")
            confirm(access, Channel.PHONE, r.fake.lastCode(Channel.PHONE), id)
            r.fake.sent.clear()
            recover("@sequestro")
            val phone = r.fake.lastCode(Channel.PHONE)
            val email = r.fake.lastCode(Channel.EMAIL)
            assertEquals(HttpStatusCode.NoContent, complete("@sequestro", phone, email).status)
            t.now += 25 * HOUR_MS
            assertEquals(FAKE_PHONE, r.contacts.find(r.accountId("@sequestro")).phone)
            assertEquals(0, pendingCount(tokenOf("@sequestro", NEW_PASSWORD)))
        }
    }

    private suspend fun ApplicationTestBuilder.tokenOf(
        handle: String,
        password: String,
    ) = login(handle, password).obj().str("access_token")

    @Test
    fun `captcha is required after repeated requests and behaves the same for unknown handles`() {
        val roomy = RecoveryLimits(requestIp = RateLimiter(100.0, 1.0), requestHandle = RateLimiter(100.0, 1.0))
        val r = TestRecovery(TestAuth(), limits = roomy)
        app(r) {
            verified(r, "@conhecida")
            repeat(3) { assertEquals(HttpStatusCode.Accepted, recover("@fantasma_$it").status) }
            val blocked = recover("@fantasma_9")
            assertEquals(HttpStatusCode.Forbidden, blocked.status)
            assertEquals("captcha_required", blocked.obj().str("detail"))
            assertEquals(HttpStatusCode.Forbidden, recover("@conhecida").status)
            val ok = post("/auth/recovery", """{"handle":"@conhecida","captcha_token":"token-de-teste"}""")
            assertEquals(HttpStatusCode.Accepted, ok.status)
        }
    }

    @Test
    fun `spend ceiling stops sending but the request still answers 202`() {
        val t = TestAuth()
        // cota reservada de recuperacao esgotada: o cadastro (cota propria) enviou, a recuperacao nao envia mais
        val limits =
            SendLimits(
                clock = { t.now },
                recoverySms = SpendCeiling("sms-recovery", 0) { t.now },
                ddi = RateLimiter(99.0, 1.0),
            )
        val r = TestRecovery(t, sendLimits = limits)
        app(r) {
            verified(r, "@teto")
            val smsBefore = r.fake.sent.count { it.channel == Channel.PHONE }
            assertEquals(1, smsBefore)
            assertEquals(HttpStatusCode.Accepted, recover("@teto").status)
            assertEquals(smsBefore, r.fake.sent.count { it.channel == Channel.PHONE }, "teto diario: nenhum SMS novo")
        }
    }

    @Test
    fun `a failing provider keeps the generic response and logs no personal data`() {
        val r = TestRecovery(TestAuth(), FakeSender(failing = true))
        val logs =
            captureLogs {
                app(r) {
                    assertEquals(HttpStatusCode.Created, signup("@provedor_fora").status)
                    val id = r.accountId("@provedor_fora")
                    r.contacts.setVerified(id, Channel.PHONE, FAKE_PHONE, java.time.Instant.EPOCH)
                    assertEquals(HttpStatusCode.Accepted, recover("@provedor_fora").status)
                }
            }
        assertTrue(logs.contains("Falha ao enviar"), logs)
        listOf(FAKE_PHONE, FAKE_EMAIL, "provedor_fora").forEach { assertFalse(logs.contains(it), it) }
    }

    @Test
    fun `fail closed sender refuses to send`() {
        assertFailsOnSend()
    }

    private fun assertFailsOnSend() {
        val failed =
            runCatching { runBlocking { FailClosedMessageSender.sendSms(FAKE_PHONE, "x") } }.exceptionOrNull()
        assertTrue(failed is MessageDeliveryException)
        assertFalse(failed?.message.orEmpty().contains(FAKE_PHONE))
    }

    @Test
    fun `production logging sender never logs otp codes, contacts or handles`() {
        val r = TestRecovery(TestAuth(), FakeSender(also = LoggingMessageSender(showOtp = false)))
        val logs =
            captureLogs {
                app(r) {
                    verified(r, "@sem_vazar")
                    recover("@sem_vazar")
                    complete("@sem_vazar", r.fake.lastCode(Channel.PHONE), r.fake.lastCode(Channel.EMAIL))
                }
            }
        assertTrue(logs.contains("Mensagem sms enviada"), logs)
        val codes =
            r.fake.sent
                .mapNotNull { it.code }
                .distinct()
        assertTrue(codes.isNotEmpty())
        (codes + listOf(FAKE_PHONE, FAKE_EMAIL, "sem_vazar", STRONG_PASSWORD, NEW_PASSWORD)).forEach {
            assertFalse(logs.contains(it), "log vazou $it")
        }
    }

    @Test
    fun `dev logging sender prints the otp only when explicitly enabled`() {
        val dev = TestRecovery(TestAuth(), FakeSender(also = LoggingMessageSender(showOtp = true)))
        val logs = captureLogs { app(dev) { signup("@dev_otp") } }
        assertTrue(logs.contains(dev.fake.lastCode(Channel.PHONE)), logs)
        assertEquals(FailClosedMessageSender, RecoveryConfig.senderFromEnv { null })
        assertTrue(
            RecoveryConfig.senderFromEnv {
                if (it ==
                    "MALHAVIVA_MESSAGE_SENDER"
                ) {
                    "log"
                } else {
                    null
                }
            } is LoggingMessageSender,
        )
    }

    @Test
    fun `a contact verified by another account is not revealed at signup`() {
        val r = TestRecovery(TestAuth())
        app(r) {
            verified(r, "@dona")
            val second = signup("@curiosa")
            assertEquals(HttpStatusCode.Created, second.status)
            val access = tokenOf("@curiosa")
            val refused = confirm(access, Channel.PHONE, r.fake.lastCode(Channel.PHONE))
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertEquals("invalid_or_expired_code", refused.obj().str("detail"))
        }
    }

    private fun captureLogs(block: () -> Unit): String {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger("br.malhaviva") as Logger
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.joinToString("\n") { it.formattedMessage }
    }
}
