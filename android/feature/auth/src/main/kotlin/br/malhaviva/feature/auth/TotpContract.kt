package br.malhaviva.feature.auth

import br.malhaviva.feature.auth.net.AuthError
import br.malhaviva.feature.auth.net.TotpEnrollment

enum class TotpStep { Menu, EnrollStart, EnrollConfirm, BackupCodes, Regenerate, Disable }

/**
 * Enrolamento, regeneracao de codigos de backup e desligamento do TOTP. Segredo, URI e codigos de
 * backup so existem no estado enquanto a tela os mostra; [TotpIntent.Acknowledge] apaga tudo.
 */
data class TotpState(
    val step: TotpStep = TotpStep.Menu,
    /** Sessao restrita (admin sem TOTP): so o enrolamento esta disponivel. */
    val enrollOnly: Boolean = false,
    /** Admin precisa do codigo de enrolamento entregue fora de banda. */
    val needsEnrollmentCode: Boolean = false,
    val enrollmentCode: String = "",
    val enrollment: TotpEnrollment? = null,
    val backupCodes: List<String> = emptyList(),
    val password: String = "",
    val code: String = "",
    val useBackupCode: Boolean = false,
    val busy: Boolean = false,
    val message: AuthMessage? = null,
    val retryAfterSeconds: Long? = null,
    /** Sessao perdida numa rota autenticada: a tela avisa o fluxo principal. */
    val sessionLost: Boolean = false,
    /** Sessao restrita ja usada; o usuario precisa entrar de novo. */
    val relogin: Boolean = false,
) {
    val canStart get() = !busy && (!needsEnrollmentCode || enrollmentCode.isNotBlank())
    val canConfirm get() = !busy && isCodeValid(code)
    val canRegenerate
        get() = !busy && password.isNotEmpty() && (if (useBackupCode) code.isNotBlank() else isCodeValid(code))
    val canDisable get() = !busy && password.isNotEmpty() && isCodeValid(code)
}

sealed interface TotpIntent {
    data class Open(
        val enrollOnly: Boolean,
    ) : TotpIntent

    data class Go(
        val step: TotpStep,
    ) : TotpIntent

    data class PasswordChanged(
        val value: String,
    ) : TotpIntent

    data class CodeChanged(
        val value: String,
    ) : TotpIntent

    data class EnrollmentCodeChanged(
        val value: String,
    ) : TotpIntent

    data object ToggleBackupCode : TotpIntent

    data object SubmitStart : TotpIntent

    data object SubmitConfirm : TotpIntent

    data object SubmitRegenerate : TotpIntent

    data object SubmitDisable : TotpIntent

    data class Started(
        val enrollment: TotpEnrollment,
    ) : TotpIntent

    data class CodesReceived(
        val codes: List<String>,
        val regenerated: Boolean,
    ) : TotpIntent

    data object Disabled : TotpIntent

    /** "Guardei os codigos": unica saida da tela de codigos de backup. */
    data object Acknowledge : TotpIntent

    data class Failed(
        val error: AuthError,
    ) : TotpIntent
}

fun reduceTotp(
    state: TotpState,
    intent: TotpIntent,
): TotpState =
    when (intent) {
        is TotpIntent.Open ->
            TotpState(
                step = if (intent.enrollOnly) TotpStep.EnrollStart else TotpStep.Menu,
                enrollOnly = intent.enrollOnly,
                needsEnrollmentCode = intent.enrollOnly,
            )
        // Durante os codigos de backup nao ha navegacao: so [TotpIntent.Acknowledge] sai.
        is TotpIntent.Go ->
            if (state.step == TotpStep.BackupCodes) state else form(state).copy(step = intent.step)
        TotpIntent.SubmitStart -> if (state.canStart) state.copy(busy = true, message = null) else state
        TotpIntent.SubmitConfirm -> if (state.canConfirm) state.copy(busy = true, message = null) else state
        TotpIntent.SubmitRegenerate -> if (state.canRegenerate) state.copy(busy = true, message = null) else state
        TotpIntent.SubmitDisable -> if (state.canDisable) state.copy(busy = true, message = null) else state
        else -> reduceTotpResult(state, intent)
    }

/** Limpa os campos digitados ao trocar de passo, mantendo o modo da sessao. */
private fun form(state: TotpState) =
    TotpState(
        enrollOnly = state.enrollOnly,
        needsEnrollmentCode = state.needsEnrollmentCode,
        enrollmentCode = state.enrollmentCode,
        enrollment = state.enrollment,
    )

private fun reduceTotpResult(
    state: TotpState,
    intent: TotpIntent,
): TotpState =
    when (intent) {
        is TotpIntent.PasswordChanged -> state.copy(password = intent.value, message = null)
        is TotpIntent.CodeChanged -> state.copy(code = intent.value.trim(), message = null)
        is TotpIntent.EnrollmentCodeChanged -> state.copy(enrollmentCode = intent.value.trim(), message = null)
        TotpIntent.ToggleBackupCode -> state.copy(useBackupCode = !state.useBackupCode, code = "", message = null)
        is TotpIntent.Started ->
            state.copy(step = TotpStep.EnrollConfirm, enrollment = intent.enrollment, busy = false, enrollmentCode = "")
        is TotpIntent.CodesReceived ->
            TotpState(
                step = TotpStep.BackupCodes,
                enrollOnly = state.enrollOnly,
                backupCodes = intent.codes,
                message = if (intent.regenerated) AuthMessage.CodesRegenerated else null,
            )
        TotpIntent.Disabled -> TotpState(enrollOnly = state.enrollOnly, message = AuthMessage.TotpDisabled)
        TotpIntent.Acknowledge ->
            if (state.enrollOnly) TotpState(enrollOnly = true, relogin = true) else TotpState()
        is TotpIntent.Failed -> reduceTotpFailure(state, intent.error)
        else -> state
    }

private fun reduceTotpFailure(
    state: TotpState,
    error: AuthError,
): TotpState =
    state.copy(
        busy = false,
        // 401 numa rota autenticada, depois do refresh, e sessao perdida.
        sessionLost = error == AuthError.Unauthorized,
        needsEnrollmentCode = state.needsEnrollmentCode || error == AuthError.EnrollmentCodeRequired,
        message = error.toMessage(),
        retryAfterSeconds = (error as? AuthError.RateLimited)?.retryAfterSeconds,
    )
