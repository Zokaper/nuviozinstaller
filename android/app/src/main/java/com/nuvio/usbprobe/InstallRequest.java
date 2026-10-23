package com.nuvio.usbprobe;

import android.content.Context;
import java.util.Arrays;

/** Ephemeral input, passed directly to native code. Never serialized into a diagnostic or Bundle. */
final class InstallRequest {
    final String email;
    final char[] password;
    final String cacheDirectory;
    final PairingStore signingStore;
    /** Nuvio Z Debug instead of Nuvio Z. Read by native code (installer.rs). */
    final boolean debugBuild;
    /** "Add it again": no account, only Nuvio Z's connection file (native mode 9). */
    final boolean handoffOnly;
    InstallRequest(Context context, String email, char[] password) { this(context, email, password, true); }
    InstallRequest(Context context, String email, char[] password, boolean debugBuild) {
        this(context, email, password, debugBuild, false);
    }
    static InstallRequest forHandoff(Context context, boolean debugBuild) {
        return new InstallRequest(context, "", new char[0], debugBuild, true);
    }
    private InstallRequest(Context context, String email, char[] password, boolean debugBuild, boolean handoffOnly) {
        this.debugBuild = debugBuild;
        this.handoffOnly = handoffOnly;
        this.email = email.trim().toLowerCase(java.util.Locale.ROOT);
        this.password = password;
        this.cacheDirectory = new java.io.File(context.getCacheDir(), "install-proof").getAbsolutePath();
        this.signingStore = new PairingStore(context, "usb-signing-v1");
    }
    void erase() { Arrays.fill(password, '\0'); }
}
