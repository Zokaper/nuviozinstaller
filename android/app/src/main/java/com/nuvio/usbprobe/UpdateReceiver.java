package com.nuvio.usbprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** Android's answers to an installer self-update (InstallerUpdater): show its confirmation, or report the failure. */
public final class UpdateReceiver extends BroadcastReceiver {
    interface Listener { void failed(String message); }
    /** The bridge of the open installer window, which shows a failure on the welcome screen. */
    static volatile Listener listener;

    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return; // Android replaces the app; it is reopened by hand.
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        UsbProbeSession.get(context).note("update_failed status=" + status + " " + message);
        Listener current = listener;
        if (current != null) {
            current.failed(status == PackageInstaller.STATUS_FAILURE_ABORTED ? "The update was cancelled."
                    : status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                    ? "Android refused the update: it isn't signed like this copy. Download it from the release page instead."
                    : "Android couldn't install the update" + (message == null ? "." : ": " + message));
        }
    }
}
