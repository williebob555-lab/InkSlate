package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update check is the one piece of InkSlate a person cannot verify by looking at the screen:
 * if the comparison is wrong they are simply never told a new build exists, silently and
 * forever. The arithmetic is pinned down here rather than trusted.
 */
class UpdateCheckTest {

    private fun v(raw: String) = Version.parse(raw) ?: error("did not parse: $raw")

    // ------------------------------------------------------------------ parsing

    @Test
    fun `a leading v is a tag convention, not part of the number`() {
        assertEquals(v("1.2.3"), v("v1.2.3"))
    }

    @Test
    fun `a pre-release label is kept separately from the numbers`() {
        val parsed = v("v1.3.0-beta.1")
        assertEquals(listOf(1, 3, 0), parsed.parts)
        assertEquals("beta.1", parsed.preRelease)
    }

    @Test
    fun `something with no number in it does not parse`() {
        assertNull(Version.parse("latest"))
        assertNull(Version.parse(""))
    }

    // ------------------------------------------------------------------ ordering

    @Test
    fun `ten is newer than nine, not older`() {
        // The whole point of comparing numerically. As strings, "1.10" sorts before "1.9".
        assertTrue(v("1.10") > v("1.9"))
    }

    @Test
    fun `a missing component reads as zero`() {
        assertEquals(0, v("1.2").compareTo(v("1.2.0")))
    }

    @Test
    fun `a finished release is newer than its own pre-release`() {
        assertTrue(v("1.3.0") > v("1.3.0-beta"))
    }

    @Test
    fun `a pre-release is still newer than the version before it`() {
        assertTrue(v("1.3.0-beta") > v("1.2.9"))
    }

    // ------------------------------------------------------------------ payloads

