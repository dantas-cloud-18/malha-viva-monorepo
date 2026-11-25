package br.malhaviva.app

import br.malhaviva.feature.auth.CaptchaTokenProvider

/**
 * Release ainda nao tem provedor de CAPTCHA: devolve null e o app bloqueia o cadastro com contato,
 * com aviso claro. Trocar pela integracao real antes de publicar o cadastro com e-mail ou telefone.
 */
object UnavailableCaptchaTokenProvider : CaptchaTokenProvider {
    override suspend fun token(): String? = null
}

fun captchaTokenProvider(): CaptchaTokenProvider = UnavailableCaptchaTokenProvider
