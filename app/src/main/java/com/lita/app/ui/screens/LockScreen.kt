package com.lita.app.ui.screens

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.lita.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.lita.app.i18n.I18n
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lita.app.LitaViewModel
import com.lita.app.PinResult
import com.lita.app.ui.theme.LitaLogo
import com.lita.app.ui.theme.LitaPink
import com.lita.app.ui.theme.LitaSurfaceHigh
import com.lita.app.ui.theme.LitaTextMid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
    return null
}

object Biometrics {
    /** Depends on enrolled biometrics and device configuration; false when none is usable. */
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    /** Shows the real system biometric prompt (never a lookalike). */
    fun prompt(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure(errString.toString())
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(I18n.tr(title))
            .setNegativeButtonText(I18n.tr("Use PIN"))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }
}

@Composable
fun LockScreen(vm: LitaViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val biometric by vm.biometricEnabled.collectAsStateWithLifecycle()
    val pinLen by vm.pinLength.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var lockedMs by remember { mutableStateOf(vm.lockRemainingMs()) }
    var busy by remember { mutableStateOf(false) }
    val canBio = biometric && Biometrics.available(context)

    fun tryBio() {
        val act = context.findFragmentActivity() ?: return
        Biometrics.prompt(act, "Unlock Lita", onSuccess = { vm.unlockedByBiometric() }, onFailure = { })
    }

    LaunchedEffect(Unit) { if (canBio && lockedMs <= 0) tryBio() }
    LaunchedEffect(lockedMs) {
        while (lockedMs > 0) { delay(500); lockedMs = vm.lockRemainingMs() }
        if (lockedMs <= 0 && message?.startsWith("Too many") == true) message = null
    }

    fun submit() {
        if (busy || pin.length < 4) return
        busy = true
        val attempt = pin.toCharArray()
        scope.launch {
            when (val r = vm.unlockWithPin(attempt)) {
                is PinResult.Ok -> {}
                is PinResult.Wrong -> { message = "Incorrect PIN"; pin = "" }
                is PinResult.Locked -> { lockedMs = r.remainingMs; message = "Too many attempts. Try again later."; pin = "" }
            }
            busy = false
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LitaLogo(64.dp)
        Box(Modifier.height(16.dp))
        Text("Enter PIN", style = MaterialTheme.typography.titleLarge)
        Box(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in 0 until (if (pinLen > 0) pinLen else maxOf(pin.length, 4))) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (i < pin.length) LitaPink else LitaSurfaceHigh))
            }
        }
        Box(Modifier.height(12.dp))
        val msg = if (lockedMs > 0) "Locked for ${(lockedMs + 999) / 1000} s" else if (busy) "Checking…" else message
        Text(msg ?: " ", color = if (msg == null) Color.Transparent else if (busy) LitaTextMid else MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.height(16.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("bio", "0", "del"))
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                for (k in row) {
                    PadKey(k, canBio) {
                        if (lockedMs > 0 || busy) return@PadKey
                        when (k) {
                            "del" -> { pin = pin.dropLast(1); message = null }
                            "bio" -> tryBio()
                            else -> if (pin.length < 8) { pin += k; message = null; if (pinLen > 0 && pin.length == pinLen) submit() }
                        }
                    }
                }
            }
            Box(Modifier.height(14.dp))
        }
        if (pinLen == 0) {
            Text(
                "OK", color = if (pin.length >= 4) LitaPink else LitaTextMid, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable(enabled = pin.length >= 4 && lockedMs <= 0) { submit() }.padding(12.dp),
            )
        }
    }
    }
}

@Composable
private fun PadKey(key: String, canBio: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(if (key == "bio" && !canBio) Color.Transparent else LitaSurfaceHigh)
            .clickable(enabled = !(key == "bio" && !canBio), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            "del" -> Icon(Icons.Rounded.Backspace, contentDescription = "Delete digit")
            "bio" -> if (canBio) Icon(Icons.Rounded.Fingerprint, contentDescription = "Use biometrics")
            else -> Text(key, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
