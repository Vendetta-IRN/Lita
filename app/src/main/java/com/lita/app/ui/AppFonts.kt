package com.lita.app.ui

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Optional custom fonts, looked up by name in res/font (any .ttf or .otf):
 *   lita_font_en  (+ optional lita_font_en_bold)  -> English UI
 *   lita_font_fa  (+ optional lita_font_fa_bold)  -> Persian UI
 * If a file is missing the system font is used, so the app builds and runs without them.
 */
object AppFonts {
    private fun id(ctx: Context, name: String) = ctx.resources.getIdentifier(name, "font", ctx.packageName)

    fun family(ctx: Context, lang: String): FontFamily? {
        val base = if (lang == "fa") "lita_font_fa" else "lita_font_en"
        val regular = id(ctx, base).takeIf { it != 0 } ?: return null
        val bold = id(ctx, base + "_bold").takeIf { it != 0 }
        return if (bold != null) FontFamily(Font(regular, FontWeight.Normal), Font(bold, FontWeight.Bold))
        else FontFamily(Font(regular, FontWeight.Normal))   // bold is synthesized from the regular file
    }
}

fun Typography.withFamily(f: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f), displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f), headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f), titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f), bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f), labelSmall = labelSmall.copy(fontFamily = f),
)
