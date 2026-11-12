package com.eollas.sleepmonitor.data

import android.content.Context
import com.eollas.sleepmonitor.BuildConfig
import com.eollas.sleepmonitor.tracking.Labels
import com.eollas.sleepmonitor.tracking.SoundRules
import com.eollas.sleepmonitor.tracking.YamnetNames
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export de toutes les nuits dans un fichier ZIP (tableaux CSV + extraits audio).
 * Le fichier est créé dans le cache de l'app ; c'est l'utilisateur qui choisit
 * ensuite où l'envoyer (menu Partager d'Android). Rien n'est envoyé automatiquement.
 *
 * CSV au format Excel français : séparateur « ; », virgule décimale.
 */
class Exporter(private val ctx: Context, private val repo: Repository) {
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)
    private val fileFmt = SimpleDateFormat("yyyy-MM-dd_HH'h'mm", Locale.FRANCE)

    suspend fun export(): File {
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "SleepMonitor_${fileFmt.format(Date())}.zip")
        val dao = repo.dao
        val nights = dao.allNights()
        val names = YamnetNames.get(ctx)
        val s = repo.settings.current()
        // Lecture de la base avant d'écrire le fichier (quelques milliers de lignes par nuit)
        val epochs = nights.associate { it.id to dao.epochs(it.id) }
        val events = nights.associate { it.id to dao.events(it.id) }

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            val w = zip.writer(Charsets.UTF_8)
            fun entry(name: String, body: (Writer) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                w.write("﻿") // BOM : Excel reconnaît l'UTF-8 (accents)
                body(w)
                w.flush()
                zip.closeEntry()
            }

            entry("LISEZMOI.txt") { it.write(readme(nights.size, s)) }

            entry("nuits.csv") { o ->
                o.row(
                    "nuit_id", "coucher", "fin_suivi", "endormissement", "reveil", "sommeil_min", "profond_min",
                    "leger_min", "eveil_min", "latence_min", "reveils", "efficacite_pct", "score", "ronflement_min",
                    "paroles", "toux", "bruits", "pauses_resp", "db_moyen", "micro_actif", "terminee", "interrompue",
                    "mi_source", "mi_sommeil_min", "mi_profond_min", "mi_leger_min", "mi_paradoxal_min", "mi_eveil_min",
                    "mi_debut", "mi_fin", "fc_moyenne", "fc_min", "version_analyse",
                )
                for (n in nights) o.row(
                    n.id, d(n.startTime), d(n.endTime), d(n.sleepOnset), d(n.wakeTime), n.totalSleepMin, n.deepMin,
                    n.lightMin, n.awakeMin, n.latencyMin, n.awakenings, n.efficiency, n.score, n.snoreMin,
                    n.talkCount, n.coughCount, n.noiseCount, n.apneaCount, f(n.avgDb), b(n.audioEnabled), b(n.finished),
                    b(n.interrupted), n.extSource, n.extSleepMin, n.extDeepMin, n.extLightMin, n.extRemMin, n.extAwakeMin,
                    d(n.extStart), d(n.extEnd), n.hrAvg, n.hrMin, n.analysisVersion,
                )
            }

            entry("tranches_30s.csv") { o ->
                o.row("nuit_id", "debut", "debut_ms", "mouvement_mg", "db_moyen", "db_max", "phase", "ecran_allume")
                for (n in nights) for (e in epochs[n.id].orEmpty()) o.row(
                    n.id, d(e.t), e.t, f(e.activity), f(e.db), f(e.maxDb), stage(e.stage), b(e.screenOn),
                )
            }

            val clips = mutableListOf<Pair<String, File>>()
            entry("sons.csv") { o ->
                o.row(
                    "son_id", "nuit_id", "debut", "fin", "debut_ms", "duree_s", "categorie", "nom", "confiance",
                    "db_max", "ecart_fond_db", "top1", "top1_score", "top2", "top2_score", "top3", "top3_score",
                    "moment_de_la_nuit", "extrait",
                )
                for (n in nights) for (e in events[n.id].orEmpty()) {
                    val top = SoundRules.decodeTop(e.top3)
                    val cols = (0 until 3).flatMap { k ->
                        val t = top.getOrNull(k)
                        listOf(t?.let { "${Labels.name(it.first, names)} (${it.first})" }, t?.let { f(it.second, 3) })
                    }
                    val clip = e.clipPath?.let(::File)?.takeIf { it.exists() }
                    val clipName = clip?.let { "extraits/${n.id}/${it.name}" }
                    if (clip != null) clips += clipName!! to clip
                    o.row(
                        e.id, n.id, d(e.start), d(e.end), e.start, f((e.end - e.start) / 1000f, 1),
                        SoundCategory.of(e.category).label, e.label, f(e.confidence, 3), f(e.peakDb), f(e.aboveDb),
                        *cols.toTypedArray(), b(e.highlight), clipName,
                    )
                }
            }

            // Extraits audio encore présents (moments de la nuit), copiés tels quels
            for ((name, file) in clips) {
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    private fun readme(count: Int, s: AppSettings) = """
        Sleep Monitor ${BuildConfig.VERSION_NAME} : export du ${dateFmt.format(Date())}
        $count nuit(s).

        Réglages au moment de l'export :
        - sensibilité aux bruits : ${(s.soundSensitivity * 100).toInt()} % (0 = moins sensible, 100 = plus sensible)
        - sensibilité aux mouvements : ${listOf("faible", "normale", "élevée").getOrElse(s.sensitivity) { "?" }}

        Fichiers (format Excel français : séparateur « ; », virgule décimale) :
        - nuits.csv : une ligne par nuit (récapitulatif, données Mi Fitness si disponibles).
        - tranches_30s.csv : mouvement (milli-g), niveau sonore (dB approximatifs) et phase, toutes les 30 s.
        - sons.csv : chaque son détecté, avec les 3 catégories les plus probables selon le modèle YAMNet
          (de la plus à la moins probable, numéro de catégorie entre parenthèses) et l'écart au bruit de fond.
          Les sons enregistrés avant cette version n'ont pas ces informations.
        - extraits/ : les extraits audio encore conservés (moments de la nuit), nommés par l'heure de début
          en millisecondes (colonne debut_ms de sons.csv).
    """.trimIndent()

    private fun d(t: Long?): String? = t?.let { dateFmt.format(Date(it)) }
    private fun f(v: Float, digits: Int = 2): String = String.format(Locale.FRANCE, "%.${digits}f", v)
    private fun b(v: Boolean) = if (v) "1" else "0"
    private fun stage(s: Int) = when (s) {
        Stage.AWAKE -> "eveil"; Stage.LIGHT -> "leger"; Stage.DEEP -> "profond"; else -> ""
    }

    /** Écrit une ligne CSV (valeurs nulles = case vide, texte protégé par des guillemets si besoin). */
    private fun Writer.row(vararg v: Any?) {
        write(v.joinToString(";") { x ->
            val t = x?.toString().orEmpty()
            if (t.any { it == ';' || it == '"' || it == '\n' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
        })
        write("\r\n")
    }
}
