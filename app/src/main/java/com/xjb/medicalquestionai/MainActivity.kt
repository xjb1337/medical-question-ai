package com.xjb.medicalquestionai

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjb.medicalquestionai.ui.MedicalQuestionApp
import com.xjb.medicalquestionai.ui.theme.MedicalQuestionAiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val settings = mainViewModel.settings.collectAsStateWithLifecycle()
            MedicalQuestionAiTheme(themeMode = settings.value.themeMode) {
                MedicalQuestionApp(viewModel = mainViewModel)
            }
        }
    }
}
