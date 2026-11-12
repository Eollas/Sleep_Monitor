package com.eollas.sleepmonitor.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eollas.sleepmonitor.BuildConfig
import com.eollas.sleepmonitor.ui.Fmt
import com.eollas.sleepmonitor.ui.MainViewModel
import com.eollas.sleepmonitor.ui.clickableNoRipple
import com.eollas.sleepmonitor.ui.components.GlassButton
import com.eollas.sleepmonitor.ui.components.GlassChip
import com.eollas.sleepmonitor.ui.components.SoftSwitch
import com.eollas.sleepmonitor.ui.components.Wallpapers
import com.eollas.sleepmonitor.ui.theme.Palette
import java.io.File
import kotlin.math.roundToInt

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    // Taille des enregistrements recalculée à chaque ouverture de la page
    LaunchedEffect(Unit) { vm.refreshClips() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setCustomWallpaper(uri)
    }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        vm.refreshHc()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
            .navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Réglages", color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Light)

        Card(title = "Fond d'écran") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((id, name) in Wallpapers.presets) {
                    WallpaperThumb(name, s.wallpaper == id, { vm.setWallpaper(id) }) { size ->
                        Canvas(Modifier.fillMaxSize()) { Wallpapers.draw(this, id, null, 0f, Size(this.size.width * 2.2f, this.size.height)) }
                    }
                }
                WallpaperThumb("Ma photo", s.wallpaper == "custom", {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    val img = vm.customWallpaper
                    if (img != null) {
                        Canvas(Modifier.fillMaxSize()) { Wallpapers.draw(this, "custom", img, 0f, this.size) }
                    } else {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, tint = Palette.textDim, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
            if (s.wallpaper == "custom") {
                Text("Changer de photo", color = Palette.accent, fontSize = 14.sp, modifier = Modifier.clickableNoRipple {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
            }
            Text("Assombrir le fond", color = Palette.textDim, fontSize = 14.sp)
            Slider(
                value = s.dim, onValueChange = { vm.setDim(it) }, valueRange = 0f..0.8f,
                colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accentDeep, inactiveTrackColor = Color.White.copy(alpha = 0.12f)),
            )
        }

        Card(title = "Sons de la nuit") {
            SettingRow("Enregistrer les bruits", "Ronflements, paroles, toux et bruits perturbants") {
                SoftSwitch(s.audioEnabled) { if (!live.running) vm.setAudio(it) }
            }
            Text("Conserver les extraits sonores", color = Palette.textDim, fontSize = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((d, label) in listOf(3 to "3 jours", 7 to "7 jours", 14 to "14 jours", 30 to "30 jours", 90 to "90 jours", 0 to "Toujours")) {
                    GlassChip(label, s.retentionDays == d, { vm.setRetention(d) })
                }
            }
            Hint("Les extraits plus anciens sont effacés automatiquement. Les statistiques des nuits sont gardées.")
            Text("Sensibilité aux bruits", color = Palette.textDim, fontSize = 14.sp)
            // Valeur locale pendant le glissement : enregistrée seulement au lâcher du curseur
            var sound by remember(s.soundSensitivity) { mutableFloatStateOf(s.soundSensitivity) }
            Slider(
                value = sound, onValueChange = { sound = it }, onValueChangeFinished = { vm.setSoundSensitivity(sound) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accentDeep, inactiveTrackColor = Color.White.copy(alpha = 0.12f)),
            )
            Row(Modifier.fillMaxWidth()) {
                Text("Moins sensible", color = Palette.textFaint, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("${(sound * 100).roundToInt()} %", color = Palette.text, fontSize = 12.sp)
                Text("Plus sensible", color = Palette.textFaint, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            Hint(
                "Moins sensible : seuls les sons nets et répétés sont retenus. Plus sensible : les sons faibles " +
                    "ou brefs aussi, avec plus de fausses détections. Pris en compte à la prochaine nuit.",
            )
            val c = vm.clips
            SettingRow(
                "Place utilisée par les enregistrements : ${Fmt.size(c.bytes)}",
                if (c.count == 0) "Aucun extrait audio sur le téléphone"
                else "${c.count} extrait${if (c.count > 1) "s" else ""} sur ${c.nights} nuit${if (c.nights > 1) "s" else ""}",
            ) {
                GlassButton("Effacer", { confirm = "clips" })
            }
        }

        Card(title = "Détection du sommeil") {
            Text("Sensibilité aux mouvements", color = Palette.textDim, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((v, label) in listOf(0 to "Faible", 1 to "Normale", 2 to "Élevée")) {
                    GlassChip(label, s.sensitivity == v, { vm.setSensitivity(v) })
                }
            }
            Hint("Plus elle est élevée, plus de petits mouvements comptent comme de l'éveil.")
            SettingRow(
                "Arrêt automatique au réveil",
                "Quand vous prenez le téléphone en main le matin (après au moins 3 h de sommeil)",
            ) { SoftSwitch(s.autoStop) { vm.setAutoStop(it) } }
        }

        Card(title = "Mi Fitness (Health Connect)") {
            if (!vm.hcAvailable) {
                Hint("Health Connect n'est pas disponible sur ce téléphone.")
            } else {
                val ok = vm.hcGranted.containsAll(vm.repo.health.permissions)
                Hint(
                    "Mi Fitness envoie le sommeil et le rythme cardiaque de votre montre dans Health Connect, " +
                        "sur le téléphone. Sleep Monitor les lit pour compléter le récapitulatif.",
                )
                SettingRow(if (ok) "Accès autorisé" else "Accès non autorisé", null) {
                    GlassButton(if (ok) "Gérer" else "Autoriser", {
                        if (ok) runCatching { ctx.startActivity(vm.repo.health.settingsIntent()) }
                        else hcLauncher.launch(vm.repo.health.permissions)
                    }, accent = !ok)
                }
                SettingRow("Ajouter mes nuits dans Health Connect", "Visibles ensuite dans les autres apps santé") {
                    SoftSwitch(s.hcWrite) { vm.setHcWrite(it) }
                }
                Hint(
                    "Dans Mi Fitness : Profil → Paramètres → Health Connect (ou « Applications tierces ») → " +
                        "activer le partage du sommeil et du rythme cardiaque.",
                )
            }
        }

        Card(title = "Batterie (Redmagic)") {
            SettingRow(
                if (vm.batteryOk) "Optimisation désactivée" else "Optimisation de batterie active",
                if (vm.batteryOk) "L'app peut tourner toute la nuit" else "Android risque d'arrêter le suivi la nuit",
            ) {
                if (!vm.batteryOk) GlassButton("Régler", {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
                    }
                }, accent = true)
            }
            Hint(
                "Sur Redmagic, vérifiez aussi : Paramètres → Applications → Sleep Monitor → Batterie → " +
                    "« Aucune restriction » / autoriser l'activité en arrière-plan. Vous pouvez aussi verrouiller " +
                    "l'app dans les applications récentes (glisser vers le bas sur sa carte).",
            )
            GlassButton("Ouvrir les réglages de l'app", {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                }
            }, Modifier.fillMaxWidth())
        }

        UpdateCard(vm)

        Card(title = "Mes données") {
            Hint(
                "Crée un fichier ZIP avec toutes vos nuits (tableaux lisibles dans Excel) et les extraits audio " +
                    "encore conservés. Vous choisissez ensuite où l'envoyer : rien ne part sans votre action.",
            )
            GlassButton(
                if (vm.exporting) "Préparation…" else "Exporter mes données",
                { vm.exportData { f -> shareFile(ctx, f) } }, Modifier.fillMaxWidth(),
            )
            vm.exportError?.let { Text(it, color = Palette.warn, fontSize = 13.sp) }
        }

        Card(title = "Confidentialité") {
            Hint(
                "Tout reste sur ce téléphone : nuits, sons et données de santé ne sont jamais envoyés. " +
                    "Internet sert uniquement à vérifier et télécharger les mises à jour sur GitHub. " +
                    "La sauvegarde cloud est désactivée.",
            )
            GlassButton("Effacer toutes les données", { confirm = "all" }, Modifier.fillMaxWidth())
        }

        Text("Version ${BuildConfig.VERSION_NAME}", color = Palette.textFaint, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
    }

    when (confirm) {
        "clips" -> ConfirmDialog("Effacer tous les extraits sonores ?", "Les statistiques des nuits sont conservées.", { confirm = null }) {
            vm.deleteAllClips()
        }
        "all" -> ConfirmDialog("Effacer toutes les données ?", "Toutes les nuits, statistiques et extraits seront supprimés définitivement.", { confirm = null }) {
            vm.deleteEverything()
        }
    }
}

/** Ouvre le menu Partager d'Android avec le fichier d'export. */
private fun shareFile(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".export", f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
        clipData = ClipData.newRawUri(f.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(Intent.createChooser(send, "Exporter mes données")) }
}

@Composable
private fun WallpaperThumb(name: String, selected: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.BoxScope.(Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(width = 70.dp, height = 110.dp).clip(RoundedCornerShape(18.dp))
                .border(if (selected) 2.dp else 1.dp, if (selected) Palette.accent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
                .clickableNoRipple(onClick),
        ) { content(0) }
        Text(name, color = if (selected) Palette.accent else Palette.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Effacer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun UpdateCard(vm: MainViewModel) {
    val u = vm.update
    Card(title = "Mise à jour") {
        val r = u.release
        SettingRow(
            when {
                u.progress != null -> "Téléchargement… ${(u.progress * 100).toInt()} %"
                r != null -> "Nouvelle version ${r.versionName} disponible"
                u.checking -> "Vérification…"
                u.checked && u.error == null -> "L'application est à jour"
                else -> "Version ${BuildConfig.VERSION_NAME}"
            },
            if (r != null) "Version actuelle : ${BuildConfig.VERSION_NAME}. Vos nuits sont conservées." else null,
        ) {
            if (r != null) GlassButton("Installer", { vm.installUpdate() }, accent = true)
            else GlassButton("Vérifier", { vm.checkUpdate() })
        }
        if (u.error != null) Text(u.error, color = Palette.warn, fontSize = 13.sp)
        Hint(
            "La première fois, Android demande d'autoriser Sleep Monitor à installer des applications : " +
                "acceptez puis revenez toucher « Installer ».",
        )
    }
}
