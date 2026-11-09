package com.eollas.sleepmonitor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import com.eollas.sleepmonitor.ui.AppRoot
import com.eollas.sleepmonitor.ui.MainViewModel
import com.eollas.sleepmonitor.ui.theme.SleepTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val showPrivacy = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            SleepTheme { AppRoot(vm, showPrivacy) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    private fun handle(i: Intent?) {
        val id = i?.getLongExtra(EXTRA_NIGHT, 0L) ?: 0L
        if (id != 0L) vm.openNight = id
        // Health Connect demande d'expliquer l'usage des données
        if (i?.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" ||
            i?.action == "android.intent.action.VIEW_PERMISSION_USAGE"
        ) showPrivacy.value = true
    }

    companion object { const val EXTRA_NIGHT = "night" }
}
