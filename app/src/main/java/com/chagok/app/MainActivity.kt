package com.chagok.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.chagok.app.ui.ChagokScreen
import com.chagok.app.ui.ChagokTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = (application as ChagokApplication).engine
        setContent {
            ChagokTheme {
                ChagokScreen(engine)
            }
        }
    }
}
