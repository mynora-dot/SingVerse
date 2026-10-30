package com.mynora.singverse.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.tv.material3.MaterialTheme

class MainActivity : ComponentActivity() {
    private var app: TvApp? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SingVerseTvTheme { val state = remember { TvApp(this) }; app = state; TvScreen(state) } }
    }
    override fun onStart() { super.onStart(); app?.foreground() }
    override fun onStop() { app?.background(); super.onStop() }
    override fun onDestroy() { app?.close(); super.onDestroy() }
}
