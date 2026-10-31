package com.xjb.medicalquestionai.data

enum class ThemeMode(
    val storageValue: String,
    val label: String,
    val description: String
) {
    SYSTEM("system", "跟随系统", "根据手机设置自动切换"),
    LIGHT("light", "白天", "始终使用浅色界面"),
    DARK("dark", "黑夜", "始终使用深色界面");

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}
