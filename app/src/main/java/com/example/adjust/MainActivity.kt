package com.example.adjust

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.adjust.helper.AdjustBridge
import com.adjust.helper.model.FullAdsOption
import com.example.adjust.ui.theme.AdjustTheme

class MainActivity : ComponentActivity() {
    private fun initAdjust() {
        AdjustBridge.apply {
            appToken = "ll2x0zf75wqo"
            environment = "sandbox"
            fullAdsOption = FullAdsOption()
            apiToken = "y8URWesoZ2FJ-usa-kD3"
            fullAdCallback = { isFullAds, network, fromCache ->
                Log.i(
                    "AdjustFullAdCallback",
                    "isFullAds: $isFullAds, network: $network, fromCache: $fromCache"
                )
            }
            impressionEventToken = "gdxcgx"
        }
        AdjustBridge.initialize(
            applicationContext
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initAdjust()
        setContent {
            AdjustTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AdjustTheme {
        Greeting("Android")
    }
}