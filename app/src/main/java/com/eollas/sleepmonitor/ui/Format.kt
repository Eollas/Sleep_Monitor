package com.eollas.sleepmonitor.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {
    private val fr = Locale.FRANCE
    fun hm(min: Int): String = if (min < 60) "$min min" else "%d h %02d".format(min / 60, min % 60)
    fun clock(t: Long?): String = if (t == null) "–" else SimpleDateFormat("HH:mm", fr).format(Date(t))
    fun day(t: Long): String = SimpleDateFormat("EEE d MMM", fr).format(Date(t)).replaceFirstChar { it.uppercase() }
    fun dayShort(t: Long): String = SimpleDateFormat("EEE", fr).format(Date(t)).take(1).uppercase()

    /** « Nuit du lun. 9 au mar. 10 nov. » */
    fun nightTitle(start: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = start }
        // Un suivi lancé après minuit appartient à la nuit de la veille
        if (c.get(Calendar.HOUR_OF_DAY) < 12) c.add(Calendar.DAY_OF_MONTH, -1)
        val a = c.time
        c.add(Calendar.DAY_OF_MONTH, 1)
        val b = c.time
        return "Nuit du " + SimpleDateFormat("EEE d", fr).format(a) + " au " + SimpleDateFormat("EEE d MMM", fr).format(b)
    }

    fun duration(ms: Long): String {
        val s = (ms / 1000).toInt()
        return if (s < 60) "$s s" else "${s / 60} min ${"%02d".format(s % 60)}"
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 * 1024 -> "${bytes / 1024} Ko"
        bytes < 1024L * 1024 * 1024 -> "%.1f Mo".format(bytes / 1048576.0)
        else -> "%.2f Go".format(bytes / 1073741824.0)
    }

    fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..11 -> "Bonjour"
            in 12..17 -> "Bon après-midi"
            in 18..21 -> "Bonsoir"
            else -> "Bonne nuit"
        }
    }
}
