package br.malhaviva.lgpd

import br.malhaviva.PostgresIntegrationTest
import br.malhaviva.auth.TEST_CLIENT_IP
import br.malhaviva.auth.authApp
import br.malhaviva.auth.httpGet
import br.malhaviva.auth.httpPost
import br.malhaviva.auth.json
import br.malhaviva.auth.recovery.Channel
import br.malhaviva.auth.text
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ADULT_BIRTH = LocalDate.now().minusYears(30).toString()
private val TEEN_BIRTH = LocalDate.now().minusYears(15).toString()
private val CHILD_BIRTH = LocalDate.now().minusYears(8).toString()
private const val GUARDIAN_PHONE = "+5521988880000"

private suspend fun ApplicationTestBuilder.send(
    method: HttpMethod,
    path: String,
    bearer: String? = null,
    body: String? = null,
): HttpResponse =
    client.request("/v1$path") {
        this.method = method
        header("X-Forwarded-For", TEST_CLIENT_IP)
        bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        body?.let {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(it)
        }
    }

/** Fase 5 ponta a ponta: portao de idade, consentimento, direitos do titular, contra Postgres de verdade. */
class LgpdRoutesTest : PostgresIntegrationTest() {
    private suspend fun ApplicationTestBuilder.signup(
        handle: String,
        birth: String?,
        extra: String = "",
    ) = httpPost("/auth/accounts", signupBody(handle, birth, extra))

    private suspend fun ApplicationTestBuilder.login(handle: String): String {
        val r = httpPost("/auth/token", loginBody(handle))
        assertEquals(HttpStatusCode.OK, r.status)
        return r.json().text("access_token")
    }

    private suspend fun ApplicationTestBuilder.adultToken(handle: String): String {
        assertEquals(HttpStatusCode.Created, signup(handle, ADULT_BIRTH).status)
        return login(handle)
    }

    @Test
    fun `signup requires a valid birth date and derives the bracket without storing the date`() =
        authApp(LgpdEnv(ds).components) {
            assertEquals("birth_date_required", signup("@sem_data", null).json().text("detail"))
            val future = LocalDate.now().plusDays(1).toString()
            assertEquals("invalid_birth_date", signup("@futuro_1", future).json().text("detail"))
            assertEquals("invalid_birth_date", signup("@lixo_001", "ontem").json().text("detail"))
            assertEquals("invalid_birth_date", signup("@velho_01", "1850-01-01").json().text("detail"))
            val ok = signup("@adulto_1", ADULT_BIRTH)
            assertEquals(HttpStatusCode.Created, ok.status)
            assertEquals("adult", ok.json().text("age_bracket"))
            assertEquals("teen_12_17", signup("@teen_001", TEEN_BIRTH).json().text("age_bracket"))
            val columns =
                scalars("SELECT column_name FROM information_schema.columns WHERE table_name = 'accounts'")
            assertFalse(columns.any { it.toString().contains("birth") }, "a data de nascimento nao e guardada")
        }

