package com.eollas.sleepmonitor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eollas.sleepmonitor.ui.components.Glass
import com.eollas.sleepmonitor.ui.theme.Palette

@Composable
fun Card(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Glass(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = Palette.textDim, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
}

@Composable
fun Hint(text: String) {
    Text(text, color = Palette.textFaint, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Palette.text, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Palette.textFaint, fontSize = 12.sp, lineHeight = 16.sp)
        }
        trailing()
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Light)
        Text(label, color = Palette.textFaint, fontSize = 12.sp)
    }
}
