package com.lita.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lita.app.LitaViewModel
import com.lita.app.i18n.AppLanguages
import com.lita.app.ui.theme.LitaTextMid

/** Small language switcher (not a big button): globe icon + current language, opens a dropdown of available languages. */
@Composable
fun LanguageMenu(vm: LitaViewModel, modifier: Modifier = Modifier) {
    val lang by vm.language.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Language, contentDescription = "Language", modifier = Modifier.size(18.dp), tint = LitaTextMid)
            Spacer(Modifier.width(6.dp))
            Text(AppLanguages.nativeName(lang), color = LitaTextMid, fontSize = 13.sp)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = LitaTextMid)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (l in AppLanguages.all) {
                DropdownMenuItem(text = { Text(l.nativeName) }, onClick = { vm.setLanguage(l.code); open = false })
            }
        }
    }
}
