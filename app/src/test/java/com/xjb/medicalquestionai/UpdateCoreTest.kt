package com.xjb.medicalquestionai

import com.xjb.medicalquestionai.data.update.ReleaseAssetSelector
import com.xjb.medicalquestionai.data.update.UpdateManifestParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCoreTest {
    private fun manifestJson(versionCode: Long = 100001): String = """
        {
          "schemaVersion": 1,
          "applicationId": "com.xjb.medicalquestionai",
          "versionCode": $versionCode,
          "versionName": "1.2.0-test.1",
          "minSdk": 26,
          "apkAssetName": "medical-question-ai.apk",
          "apkSizeBytes": 100,
          "apkSha256": "${"a".repeat(64)}",
          "signingCertSha256": "${"b".repeat(64)}",
          "commitSha": "${"c".repeat(40)}",
          "publishedAt": "2026-10-29T00:00:00Z",
          "releaseNotes": "修复更新"
        }
    """.trimIndent()

    @Test
    fun updateManifestAcceptsStrictValidDocument() {
        val manifest = UpdateManifestParser.parse(manifestJson()).getOrThrow()
        assertEquals(100001L, manifest.versionCode)
        assertEquals(26, manifest.minSdk)
    }

    @Test
    fun updateManifestRejectsNullMissingWrongTypeAndUnknownSchema() {
        assertTrue(UpdateManifestParser.parse(manifestJson().replace("\"修复更新\"", "null")).isFailure)
        assertTrue(UpdateManifestParser.parse(manifestJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")).isFailure)
        assertTrue(UpdateManifestParser.parse(manifestJson().replace("\"versionCode\": 100001", "\"versionCode\": \"100001\"")).isFailure)
        assertTrue(UpdateManifestParser.parse("{}").isFailure)
    }

    @Test
    fun versionComparisonUsesNumericVersionCodeOnly() {
        assertTrue(UpdateManifestParser.isNewer(100002, 100001))
        assertTrue(!UpdateManifestParser.isNewer(100001, 100001))
        assertTrue(!UpdateManifestParser.isNewer(99999, 100001))
    }

    @Test
    fun releaseSelectorRequiresExactlyOneApkAndUpdateJsonInSameRelease() {
        val root = JSONObject(
            """
            {
              "tag_name": "v1.2.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name":"update.json","browser_download_url":"https://github.com/xjb1337/medical-question-ai/releases/download/v1.2.0/update.json"},
                {"name":"medical-question-ai.apk","browser_download_url":"https://github.com/xjb1337/medical-question-ai/releases/download/v1.2.0/medical-question-ai.apk"}
              ]
            }
            """.trimIndent()
        )
        val selected = ReleaseAssetSelector.select(root).getOrThrow()
        assertTrue(selected.updateJsonUrl.endsWith("/update.json"))
        assertTrue(selected.apkUrl.endsWith("/medical-question-ai.apk"))

        val duplicate = JSONObject(root.toString()).apply {
            getJSONArray("assets").put(
                JSONObject("""{"name":"medical-question-ai.apk","browser_download_url":"https://github.com/xjb1337/medical-question-ai/releases/download/v1.2.0/medical-question-ai.apk"}""")
            )
        }
        assertTrue(ReleaseAssetSelector.select(duplicate).isFailure)
    }

    @Test
    fun releaseSelectorRejectsDraftAndPrerelease() {
        val base = JSONObject(
            """
            {"tag_name":"v1.2.0","draft":false,"prerelease":false,"assets":[
              {"name":"update.json","browser_download_url":"https://github.com/xjb1337/medical-question-ai/releases/download/v1.2.0/update.json"},
              {"name":"medical-question-ai.apk","browser_download_url":"https://github.com/xjb1337/medical-question-ai/releases/download/v1.2.0/medical-question-ai.apk"}
            ]}
            """.trimIndent()
        )
        assertTrue(ReleaseAssetSelector.select(JSONObject(base.toString()).put("draft", true)).isFailure)
        assertTrue(ReleaseAssetSelector.select(JSONObject(base.toString()).put("prerelease", true)).isFailure)
    }
}
