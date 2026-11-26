package com.xjb.medicalquestionai

import android.app.Application
import com.xjb.medicalquestionai.data.AppDatabase

class MedicalQuestionApplication : Application() {
    internal val database by lazy { AppDatabase.create(this) }
}
