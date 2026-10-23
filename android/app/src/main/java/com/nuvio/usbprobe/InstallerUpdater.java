package com.nuvio.usbprobe;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The installer updating itself, as desktop does (core/src/update.rs) and as Nuvio Z does (its AppUpdater): the
 * newest GitHub release of this repository, its APK, checked against SHA256SUMS.txt, handed to Android's package
 * installer, which asks the person to confirm. The release key must match, or Android refuses the update.
 */
final class InstallerUpdater {
    interface Progress { void fraction(float done); }

    private static final String LATEST = "https://api.github.com/repos/Zokaper/nuviozinstaller/releases/latest";
    private static final String ALLOWED = "https://github.com/Zokaper/nuviozinstaller/releases/download/";
    static final String ASSET = "Nuvio-Z-Installer-Android.apk";
    private static final String SUMS = "SHA256SUMS.txt";
    static final String SIGNING_CHANGED = "<!-- nuviozinstaller:signing-changed -->";
    private static final Pattern VERSION = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta|rc)\\.(\\d+))?$");

    private InstallerUpdater() {}

    /** 3.1.0-alpha.2 as comparable parts; pre-releases sort before their release. Null when unreadable. */
    static int[] parts(String version) {
        Matcher m = VERSION.matcher(version == null ? "" : version.trim());
        if (!m.matches()) return null;
        String stage = m.group(4);
        int rank = stage == null ? 3 : stage.equals("alpha") ? 0 : stage.equals("beta") ? 1 : 2;
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                rank, m.group(5) == null ? 0 : Integer.parseInt(m.group(5))};
    }

    static boolean isNewer(String remote, String local) {
        int[] r = parts(remote), l = parts(local);
        if (r == null || l == null) return false;
        for (int i = 0; i < r.length; i++) if (r[i] != l[i]) return r[i] > l[i];
        return false;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "nuviozinstaller-android");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        if (connection.getResponseCode() != 200) throw new IllegalStateException("HTTP " + connection.getResponseCode());
        return connection;
    }

    private static String text(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } finally { connection.disconnect(); }
    }

    /** The UI's update offer ({ current, version, important, notes }), or null when this is the newest. */
    static JSONObject check(String current) throws Exception {
        JSONObject release = new JSONObject(text(LATEST));
        String tag = release.optString("tag_name");
        if (release.optBoolean("draft") || !isNewer(tag, current)) return null;
        String apk = null, sums = null;
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String url = asset.optString("browser_download_url");
            if (!url.startsWith(ALLOWED) || url.contains("..")) continue;
            if (ASSET.equals(asset.optString("name"))) apk = url;
            if (SUMS.equals(asset.optString("name"))) sums = url;
        }
        if (apk == null || sums == null) return null;
        String body = release.optString("body", "");
        return new JSONObject()
                .put("current", current)
                .put("version", tag.replaceFirst("^v", ""))
                .put("important", body.contains(SIGNING_CHANGED))
                .put("notes", body.replaceAll("<!--[\\s\\S]*?-->", "").trim())
                .put("url", apk)
                .put("sumsUrl", sums);
    }

    /** The SHA-256 SHA256SUMS.txt lists for the APK. */
    static String sumFor(String sums, String name) {
        for (String line : sums.split("\n")) {
            String[] fields = line.trim().split("\\s+", 2);
            if (fields.length == 2 && fields[1].replaceFirst("^\\*", "").trim().equals(name)
                    && fields[0].matches("[0-9a-fA-F]{64}")) return fields[0].toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** Downloads and checks the APK, then hands it to Android, which asks the person to confirm (UpdateReceiver). */
    static void install(Context context, JSONObject update, Progress progress) throws Exception {
        String expected = sumFor(text(update.getString("sumsUrl")), ASSET);
        if (expected == null) throw new IllegalStateException("The release doesn't list a checksum for the APK");
        File apk = new File(context.getCacheDir(), "installer-update.apk");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HttpURLConnection connection = open(update.getString("url"));
        long total = connection.getContentLengthLong();
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            byte[] buffer = new byte[65536];
            long done = 0;
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                digest.update(buffer, 0, n);
                done += n;
                if (total > 0) progress.fraction(Math.min(0.99f, done / (float) total));
            }
        } finally { connection.disconnect(); }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!hex.toString().equals(expected)) {
            apk.delete();
            throw new IllegalStateException("The download doesn't match the release's checksum");
        }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("installer.apk", 0, apk.length())) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                session.fsync(out);
            }
            Intent result = new Intent(context, UpdateReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(context, id, result, flags).getIntentSender());
        }
        apk.delete();
        progress.fraction(1f);
    }
}
