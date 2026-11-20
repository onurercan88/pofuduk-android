package com.pofuduk.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.pofuduk.app.home.HomeScreen
import com.pofuduk.app.home.HomeViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG && savedInstanceState == null) applyDebugExtras()
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            HomeScreen(state, viewModel)
        }
    }

    /**
     * Debug builds only (store screenshots / art, see RELEASE_CHECKLIST.md):
     *   --es clock 2026-11-14T09:00   fixed local Istanbul time
     *   --es demo RAINY_AFTERNOON      labelled demo scenario
     *   --ez art true                  export store art to files/store-art
     */
    private fun applyDebugExtras() {
        intent.getStringExtra("clock")?.let { text ->
            runCatching { java.time.LocalDateTime.parse(text).atZone(java.time.ZoneId.of("Europe/Istanbul")).toInstant() }
                .getOrNull()?.let { fixed -> AppClock.now = { fixed } }
        }
        if (intent.getBooleanExtra("art", false)) android.util.Log.d("Pofuduk", "store art: ${StoreArt.export(this)}")
        intent.getStringExtra("demo")?.let { name ->
            runCatching { com.pofuduk.core.mock.MockWeatherData.Scenario.valueOf(name) }.getOrNull()?.let(viewModel::setDemo)
        }
        //   --es ask "Akşam yağmur var mı?"   --es travel Bursa   --ez settings true
        val ask = intent.getStringExtra("ask")
        val travel = intent.getStringExtra("travel")?.let { com.pofuduk.core.data.TurkishProvinces.search(it).firstOrNull() }
        val settings = intent.getBooleanExtra("settings", false)
        if (ask != null || travel != null || settings) {
            lifecycleScope.launch {
                viewModel.state.first { it.loadState == com.pofuduk.app.home.LoadState.Loaded && it.speechAvailable != null }
                kotlinx.coroutines.delay(1_500) // let the automatic briefing start first
                ask?.let(viewModel::ask)
                travel?.let {
                    viewModel.openTravel()
                    viewModel.selectDestination(it.place)
                    viewModel.showTravelWeather()
                }
                if (settings) viewModel.openSettings()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onStop() {
        super.onStop()
        // Never keep talking in the background.
        if (!isChangingConfigurations) viewModel.stop()
    }
}
