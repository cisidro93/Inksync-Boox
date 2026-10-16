package com.example.inksyncboox

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.inksyncboox.data.DefaultDataRepository
import com.example.inksyncboox.theme.InkSyncBooxTheme
import com.example.inksyncboox.ui.main.MainScreenViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainScreenViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainScreenViewModel(DefaultDataRepository()) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        enableEdgeToEdge()
        setContent {
            InkSyncBooxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(viewModel = mainViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val dataUri = intent.data
        val extraFilePath = intent.getStringExtra("file_path")
        when {
            dataUri != null -> mainViewModel.setIncomingFileUri(dataUri)
            !extraFilePath.isNullOrBlank() -> {
                val file = File(extraFilePath)
                if (file.exists()) {
                    mainViewModel.setIncomingFileUri(Uri.fromFile(file))
                }
            }
        }
    }

    /**
     * Hardware Page-Turn Button & Bluetooth Remote Responder for Boox Note Air 4C.
     * Supports Volume Up/Down, Page Up/Down, and D-Pad Left/Right to step seamlessly
     * through NeoFlow Smart Tiers (Q1 -> Q2 -> Q3 -> Next Page) with zero animation lag.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                mainViewModel.stepForwardTierOrPage()
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                mainViewModel.stepBackwardTierOrPage()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
