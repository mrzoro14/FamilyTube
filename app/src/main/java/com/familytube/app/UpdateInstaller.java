package com.familytube.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads a new FamilyTube.apk inside the app and hands it to Android's installer. */
final class UpdateInstaller {

    private static final String APK_MIME = "application/vnd.android.package-archive";

    private UpdateInstaller() {
    }

    /** True when the release has an APK we can install directly (not just a web page). */
    static boolean canInstallInApp(UpdateChecker.Update update) {
        return update.downloadUrl.toLowerCase().endsWith(".apk");
    }

    /** Android 8+ needs a one-time "allow this app to install updates" switch. */
    static boolean hasInstallPermission(Context context) {
        return Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls();
    }

    /** Opens the system screen with that switch for FamilyTube. */
    static Intent installPermissionScreen(Context context) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
    }

    /** Download the APK into the app's cache. Runs on the caller's thread. */
    static File download(Context context, String url, Downloader.Progress progress)
            throws IOException {
        File dir = new File(context.getCacheDir(), "updates");
        dir.mkdirs();
        File apk = new File(dir, "FamilyTube-update.apk");
        File part = new File(dir, "FamilyTube-update.apk.part");

        // GitHub redirects release downloads to its file servers (https -> https).
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        if (conn.getResponseCode() != 200) {
            throw new IOException("GitHub answered " + conn.getResponseCode());
        }
        long total = conn.getContentLengthLong();
        long done = 0;
        int lastPct = -1;
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        progress.update(String.format("Downloading update... %d%%", pct), pct);
                    }
                }
            }
        }
        if (total > 0 && done != total) throw new IOException("The download was cut off.");
        if (apk.exists()) apk.delete();
        if (!part.renameTo(apk)) throw new IOException("Couldn't prepare the update.");
        return apk;
    }

    /** Android's "Do you want to update this app?" screen for the downloaded APK. */
    static Intent installScreen(Context context, File apk) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", apk);
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
