package com.example.tpms.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseParserTest {

    private val releaseJson = """
        {"tag_name":"v1.13","name":"Deelife TPMS v1.13","body":"### Fixes\n- **Faster** pairing\n- `USB` retry",
         "html_url":"https://git.vhelectronics.com/vhadmin/Deelife-TPMS/releases/tag/v1.13",
         "assets":[
           {"name":"update.json","size":150,"browser_download_url":"https://example/update.json"},
           {"name":"Deelife-TPMS-v1.13.apk","size":10028237,"browser_download_url":"https://example/Deelife-TPMS-v1.13.apk"}
         ]}
    """.trimIndent()

    @Test
    fun parsesGiteaRelease() {
        val r = ReleaseParser.parseRelease(releaseJson)
        assertEquals("v1.13", r.tag)
        assertEquals(2, r.assets.size)
        assertEquals(10028237L, r.assets[1].size)
    }

    @Test
    fun manifestDrivesVersionAndChecksum() {
        val r = ReleaseParser.parseRelease(releaseJson)
        val m = ReleaseParser.parseManifest(
            """{"versionCode":13,"versionName":"1.13","apk":"Deelife-TPMS-v1.13.apk","sha256":"ABCDEF","minSdk":21}""")
        val info = ReleaseParser.buildUpdateInfo(r, m)!!
        assertEquals(13, info.versionCode)
        assertEquals("1.13", info.versionName)
        assertEquals("abcdef", info.sha256)
        assertEquals("https://example/Deelife-TPMS-v1.13.apk", info.apkUrl)
        assertTrue(ReleaseParser.isNewer(info, currentCode = 12, currentName = "1.12"))
        assertFalse(ReleaseParser.isNewer(info, currentCode = 13, currentName = "1.13"))
    }

    @Test
    fun withoutManifestFallsBackToTag() {
        val r = ReleaseParser.parseRelease(releaseJson)
        val info = ReleaseParser.buildUpdateInfo(r, null)!!
        assertEquals(-1, info.versionCode)
        assertEquals("1.13", info.versionName)
        assertNull(info.sha256)
        assertTrue(ReleaseParser.isNewer(info, currentCode = 12, currentName = "1.12"))
        assertFalse(ReleaseParser.isNewer(info, currentCode = 20, currentName = "1.20"))
    }

    @Test
    fun releaseWithoutApkIsIgnored() {
        val r = ReleaseParser.parseRelease("""{"tag_name":"v2.0","assets":[]}""")
        assertNull(ReleaseParser.buildUpdateInfo(r, null))
    }

    @Test
    fun compareVersionsIsNumeric() {
        assertTrue(ReleaseParser.compareVersions("1.12", "1.11") > 0)
        assertTrue(ReleaseParser.compareVersions("1.2", "1.11") < 0)
        assertTrue(ReleaseParser.compareVersions("1.2.1", "1.2") > 0)
        assertEquals(0, ReleaseParser.compareVersions("v1.3", "1.3"))
        assertEquals(0, ReleaseParser.compareVersions("1.3.0", "1.3"))
    }

    @Test
    fun notesAreStrippedOfMarkdown() {
        val notes = ReleaseParser.plainNotes("### Fixes\n- **Faster** pairing\n- `USB` retry")
        assertEquals("Fixes\n• Faster pairing\n• USB retry", notes)
    }
}
