package com.familytube.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Looks at the latest GitHub release and says whether it's newer than this app.
 * New versions are published by uploading FamilyTube.apk to a release tagged "v1.4" etc.
 */
final class UpdateChecker {

    static final String RELEASES_PAGE = "https://github.com/mrzoro14/FamilyTube/releases/latest";
    private static final String LATEST_API =
            "https://api.github.com/repos/mrzoro14/FamilyTube/releases/latest";
    private static final long CHECK_EVERY_MS = 24L * 60 * 60 * 1000;

    static final class Update {
        final String version;
        final String downloadUrl;
        final String notes;

        Update(String version, String downloadUrl, String notes) {
            this.version = version;
            this.downloadUrl = downloadUrl;
            this.notes = notes;
        }
    }

    private UpdateChecker() {
    }

    /**
     * Returns the newer release, or null if up to date, offline, or checked recently.
     * Network call: run off the main thread. Pass force=true to ignore the daily limit.
     */
    static Update check(Context context, boolean force) {
        SharedPreferences prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE);
        long last = prefs.getLong("lastUpdateCheck", 0);
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY_MS) return null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(LATEST_API).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            if (conn.getResponseCode() != 200) return null;
            JSONObject release = new JSONObject(readAll(conn.getInputStream()));
            prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply();

            String latest = release.optString("tag_name", "").replaceFirst("^[vV]", "");
            if (!isNewer(latest, currentVersion(context))) return null;

            String url = RELEASES_PAGE;
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").toLowerCase().endsWith(".apk")) {
                        url = a.optString("browser_download_url", url);
                        break;
                    }
                }
            }
            String notes = release.optString("body", "").trim();
            if (notes.length() > 400) notes = notes.substring(0, 400) + "...";
            return new Update(latest, url, notes);
        } catch (Exception e) {
            return null; // Offline or GitHub unavailable: try again another time.
        }
    }

    static String currentVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Compares dotted versions like 1.10 vs 1.9. */
    static boolean isNewer(String candidate, String current) {
        if (candidate == null || candidate.isEmpty()) return false;
        String[] a = candidate.split("\\.");
        String[] b = (current == null ? "0" : current).split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? parse(a[i]) : 0;
            int y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
