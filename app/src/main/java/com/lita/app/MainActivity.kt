package com.lita.app

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.lita.app.ui.LitaRoot
import com.lita.app.ui.theme.LitaTheme

/** FragmentActivity is required by AndroidX BiometricPrompt. */
class MainActivity : FragmentActivity() {
    private val vm: LitaViewModel by viewModels()
    @Volatile private var uiReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash stays until our own splash has drawn its first frame: no black gap before the branded screen.
        installSplashScreen().setKeepOnScreenCondition { !uiReady }
        Handler(Looper.getMainLooper()).postDelayed({ uiReady = true }, 2000)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            val lang by vm.language.collectAsStateWithLifecycle()
            LitaTheme(lang) { LitaRoot(vm, onUiReady = { uiReady = true }) }
        }
    }

    override fun onStart() { super.onStart(); vm.onAppStarted() }
    override fun onStop() { vm.onAppStopped(); super.onStop() }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true   // handled on key down
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (vm.onVolumeKey(keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
