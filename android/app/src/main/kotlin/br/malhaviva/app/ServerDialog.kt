package br.malhaviva.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import br.malhaviva.core.data.net.ApiBaseUrlStore

/** Entrada oculta do piloto: troca o servidor da API sem recompilar. Vale na proxima requisicao. */
@Composable
fun ServerDialog(
    store: ApiBaseUrlStore,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(store.current()) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Servidor") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        invalid = false
                    },
                    label = { Text("Endereço da API") },
                    singleLine = true,
                    isError = invalid,
                )
                if (invalid) {
                    val rule = if (BuildConfig.DEBUG) "https:// ou http://" else "https://"
                    Text("Endereço inválido: use $rule.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton({
                if (store.save(text)) onDismiss() else invalid = true
            }) { Text("Salvar") }
        },
        dismissButton = {
            Column {
                TextButton({
                    store.reset()
                    onDismiss()
                }) { Text("Restaurar padrão") }
                TextButton(onDismiss) { Text("Cancelar") }
            }
        },
    )
}
