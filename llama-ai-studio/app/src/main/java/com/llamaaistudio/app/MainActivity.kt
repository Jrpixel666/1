package com.llamaaistudio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.llamaaistudio.app.ui.nav.StudioNavHost
import com.llamaaistudio.app.ui.nav.StudioBottomBar
import com.llamaaistudio.app.ui.theme.LlamaAiStudioTheme
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LlamaAiStudioApp
        setContent {
            LlamaAiStudioTheme {
                StudioApp(app)
            }
        }
    }
}

@Composable
private fun StudioApp(app: LlamaAiStudioApp) {
    val navController = rememberNavController()
    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = { StudioBottomBar(navController) }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                StudioNavHost(navController = navController, app = app)
            }
        }
    }
}