    private val payload = """
        {
          "tag_name": "v1.4.0",
          "name": "InkSlate 1.4.0",
          "body": "Faster saving.",
          "html_url": "https://github.com/o/r/releases/tag/v1.4.0",
          "published_at": "2026-09-08T00:00:00Z",
          "assets": [
            {
              "name": "InkSlate-1.4.0.apk",
              "browser_download_url": "https://example.invalid/InkSlate-1.4.0.apk",
              "size": 30000000,
              "content_type": "application/vnd.android.package-archive"
            },
            {
              "name": "InkSlate-1.4.0.msi",
              "browser_download_url": "https://example.invalid/InkSlate-1.4.0.msi",
              "size": 80000000
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `the fields we care about survive a real release payload`() {
        val release = UpdateCheck.parseRelease(payload)!!
        assertEquals(v("1.4.0"), release.version)
        assertEquals("InkSlate 1.4.0", release.title)
        assertEquals("Faster saving.", release.notes)
        assertEquals(2, release.assets.size)
    }

    @Test
    fun `unknown fields in the payload are ignored rather than fatal`() {
        // GitHub adds fields to this response without warning; a strict parser would turn that
        // into "update check broken" for everyone at once.
        val release = UpdateCheck.parseRelease(payload)
        assertTrue(release != null)
    }

    // ---- the test channel ----------------------------------------------------

    /**
     * Test builds are numbered by CI run, so they cross 9 to 10 quickly.
     *
     * A plain string comparison puts `test.9` after `test.10`, which would offer the tenth build
     * once and then go quiet as the numbers grew past it - a channel that silently stops working
     * is worse than one that never did.
     */
    @Test
    fun `test builds are ordered by number, not alphabetically`() {
        val ninth = Version.parse("1.1.1-test.9")!!
        val tenth = Version.parse("1.1.1-test.10")!!
        assertTrue(ninth < tenth)
    }

    /** A finished release always beats any test build of the same version. */
    @Test
    fun `a finished release outranks its own test builds`() {
        assertTrue(Version.parse("1.1.1-test.40")!! < Version.parse("1.1.1")!!)
    }

    /**
     * The point of numbering a test build as the *next* patch.
     *
     * Numbering it as the current one would make it sort below the release it comes after, and
     * anyone on the test channel would be told they were already up to date.
     */
    @Test
    fun `a test build sorts above the release it follows`() {
        assertTrue(Version.parse("1.1.0")!! < Version.parse("1.1.1-test.1")!!)
    }

    @Test
    fun `the newest release is chosen by version rather than by publication order`() {
        val older = UpdateCheck.Release(
            Version.parse("1.2.0")!!, "1.2.0", "", "", emptyList()
        )
        val patch = UpdateCheck.Release(
            Version.parse("1.0.4")!!, "1.0.4", "", "", emptyList()
        )
        // GitHub lists by date, so a late patch to an old line comes first.
        assertEquals(older, UpdateCheck.newestOf(listOf(patch, older)))
    }

    // ---- reading a release ------------------------------------------------------

    private fun releaseJson(tag: String, name: String, assets: List<String> = emptyList()) = """
        {
          "tag_name": "$tag",
          "name": "$name",
          "body": "notes",
          "html_url": "https://example/releases",
          "assets": [${assets.joinToString(",") {
              """{"name":"$it","browser_download_url":"https://example/$it","size":1}"""
          }}]
        }
    """.trimIndent()

    /**
     * The failure this test exists for.
     *
     * Test builds are published onto one moving `test` tag, so the tag has no version in it and
     * the name has. Reading only the tag dropped every test build, and a device on the test
     * channel was told it was up to date because the only releases left were stable ones it had
     * already passed.
     */
    @Test
    fun `a test build is recognised from its name when the tag has no version`() {
        val releases = UpdateCheck.parseReleases(
            "[" + releaseJson("test", "Test build 1.1.1-test.22") + "]"
        )

        assertEquals(1, releases.size)
        assertEquals(Version.parse("1.1.1-test.22"), releases.single().version)
    }

    @Test
    fun `the newest test build wins over the release it follows`() {
        val releases = UpdateCheck.parseReleases(
            "[" + releaseJson("test", "Test build 1.1.1-test.22") + "," +
                releaseJson("v1.1.0", "InkSlate 1.1.0") + "]"
        )

        assertEquals(Version.parse("1.1.1-test.22"), UpdateCheck.newestOf(releases)?.version)
    }

    @Test
    fun `a release with no version anywhere is left out rather than guessed at`() {
        val releases = UpdateCheck.parseReleases(
            "[" + releaseJson("nightly", "Spring edition") + "]"
        )

        assertTrue(releases.isEmpty())
    }

    /** A year is not a version, however much it looks like a number. */
    @Test
    fun `a name with a bare number in it is not mistaken for a version`() {
        assertNull(Version.findIn("Spring 2026 edition"))
        assertEquals(Version.parse("2.3"), Version.findIn("InkSlate 2.3 (beta)"))
    }

    // ---- which file comes down ------------------------------------------------

    private fun asset(name: String) = UpdateCheck.Asset(name, "https://example/$name", 1)

    private fun release(version: String, vararg names: String) = UpdateCheck.Release(
        Version.parse(version)!!, version, "", "", names.map(::asset)
    )

    /**
     * The test channel publishes onto one moving tag, so its release accumulates the files of
     * earlier builds. Taking the first match would install the *oldest* of them - which is the
     * opposite of what asking for test builds is for.
     */
    @Test
    fun `the file matching the version wins over the ones left behind by earlier builds`() {
        val r = release(
            "1.1.1-test.11",
            "InkSlate-1.1.1-test.1.apk",
            "InkSlate-1.1.1-test.10.apk",
            "InkSlate-1.1.1-test.11.apk"
        )
        assertEquals(
            "InkSlate-1.1.1-test.11.apk",
            UpdateCheck.pickAsset(r, UpdateCheck.Platform.ANDROID)?.name
        )
    }

    /** A release whose files are not named after it still hands over something sensible. */
    @Test
    fun `an unnamed file still downloads`() {
        val r = release("1.2.0", "app-release.apk")
        assertEquals(
            "app-release.apk",
            UpdateCheck.pickAsset(r, UpdateCheck.Platform.ANDROID)?.name
        )
    }

    /** Windows accepts either, and an installer is a better thing to hand someone than an exe. */
    @Test
    fun `the installer is preferred over a bare executable`() {
        val r = release("1.2.0", "InkSlate-1.2.0.exe", "InkSlate-1.2.0.msi")
        assertEquals(
            "InkSlate-1.2.0.msi",
            UpdateCheck.pickAsset(r, UpdateCheck.Platform.WINDOWS)?.name
        )
    }

    @Test
    fun `a release with nothing for this platform offers no download`() {
        val r = release("1.2.0", "InkSlate-1.2.0.msi")
        assertNull(UpdateCheck.pickAsset(r, UpdateCheck.Platform.ANDROID))
    }
}
