package com.example.inksyncboox

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.inksyncboox.data.FileReaderStore
import com.example.inksyncboox.eink.BooxEpdController
import com.example.inksyncboox.ui.main.MainScreen
import com.example.inksyncboox.ui.main.MainScreenViewModel

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainScreenViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainScreenViewModel(FileReaderStore(applicationContext)) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openedFromIntent = handleIncomingIntent(intent)
        if (savedInstanceState == null && !openedFromIntent) {
            mainViewModel.restoreLastDocumentIfIdle(Uri::parse)
        }
        enterFullScreen()
        setContent { MainScreen(viewModel = mainViewModel) }
    }

    override fun onResume() {
        super.onResume()
        if (BooxEpdController.isBooxHardware) {
            // Same broadcast the Notable app sends: asks Boox's per-app optimizer to keep this
            // app full screen. Ignored on other devices and harmless if unsupported.
            sendBroadcast(
                Intent("com.onyx.app.optimize.setting")
                    .putExtra("optimize_fullScreen", true)
                    .putExtra("optimize_pkgName", packageName)
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullScreen()
    }

    /** Hides the status and navigation bars; swipe from an edge to show them briefly. */
    private fun enterFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Opens a file sent from the Boox file manager or another app. Returns true if one was found. */
    private fun handleIncomingIntent(intent: Intent?): Boolean {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return false
        mainViewModel.requestOpen(uri)
        return true
    }

    /**
     * Page turning from hardware keys: Bluetooth page-turn remotes, keyboards and volume keys.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!mainViewModel.readerState.value.isReaderMode) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_A -> {
                mainViewModel.stepForwardTierOrPage()
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_B -> {
                mainViewModel.stepBackwardTierOrPage()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
