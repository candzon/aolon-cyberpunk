package com.feri.aolon_cyberpunk

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.feri.aolon_cyberpunk.lyfta.LyftaClient
import com.feri.aolon_cyberpunk.ui.AolonApp
import com.feri.aolon_cyberpunk.ui.AolonTheme

class MainActivity : ComponentActivity() {
    private val vm: AolonViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleRedirect(intent)
        // The canvas is always dark, so system bar icons stay light regardless of the phone theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { AolonTheme { AolonApp(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.toString().startsWith(LyftaClient.REDIRECT)) vm.finishLyfta(data)
    }
}
