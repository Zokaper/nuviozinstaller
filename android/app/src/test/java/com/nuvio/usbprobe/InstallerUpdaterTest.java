package com.nuvio.usbprobe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The same cases as desktop's core/src/update.rs tests: one versioning for every platform. */
public final class InstallerUpdaterTest {
    @Test public void comparesVersionsLikeSemverWithPreReleasesFirst() {
        assertTrue(InstallerUpdater.isNewer("v3.1.0-alpha.2", "3.1.0-alpha.1"));
        assertTrue(InstallerUpdater.isNewer("3.1.0-beta.1", "3.1.0-alpha.9"));
        assertTrue(InstallerUpdater.isNewer("3.1.0", "3.1.0-rc.3"));
        assertTrue(InstallerUpdater.isNewer("3.2.0-alpha.0", "3.1.9"));
        assertFalse(InstallerUpdater.isNewer("3.1.0-alpha.1", "3.1.0-alpha.1"));
        assertFalse(InstallerUpdater.isNewer("3.0.9", "3.1.0-alpha.1"));
        assertFalse(InstallerUpdater.isNewer("nightly", "3.1.0"));
        assertFalse(InstallerUpdater.isNewer("3.2.0", "3.1.0-alpha.1-dev"));
    }

    @Test public void readsTheChecksumListTheReleaseWorkflowWrites() {
        String a = "a".repeat(64);
        String sums = a + "  Nuvio-Z-Installer-Windows-x64.zip\n" + a.toUpperCase() + " *Nuvio-Z-Installer-Android.apk\n";
        assertEquals(a, InstallerUpdater.sumFor(sums, "Nuvio-Z-Installer-Android.apk"));
        assertNull(InstallerUpdater.sumFor(sums, "Nuvio-Z-Installer-macOS.zip"));
    }
}
