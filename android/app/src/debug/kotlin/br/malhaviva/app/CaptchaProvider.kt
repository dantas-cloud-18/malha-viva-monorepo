package br.malhaviva.app

import br.malhaviva.feature.auth.CaptchaTokenProvider

/** Somente debug: o backend de desenvolvimento aceita este valor fixo no lugar de um CAPTCHA real. */
object DebugCaptchaTokenProvider : CaptchaTokenProvider {
    override suspend fun token(): String = "dev-captcha"
}

fun captchaTokenProvider(): CaptchaTokenProvider = DebugCaptchaTokenProvider
