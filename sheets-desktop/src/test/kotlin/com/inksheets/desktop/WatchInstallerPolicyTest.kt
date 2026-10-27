package com.inksheets.desktop

import com.inkslate.core.UpdateCheck
import org.junit.Assert.*
import org.junit.Test

class WatchInstallerPolicyTest {
    @Test fun onlyExpectedFlatBundleEntriesAreAccepted() {
        DesktopWatchInstaller.allowedEntries.forEach { assertTrue(DesktopWatchInstaller.safeEntry(it, 100)) }
        listOf("../Install-Watch.cmd", "C:/Install-Watch.cmd", "nested/InkSheets-Watch.apk", "other.exe").forEach {
            assertFalse(DesktopWatchInstaller.safeEntry(it, 100))
        }
        assertFalse(DesktopWatchInstaller.safeEntry("InkSheets-Watch.apk", -1))
        assertFalse(DesktopWatchInstaller.safeEntry("InkSheets-Watch.apk", 64_000_001))
    }
    @Test fun onlyWatchZipFromProjectReleaseCanBeChosen() {
        fun release(vararg assets: UpdateCheck.Asset) = UpdateCheck.Release(com.inkslate.core.Version.parse("1.2.3")!!, "test", "", "", assets.toList())
        val good = UpdateCheck.Asset("InkSheets-Watch-1.2.3.zip", "https://github.com/williebob555-lab/InkSlate/releases/download/inksheets-test/watch.zip", 100)
        assertEquals(good, DesktopWatchInstaller.asset(release(good)))
        assertNull(DesktopWatchInstaller.asset(release(good.copy(url = "https://example.com/watch.zip"))))
        assertNull(DesktopWatchInstaller.asset(release(good.copy(name = "InkSheets-1.2.3.apk"))))
    }
}
