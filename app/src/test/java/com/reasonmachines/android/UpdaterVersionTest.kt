package com.reasonmachines.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {
    @Test fun newerPatch() = assertTrue(Updater.isNewer("v1.0.12", "1.0.9"))
    @Test fun sameVersion() = assertFalse(Updater.isNewer("v1.0.9", "1.0.9"))
    @Test fun olderVersion() = assertFalse(Updater.isNewer("v1.0.8", "1.0.9"))
    @Test fun newerMinor() = assertTrue(Updater.isNewer("1.1.0", "1.0.99"))
    @Test fun extraSegment() = assertTrue(Updater.isNewer("1.0.9.1", "1.0.9"))
}
