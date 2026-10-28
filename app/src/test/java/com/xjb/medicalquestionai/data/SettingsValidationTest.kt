package com.xjb.medicalquestionai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsValidationTest {
    @Test
    fun acceptsHttpsBaseUrl() {
        assertNull(SettingsRepository.validateBaseUrl("https://api.deepseek.com"))
        assertNull(SettingsRepository.validateBaseUrl("https://gateway.example.test/v1"))
        assertNull(SettingsRepository.validateBaseUrl("https://127.0.0.1:8443"))
    }

    @Test
    fun rejectsHttpForAndroidCleartextPolicy() {
        assertNotNull(SettingsRepository.validateBaseUrl("http://api.deepseek.com"))
        assertNotNull(SettingsRepository.validateBaseUrl("http://127.0.0.1:8080"))
    }

    @Test
    fun rejectsBlankMalformedAndUnsupportedSchemes() {
        assertNotNull(SettingsRepository.validateBaseUrl(""))
        assertNotNull(SettingsRepository.validateBaseUrl("api.deepseek.com"))
        assertNotNull(SettingsRepository.validateBaseUrl("ftp://api.deepseek.com"))
        assertNotNull(SettingsRepository.validateBaseUrl("https://"))
    }

    @Test
    fun normalizesWhitespaceAndTrailingSlash() {
        assertEquals("https://api.deepseek.com", SettingsRepository.normalizeBaseUrl(" https://api.deepseek.com/ "))
    }
}
