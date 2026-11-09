package com.eollas.sleepmonitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.InsertChartOutlined
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eollas.sleepmonitor.ui.components.Backdrop
import com.eollas.sleepmonitor.ui.components.Glass
import com.eollas.sleepmonitor.ui.components.LocalBackdrop
import com.eollas.sleepmonitor.ui.screens.HomeScreen
import com.eollas.sleepmonitor.ui.screens.NightDetailScreen
import com.eollas.sleepmonitor.ui.screens.NightsScreen
import com.eollas.sleepmonitor.ui.screens.SettingsScreen
import com.eollas.sleepmonitor.ui.theme.Palette

enum class Tab(val label: String) { SLEEP("Sommeil"), NIGHTS("Mes nuits"), SETTINGS("Réglages") }

@Composable
fun AppRoot(vm: MainViewModel, showPrivacy: MutableState<Boolean>) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val backdrop = remember { Backdrop() }
    backdrop.id = settings.wallpaper
    backdrop.image = vm.customWallpaper
    backdrop.dim = settings.dim
    var tab by rememberSaveable { mutableStateOf(Tab.SLEEP) }

    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { backdrop.rootSize = Size(it.width.toFloat(), it.height.toFloat()) },
        ) {
            Canvas(Modifier.fillMaxSize()) { backdrop.draw(this) }

            val open = vm.openNight
            if (open != null) {
                BackHandler { vm.openNight = null }
                NightDetailScreen(vm, open, onBack = { vm.openNight = null })
            } else {
                AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                    when (t) {
                        Tab.SLEEP -> HomeScreen(vm)
                        Tab.NIGHTS -> NightsScreen(vm)
                        Tab.SETTINGS -> SettingsScreen(vm)
                    }
                }
                if (tab != Tab.SLEEP) BackHandler { tab = Tab.SLEEP }
                BottomBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
            }
        }
        if (showPrivacy.value) PrivacyDialog { showPrivacy.value = false }
    }
}

@Composable
private fun BottomBar(tab: Tab, onSelect: (Tab) -> Unit, modifier: Modifier) {
    Glass(
        modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp).fillMaxWidth().height(76.dp),
        shape = RoundedCornerShape(38.dp),
        tint = Color(0x55101528),
    ) {
        Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (t in Tab.entries) {
                val sel = t == tab
                val icon = when (t) {
                    Tab.SLEEP -> Icons.Outlined.Bedtime
                    Tab.NIGHTS -> Icons.Outlined.InsertChartOutlined
                    Tab.SETTINGS -> Icons.Outlined.Tune
                }
                val content: @Composable () -> Unit = {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(icon, null, tint = if (sel) Palette.accent else Palette.textDim, modifier = Modifier.size(22.dp))
                        Text(
                            t.label, fontSize = 13.sp,
                            color = if (sel) Palette.accent else Palette.textDim,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                if (sel) {
                    Glass(Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(32.dp), tint = Color(0x33101528)) { content() }
                } else {
                    Box(
                        Modifier.weight(1f).fillMaxSize().clickableNoRipple { onSelect(t) },
                    ) { content() }
                }
            }
        }
    }
}

@Composable
fun PrivacyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Compris") } },
        title = { Text("Vos données restent sur le téléphone") },
        text = {
            Text(
                "Sleep Monitor n'a pas d'accès Internet. Les mouvements, les sons et les nuits sont " +
                    "enregistrés uniquement dans la mémoire privée de l'application.\n\n" +
                    "Health Connect sert à lire le sommeil et le rythme cardiaque mesurés par Mi Fitness, " +
                    "et à y ajouter vos nuits. Health Connect est lui aussi stocké sur le téléphone.",
            )
        },
    )
}
