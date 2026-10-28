package com.xjb.medicalquestionai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xjb.medicalquestionai.ui.MedicalQuestionApp
import com.xjb.medicalquestionai.ui.theme.MedicalQuestionAiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MedicalQuestionAiTheme {
                MedicalQuestionApp()
            }
        }
    }
}
