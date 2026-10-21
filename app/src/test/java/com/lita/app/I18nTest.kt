package com.lita.app

import com.lita.app.i18n.AppLanguages
import com.lita.app.i18n.FaStrings
import com.lita.app.i18n.I18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class I18nTest {
    private fun fa(s: String) = I18n.tr(s, "fa")

    @Test fun englishIsTheDefaultAndPassesThrough() {
        assertEquals("Cancel", I18n.tr("Cancel", "en"))
        assertEquals("Cancel", I18n.tr("Cancel", "xx"))
    }

    @Test fun exactAndUnknownStrings() {
        assertEquals("لغو", fa("Cancel"))
        assertEquals("my_video 01.mp4", fa("my_video 01.mp4"))   // file names are never touched
        assertEquals("  لغو  ", fa("  Cancel  "))
    }

    @Test fun templatesTranslateNumbersAndNestedMessages() {
        assertEquals("قفل شده برای 12 ثانیه", fa("Locked for 12 s"))
        assertTrue(fa("Failed: Wrong recovery password, or the recovery data was modified.").startsWith("ناموفق: رمز بازیابی اشتباه"))
    }

    @Test fun concatenatedSentencesAreTranslatedOneByOne() {
        val out = fa("Imported 3 of 5 videos. 2 failed (first error: boom). Originals of failed files were not touched.")
        assertTrue(out, out.contains("3 ویدیو از 5 ویدیو ایمپورت شد."))
        assertTrue(out, out.contains("اولین خطا: boom"))
        assertFalse(out, out.contains("Imported"))
        val ex = fa("Exported to Movies/Lita Export/a.mp4. The encrypted original is unchanged.")
        assertTrue(ex, ex.startsWith("خروجی در Movies/Lita Export/a.mp4 ذخیره شد."))
    }

    @Test fun everyTemplateHasMatchingPlaceholders() {
        val idx = Regex("\\{(\\d+)\\}")
        for ((en, out) in FaStrings.templatePairs) {
            val n = Regex("\\{#\\}|\\{\\}").findAll(en).count()
            val used = idx.findAll(out).map { it.groupValues[1].toInt() }.toSet()
            assertEquals(en, (1..n).toSet(), used)
        }
    }

    @Test fun exactEntriesAreClean() {
        for ((k, v) in FaStrings.exact) {
            assertTrue(k, v.isNotBlank())
            assertFalse(k, v.contains("{") || v.contains("}"))
        }
    }

    @Test fun languagesList() {
        assertEquals(listOf("en", "fa"), AppLanguages.all.map { it.code })
        assertTrue(AppLanguages.isRtl("fa")); assertFalse(AppLanguages.isRtl("en"))
        assertEquals("فارسی", AppLanguages.nativeName("fa"))
    }
}
