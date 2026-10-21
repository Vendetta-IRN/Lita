package com.lita.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.lita.app.i18n.AppLanguages
import com.lita.app.i18n.I18n
import com.lita.app.ui.AppFonts
import com.lita.app.ui.LocalAppFont
import com.lita.app.ui.LocalAppLanguage
import com.lita.app.ui.withFamily
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp

val LitaBlack = Color(0xFF000000)
val LitaSurface = Color(0xFF0B0B10)
val LitaSurfaceHigh = Color(0xFF16161D)
val LitaPink = Color(0xFFFF3D8B)
val LitaViolet = Color(0xFF8A5CFF)
val LitaCyan = Color(0xFF22D3EE)
val LitaTextHigh = Color(0xFFF2F2F7)
val LitaTextMid = Color(0xFF9A9AA6)
val LitaError = Color(0xFFFF6B6B)

/** The Lita ribbon gradient. */
val LitaBrush: Brush get() = Brush.linearGradient(listOf(LitaPink, LitaViolet, LitaCyan))

private val Scheme = darkColorScheme(
    primary = LitaViolet, onPrimary = Color.White,
    secondary = LitaPink, onSecondary = Color.White,
    tertiary = LitaCyan, onTertiary = Color.Black,
    background = LitaBlack, onBackground = LitaTextHigh,
    surface = LitaSurface, onSurface = LitaTextHigh,
    surfaceVariant = LitaSurfaceHigh, onSurfaceVariant = LitaTextMid,
    error = LitaError, outline = Color(0xFF33333D),
)

@Composable
fun LitaTheme(language: String = "en", content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val family = remember(language) { AppFonts.family(ctx, language) }
    val typography = remember(family) { if (family == null) Typography() else Typography().withFamily(family) }
    SideEffect { I18n.language = language }
    CompositionLocalProvider(
        LocalAppLanguage provides language,
        LocalAppFont provides family,
        LocalLayoutDirection provides (if (AppLanguages.isRtl(language)) LayoutDirection.Rtl else LayoutDirection.Ltr),
    ) {
        MaterialTheme(colorScheme = Scheme, typography = typography) {
            // Without a Surface the default content colour is black, which made many texts invisible on the dark background.
            Surface(Modifier.fillMaxSize(), color = LitaBlack, contentColor = LitaTextHigh) { content() }
        }
    }
}

@Composable
fun litaSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White, checkedTrackColor = LitaViolet, checkedBorderColor = LitaViolet,
    uncheckedThumbColor = Color(0xFFC9C9D3), uncheckedTrackColor = Color(0xFF2A2A34), uncheckedBorderColor = Color(0xFF7A7A88),
    disabledCheckedThumbColor = Color(0xFFB8B8C2), disabledCheckedTrackColor = Color(0xFF4B3C80), disabledCheckedBorderColor = Color(0xFF4B3C80),
    disabledUncheckedThumbColor = Color(0xFF8A8A96), disabledUncheckedTrackColor = Color(0xFF1C1C24), disabledUncheckedBorderColor = Color(0xFF55555F),
)

/** The ribbon "L" mark, drawn with the same geometry as the launcher icon. */
@Composable
fun LitaLogo(height: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = height * 40f / 56f, height = height)) {
        val sx = size.width / 40f
        val sy = size.height / 56f
        fun p(x: Float, y: Float) = Offset((x - 36f) * sx, (y - 26f) * sy)
        val ribbon = Path().apply {
            moveTo(p(36f, 26f).x, p(36f, 26f).y); lineTo(p(52f, 26f).x, p(52f, 26f).y)
            lineTo(p(52f, 60f).x, p(52f, 60f).y); lineTo(p(76f, 60f).x, p(76f, 60f).y)
            lineTo(p(76f, 82f).x, p(76f, 82f).y); lineTo(p(36f, 82f).x, p(36f, 82f).y); close()
        }
        drawPath(ribbon, Brush.linearGradient(listOf(LitaPink, LitaViolet, LitaCyan), Offset.Zero, Offset(size.width, size.height)))
        val fold = Path().apply {
            moveTo(p(52f, 60f).x, p(52f, 60f).y); lineTo(p(64f, 71f).x, p(64f, 71f).y); lineTo(p(52f, 82f).x, p(52f, 82f).y); close()
        }
        drawPath(fold, Color(0x40000000))
    }
}
