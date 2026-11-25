package br.malhaviva.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Contador de referencias para FLAG_SECURE: duas telas sensiveis compostas ao mesmo tempo nao
 * desligam a flag uma da outra. [apply] recebe true ao ligar a primeira e false ao soltar a ultima.
 */
class SecureFlagTracker(
    private val apply: (Boolean) -> Unit,
) {
    private var holders = 0

    fun acquire() {
        if (holders++ == 0) apply(true)
    }

    fun release() {
        if (holders > 0 && --holders == 0) apply(false)
    }
}

private val trackers = java.util.WeakHashMap<Activity, SecureFlagTracker>()

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * Marca a janela com FLAG_SECURE (bloqueia captura de tela e previa em apps recentes) enquanto
 * [content] estiver na composicao. Usar em telas sensiveis (login, recuperacao, dispositivos).
 * A flag na janela nao tem teste em aparelho; so o contador e testado em JVM.
 */
@Composable
fun SecureScreen(content: @Composable () -> Unit) {
    val activity = LocalContext.current.findActivity()
    if (activity != null) {
        DisposableEffect(activity) {
            val tracker =
                trackers.getOrPut(activity) {
                    SecureFlagTracker { on ->
                        if (on) {
                            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
                }
            tracker.acquire()
            onDispose { tracker.release() }
        }
    }
    content()
}
