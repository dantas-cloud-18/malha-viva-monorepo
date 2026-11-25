package br.malhaviva.platform

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** Limite padrao de corpo JSON das rotas de conta e recuperacao. */
const val DEFAULT_MAX_BODY_BYTES = 8 * 1024

/**
 * IP do cliente, unica fonte para limites e guardas. Atras do ALB o plugin XForwardedHeaders (useLastProxy)
 * troca o vizinho de rede pelo ultimo item de X-Forwarded-For, que e o IP que o ALB viu. Isso so e confiavel
 * porque o grupo de seguranca da aplicacao aceita trafego apenas do ALB (infra/terraform/network.tf).
 */
fun ApplicationCall.clientIp(): String = request.origin.remoteAddress

/** Le no maximo [max] + 1 bytes do canal: o chamador rejeita se vier mais que [max], sem carregar o excesso. */
suspend fun ApplicationCall.readCapped(max: Int): ByteArray = receiveChannel().readRemaining(max + 1L).readByteArray()

/** Corpo de ate [max] bytes, ou null se for maior (Content-Length declarado ou excesso lido). */
suspend fun ApplicationCall.readBoundedOrNull(max: Int = DEFAULT_MAX_BODY_BYTES): ByteArray? {
    val declared = request.contentLength()
    return if (declared != null && declared > max) null else readCapped(max).takeIf { it.size <= max }
}
