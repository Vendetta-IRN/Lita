package com.lita.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.lita.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lita.app.R
import com.lita.app.ui.theme.LitaBrush
import com.lita.app.ui.theme.LitaLogo
import com.lita.app.ui.theme.LitaTextMid
import kotlinx.coroutines.launch

/**
 * Branded start screen: the Lita icon in the centre, credit line at the bottom.
 * The small image after "from" is res/drawable/vendetta_emoji.png (replace it with your own emoji image).
 */
@Composable
fun SplashScreen(onShown: () -> Unit = {}) {
    val fade = remember { Animatable(0f) }
    val grow = remember { Animatable(0.94f) }
    LaunchedEffect(Unit) { withFrameNanos { }; onShown() }
    LaunchedEffect(Unit) {
        launch { fade.animateTo(1f, tween(600)) }
        grow.animateTo(1f, tween(700))
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.align(Alignment.Center).graphicsLayer { scaleX = grow.value; scaleY = grow.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LitaLogo(120.dp)
            Spacer(Modifier.height(18.dp))
            Text("Lita", style = TextStyle(brush = LitaBrush, fontSize = 34.sp, fontWeight = FontWeight.Bold))
        }
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp).graphicsLayer { alpha = fade.value },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Created by Vendetta IRN from ", color = LitaTextMid, fontSize = 13.sp)
            Image(painterResource(R.drawable.vendetta_emoji), contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
    }
}
