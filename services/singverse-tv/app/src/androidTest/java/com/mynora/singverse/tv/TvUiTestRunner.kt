@file:Suppress("DEPRECATION")

package com.mynora.singverse.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.test.InstrumentationTestRunner
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Replaces only the instrumented launch; this class is absent from production APKs. */
class TvUiTestRunner : InstrumentationTestRunner() {
    override fun newActivity(loader: ClassLoader, className: String, intent: Intent): Activity =
        if (className == MainActivity::class.java.name && intent.hasExtra("tv-ui-fixture")) TvUiFixtureActivity()
        else super.newActivity(loader, className, intent)
}

/** Real production Compose screens, isolated in-memory data and loopback HTTP only. */
class TvUiFixtureActivity : ComponentActivity() {
    internal val focusMemory = TvFocusMemory()
    lateinit var app: TvApp
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isolated = object : ContextWrapper(this) {
            private val prefix = "tv-ui-test-${java.util.UUID.randomUUID()}-"
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)
        }
        app = TvApp(isolated).apply {
            client.base = intent.getStringExtra("tv-ui-fixture")!!
            client.token = "test-fixture-device-only"
            address = client.base
            connected = true
            online = true
            serverName = "UI 验收测试服务"
            snapshot = TvUiFixture.snapshot()
            homeSongs = if (intent.getBooleanExtra("tv-ui-playback", false)) TvPlaybackFixture.songs() else TvUiFixture.songs()
            if (intent.getBooleanExtra("tv-ui-playback", false)) tags = TvPlaybackFixture.classificationTags()
            songs = homeSongs
            homeTotal = homeSongs.size.toLong()
            total = homeTotal
            homeAppearance = TvUiFixture.appearance()
            ensureInvitation()
        }
        setContent { SingVerseTvTheme { TvScreen(app, focusMemory) } }
    }

    override fun onDestroy() {
        if (::app.isInitialized) app.close()
        super.onDestroy()
    }
}