    @Test
    fun `consents start empty, are versioned, revocable, and have an append only history`() =
        authApp(LgpdEnv(ds).components) {
            val token = adultToken("@consent_1")
            val initial = httpGet("/me/consents", token).json()
            assertEquals(TEST_POLICY, initial.text("policy_version"))
            val list = initial.getValue("consents").jsonArray
            assertEquals(listOf("location", "marketing"), list.map { it.jsonObject.text("purpose") })
            assertTrue(
                list.none {
                    it.jsonObject
                        .getValue("granted")
                        .jsonPrimitive.content
                        .toBoolean()
                },
            )

            assertEquals(
                HttpStatusCode.Conflict,
                send(HttpMethod.Put, "/me/consents/location", token, """{"policy_version":"velha"}""").status,
            )
            val grant = send(HttpMethod.Put, "/me/consents/location", token, """{"policy_version":"$TEST_POLICY"}""")
            assertEquals(HttpStatusCode.OK, grant.status)
            assertEquals(
                "true",
                grant
                    .json()
                    .getValue("granted")
                    .jsonPrimitive.content,
            )
            // idempotente: mesma versao nao gera evento novo
            send(HttpMethod.Put, "/me/consents/location", token, """{"policy_version":"$TEST_POLICY"}""")
            assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/me/consents/location", token).status)
            assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/me/consents/location", token).status)
            assertEquals(
                HttpStatusCode.OK,
                send(HttpMethod.Put, "/me/consents/marketing", token, """{"policy_version":"$TEST_POLICY"}""").status,
            )
            assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Delete, "/me/consents/outra", token).status)

            val history = Json.parseToJsonElement(httpGet("/me/consents/history", token).bodyAsText()).jsonArray
            assertEquals(
                listOf("marketing:granted", "location:revoked", "location:granted"),
                history.map { "${it.jsonObject.text("purpose")}:${it.jsonObject.text("action")}" },
            )
            assertTrue(history.all { it.jsonObject.text("policy_version") == TEST_POLICY })
        }

    @Test
    fun `revoking location keeps the account working and ingest never depends on identity`() =
        authApp(LgpdEnv(ds).components) {
            val token = adultToken("@revoga_01")
            send(HttpMethod.Put, "/me/consents/location", token, """{"policy_version":"$TEST_POLICY"}""")
            send(HttpMethod.Delete, "/me/consents/location", token)
            assertEquals(HttpStatusCode.OK, httpGet("/me/devices", token).status)
            assertEquals(HttpStatusCode.OK, httpGet("/me/consents", token).status)
            assertEquals(HttpStatusCode.OK, httpPost("/auth/token", loginBody("@revoga_01")).status)

            // o registro de consentimento nunca toca a telemetria: nenhuma FK, nenhuma coluna em comum
            val lgpdTables = "('consents','consent_events','parental_consents','access_logs')"
            val fkTargets =
                scalars(
                    "SELECT DISTINCT ccu.table_name FROM information_schema.table_constraints tc " +
                        "JOIN information_schema.constraint_column_usage ccu " +
                        "USING (constraint_name, constraint_schema) " +
                        "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name IN $lgpdTables",
                )
            assertEquals(listOf<Any?>("accounts"), fkTargets)
            val telemetryCols =
                scalars("SELECT column_name FROM information_schema.columns WHERE table_name = 'telemetry_points'")
                    .map { it.toString() }
            assertTrue(telemetryCols.none { it.contains("account") || it.contains("consent") || it.contains("user") })
            val lgpdCols =
                scalars("SELECT column_name FROM information_schema.columns WHERE table_name IN $lgpdTables")
                    .map { it.toString() }
            assertTrue(lgpdCols.none { it.contains("anon") || it.contains("epoch") || it.contains("geom") })
        }

    @Test
    fun `teenagers get protective defaults and cannot consent to marketing`() =
        authApp(LgpdEnv(ds).components) {
            assertEquals(HttpStatusCode.Created, signup("@teen_002", TEEN_BIRTH).status)
            val token = login("@teen_002")
            val marketing =
                send(HttpMethod.Put, "/me/consents/marketing", token, """{"policy_version":"$TEST_POLICY"}""")
            assertEquals(HttpStatusCode.Forbidden, marketing.status)
            assertEquals("marketing_forbidden_for_minors", marketing.json().text("detail"))
            assertEquals(
                HttpStatusCode.OK,
                send(HttpMethod.Put, "/me/consents/location", token, """{"policy_version":"$TEST_POLICY"}""").status,
            )
            val states = httpGet("/me/consents", token).json().getValue("consents").jsonArray
            assertEquals(
                listOf(true, false),
                states.map {
                    it.jsonObject
                        .getValue("granted")
                        .jsonPrimitive.content
                        .toBoolean()
                },
            )
        }

    @Test
    fun `under 12 stays pending until the guardian confirms the OTP`() {
        val env = LgpdEnv(ds)
        authApp(env.components) {
            val guardian = ""","captcha_token":"ok","guardian":{"channel":"phone","value":"$GUARDIAN_PHONE"}"""
            assertEquals("guardian_contact_required", signup("@crianca_1", CHILD_BIRTH).json().text("detail"))
            val own = ""","phone":"$GUARDIAN_PHONE","guardian":{"channel":"phone","value":"$GUARDIAN_PHONE"}"""
            assertEquals("guardian_contact_required", signup("@crianca_1", CHILD_BIRTH, own).json().text("detail"))

            val created = signup("@crianca_1", CHILD_BIRTH, guardian)
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("pending_parental_consent", created.json().text("status"))
            assertEquals("under_12", created.json().text("age_bracket"))
            assertEquals(1, env.sender.to(GUARDIAN_PHONE).size, "o OTP vai ao responsavel")

            val blocked = httpPost("/auth/token", loginBody("@crianca_1"))
            assertEquals(HttpStatusCode.Forbidden, blocked.status)
            assertEquals("parental_consent_pending", blocked.json().text("detail"))

            val wrong = httpPost("/auth/parental-consent/confirm", """{"handle":"@crianca_1","code":"000000"}""")
            assertEquals(HttpStatusCode.BadRequest, wrong.status)
            assertEquals(
                HttpStatusCode.BadRequest,
                httpPost("/auth/parental-consent/confirm", """{"handle":"@nao_existe","code":"123456"}""").status,
            )
            val code = env.sender.lastCode(Channel.PHONE)
            val ok = httpPost("/auth/parental-consent/confirm", """{"handle":"@crianca_1","code":"$code"}""")
            assertEquals(HttpStatusCode.NoContent, ok.status)
            assertEquals(
                HttpStatusCode.BadRequest,
                httpPost("/auth/parental-consent/confirm", """{"handle":"@crianca_1","code":"$code"}""").status,
                "uso unico",
            )
            assertEquals(HttpStatusCode.OK, httpPost("/auth/token", loginBody("@crianca_1")).status)
            assertEquals(1L, scalars("SELECT count(*) FROM parental_consents WHERE verified_at IS NOT NULL").single())
        }
    }

    @Test
    fun `export returns every personal datum and none of the secrets`() =
        authApp(LgpdEnv(ds).components) {
            val token = adultToken("@exporta_1")
            send(HttpMethod.Put, "/me/consents/location", token, """{"policy_version":"$TEST_POLICY"}""")
            scalars("UPDATE accounts SET phone_e164 = '+5521977770000' WHERE handle = '@exporta_1'")
            val resp = httpGet("/me/export", token)
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("no-store", resp.headers[HttpHeaders.CacheControl])
            val body = resp.bodyAsText()
            val export = Json.parseToJsonElement(body).jsonObject
            assertEquals("@exporta_1", export.getValue("account").jsonObject.text("handle"))
            assertEquals("adult", export.getValue("account").jsonObject.text("age_bracket"))
            assertEquals("+5521977770000", export.getValue("contacts").jsonObject.text("phone"))
            assertEquals(1, export.getValue("devices").jsonArray.size)
            assertEquals(1, export.getValue("consent_history").jsonArray.size)
            assertEquals(2, export.getValue("consents").jsonArray.size)
            assertEquals(
                listOf("signup", "login"),
                export.getValue("access_logs").jsonArray.map { it.jsonObject.text("event") },
            )
            assertTrue(export.containsKey("parental_consent"))
            val hash = scalars("SELECT password_hash FROM accounts WHERE handle = '@exporta_1'").single().toString()
            assertFalse(body.contains(hash), "nunca o hash de senha")
            assertFalse(body.contains("password"), "nenhum campo de senha")
            assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/me/export").status)
        }

    @Test
    fun `get me exposes the age bracket and requires a token`() =
        authApp(LgpdEnv(ds).components) {
            assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/me").status)
            assertEquals(HttpStatusCode.Created, signup("@teen_003", TEEN_BIRTH).status)
            val teen = httpGet("/me", login("@teen_003")).json()
            assertEquals("@teen_003", teen.text("handle"))
            assertEquals("teen_12_17", teen.text("age_bracket"))
            assertEquals("adult", httpGet("/me", adultToken("@adulto_03")).json().text("age_bracket"))
        }

    @Test
    fun `patch corrects the handle and refuses taken or malformed ones`() =
        authApp(LgpdEnv(ds).components) {
            val token = adultToken("@corrige_1")
            adultToken("@ocupado_1")
            assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Patch, "/me", token, "{}").status)
            assertEquals(
                HttpStatusCode.BadRequest,
                send(HttpMethod.Patch, "/me", token, """{"handle":"sem-arroba"}""").status,
            )
            assertEquals(
                HttpStatusCode.Conflict,
                send(HttpMethod.Patch, "/me", token, """{"handle":"@OCUPADO_1"}""").status,
            )
            val ok = send(HttpMethod.Patch, "/me", token, """{"handle":"@corrigido_1"}""")
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals("@corrigido_1", ok.json().text("handle"))
            assertEquals(
                "@corrigido_1",
                httpGet("/me/export", token)
                    .json()
                    .getValue("account")
                    .jsonObject
                    .text("handle"),
            )
        }

    private fun seedPersonalRows(id: Any) {
        scalars("UPDATE accounts SET phone_e164 = '+5521977770000', email = 'a@b.test' WHERE account_id = ?", id)
        scalars("INSERT INTO totp_secrets (account_id, secret_enc, enabled_at) VALUES (?, '\\x00', now())", id)
        scalars("INSERT INTO totp_backup_codes (account_id, code_hmac) VALUES (?, 'x')", id)
        scalars(
            "INSERT INTO otp_codes (account_id, purpose, channel, code_hmac, expires_at) " +
                "VALUES (?, 'RECOVERY', 'PHONE', 'h', now() + interval '1 hour')",
            id,
        )
        scalars(
            "INSERT INTO contact_changes (change_id, account_id, channel, new_value, created_at, effective_at) " +
                "VALUES (gen_random_uuid(), ?, 'EMAIL', 'n@b.test', now(), now())",
            id,
        )
        scalars(
            "INSERT INTO parental_consents " +
                "(account_id, guardian_channel, guardian_value, policy_version, requested_at) " +
                "VALUES (?, 'PHONE', '+5521988880000', 'v', now())",
            id,
        )
    }

    @Test
    fun `delete removes every personal row and revokes the session`() =
        authApp(LgpdEnv(ds).components) {
            val token = adultToken("@apaga_001")
            val id = scalars("SELECT account_id FROM accounts WHERE handle = '@apaga_001'").single()!!
            send(HttpMethod.Put, "/me/consents/location", token, """{"policy_version":"$TEST_POLICY"}""")
            seedPersonalRows(id)

            assertEquals(
                HttpStatusCode.Forbidden,
                send(HttpMethod.Delete, "/me", token, """{"current_password":"errada errada"}""").status,
            )
            assertEquals(1L, scalars("SELECT count(*) FROM accounts WHERE status = 'active'").single())
            val gone =
                send(
                    HttpMethod.Delete,
                    "/me",
                    token,
                    """{"current_password":"quantas estrelas cabem num trem vazio"}""",
                )
            assertEquals(HttpStatusCode.NoContent, gone.status)

            val tablesWithAccount =
                scalars(
                    "SELECT table_name FROM information_schema.columns WHERE column_name = 'account_id' " +
                        "AND table_schema = 'public' AND table_name <> 'accounts'",
                ).map { it.toString() }
            assertTrue(
                tablesWithAccount.containsAll(
                    listOf("devices", "refresh_tokens", "consents", "otp_codes", "totp_secrets"),
                ),
            )
            // access_logs fica de fora de proposito: Marco Civil art. 15 (6 meses), apagado pela retencao
            for (table in tablesWithAccount - "access_logs") {
                assertEquals(0L, scalars("SELECT count(*) FROM $table WHERE account_id = ?", id).single(), table)
            }
            assertTrue(scalars("SELECT count(*) FROM access_logs WHERE account_id = ?", id).single() as Long > 0)

            val row =
                scalars("SELECT handle || '|' || password_hash || '|' || status FROM accounts WHERE account_id = ?", id)
            assertEquals(1, row.size)
            assertTrue(row.single().toString().matches(Regex("@del_[0-9a-f]{12}\\|!\\|deleted")))
            assertEquals(
                listOf<Any?>(null, null, null, null, "user", null),
                listOf(
                    scalars("SELECT phone_e164 FROM accounts WHERE account_id = ?", id).single(),
                    scalars("SELECT email FROM accounts WHERE account_id = ?", id).single(),
                    scalars("SELECT phone_verified_at FROM accounts WHERE account_id = ?", id).single(),
                    scalars("SELECT email_verified_at FROM accounts WHERE account_id = ?", id).single(),
                    scalars("SELECT role FROM accounts WHERE account_id = ?", id).single(),
                    scalars("SELECT admin_enroll_hash FROM accounts WHERE account_id = ?", id).single(),
                ),
            )

            // sessao revogada: rota sensivel recusa o access em voo; login antigo e handle livre
            assertEquals(HttpStatusCode.Unauthorized, httpGet("/me/export", token).status)
            assertEquals(HttpStatusCode.Unauthorized, httpPost("/auth/token", loginBody("@apaga_001")).status)
            assertEquals(HttpStatusCode.Created, signup("@apaga_001", ADULT_BIRTH).status)
        }

    @Test
    fun `age gate boundaries`() {
        val today = LocalDate.of(2026, 11, 25)
        assertEquals(br.malhaviva.auth.AgeBracket.UNDER_12, ageBracketOf(LocalDate.of(2014, 11, 26), today))
        assertEquals(br.malhaviva.auth.AgeBracket.TEEN, ageBracketOf(LocalDate.of(2014, 11, 25), today))
        assertEquals(br.malhaviva.auth.AgeBracket.TEEN, ageBracketOf(LocalDate.of(2008, 11, 26), today))
        assertEquals(br.malhaviva.auth.AgeBracket.ADULT, ageBracketOf(LocalDate.of(2008, 11, 25), today))
        assertNull(ageBracketOf(LocalDate.of(2026, 11, 26), today))
        assertNotNull(evaluateSignupAge("2000-01-01", null, emptyList(), today) as? SignupAge.Ok)
    }
}
