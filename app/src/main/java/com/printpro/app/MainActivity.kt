package com.printpro.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.printpro.app.print.SystemPrint
import com.printpro.app.ui.MainScreen
import com.printpro.app.ui.MainViewModel
import com.printpro.app.ui.PrintProTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PrintProTheme { MainScreen(vm, onSystemPrint = ::printWithAndroid) } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Fichiers reçus via « Partager » ou « Ouvrir avec ». */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.loadFiles(uris)
    }

    private fun printWithAndroid() {
        lifecycleScope.launch {
            val file = vm.fileForSystemPrint()
            if (file == null) {
                Toast.makeText(this@MainActivity, "Aucun document prêt", Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(this@MainActivity, "Réglez les copies et le recto verso dans la fenêtre Android.", Toast.LENGTH_LONG).show()
            SystemPrint.print(this@MainActivity, file, vm.ui.value.sourceName ?: "Print Pro", vm.ui.value.settings)
        }
    }
}
