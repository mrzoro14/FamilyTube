package com.familytube.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;

/** Everything about getting a YouTube link onto the phone, shared by all screens. */
final class Downloader {

    static final String FOLDER = "FamilyTube";

    // Quality choices: the label shown, and what it means for yt-dlp.
    static final String[] VIDEO_LABELS = {
            "Small (360p) - saves space",
            "Good (720p) - recommended",
            "HD (1080p)",
            "Best available - big files",
    };
    static final String[] VIDEO_SHORT = {"360p", "720p", "1080p", "Best"};
    private static final int[] VIDEO_HEIGHT = {360, 720, 1080, 0}; // 0 = no limit
    static final String[] AUDIO_LABELS = {
            "Normal (128 kbps) - smaller files",
            "High - best quality",
    };
    static final String[] AUDIO_SHORT = {"Normal", "High"};
    private static final String[] AUDIO_QUALITY = {"128K", "0"};

    private static final long UPDATE_EVERY_MS = 24L * 60 * 60 * 1000;
    private static final Pattern SUPPORTED_LINK = Pattern.compile(
            "https?://(?:[\\w-]+\\.)?(?:youtube\\.com|youtu\\.be|instagram\\.com|instagr\\.am)/\\S+",
            Pattern.CASE_INSENSITIVE);

    private static volatile boolean ready = false;

    interface Progress {
        /** pct < 0 means "working, no percentage yet". */
        void update(String status, int pct);
    }

    static final class Result {
        final String name;
        final Uri uri;   // null on Android 8/9, where files are saved by path
        final String mime;

        Result(String name, Uri uri, String mime) {
            this.name = name;
            this.uri = uri;
            this.mime = mime;
        }
    }

    private Downloader() {
    }

    static boolean isReady() {
        return ready;
    }

    static String findLink(String text) {
        if (text == null) return null;
        Matcher m = SUPPORTED_LINK.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** Unpack yt-dlp/ffmpeg and keep yt-dlp current (at most once a day). Safe to call often. */
    static synchronized void init(Context context, Progress progress) throws Exception {
        if (ready) return;
        Context app = context.getApplicationContext();
        YoutubeDL.getInstance().init(app);
        FFmpeg.getInstance().init(app);

        SharedPreferences prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE);
        long last = prefs.getLong("lastUpdate", 0);
        if (System.currentTimeMillis() - last > UPDATE_EVERY_MS) {
            progress.update("Checking for updates...", -1);
            try {
                YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE);
                prefs.edit().putLong("lastUpdate", System.currentTimeMillis()).apply();
            } catch (Exception ignored) {
                // No internet right now is fine; we'll try again next time.
            }
        }
        ready = true;
    }

    /** Video title for the share popup, or null if it can't be fetched. */
    static String fetchTitle(Context context, String link) {
        try {
            init(context, (s, p) -> { });
            return YoutubeDL.getInstance().getInfo(link).getTitle();
        } catch (Exception e) {
            return null;
        }
    }

    /** Download one link and save it to Download/FamilyTube. Runs on the caller's thread. */
    static Result download(Context context, String link, boolean audioOnly, int level,
                           Progress progress) throws Exception {
        init(context, progress);
        Context app = context.getApplicationContext();
        File work = new File(app.getCacheDir(), "download");
        deleteContents(work);
        work.mkdirs();
        try {
            YoutubeDLRequest req = new YoutubeDLRequest(link);
            req.addOption("--no-playlist");
            req.addOption("--no-mtime");
            req.addOption("--windows-filenames");
            req.addOption("--cache-dir", new File(app.getCacheDir(), "yt-dlp").getAbsolutePath());
            req.addOption("--embed-metadata");
            req.addOption("-o", work.getAbsolutePath() + "/%(title).120B.%(ext)s");
            if (audioOnly) {
                req.addOption("-f", "bestaudio/best");
                req.addOption("-x");
                req.addOption("--audio-format", "mp3");
                req.addOption("--audio-quality", AUDIO_QUALITY[level]);
                req.addOption("--embed-thumbnail");
            } else {
                // Closest size at or below the chosen height; prefer H.264 + AAC so
                // it plays everywhere. "Best" allows VP9/AV1 above 1080p.
                int height = VIDEO_HEIGHT[level];
                req.addOption("-f", "bv*+ba/b");
                req.addOption("-S", height > 0
                        ? "res:" + height + ",vcodec:h264,acodec:m4a"
                        : "res,acodec:m4a");
                req.addOption("--merge-output-format", "mp4");
            }

            progress.update("Starting download...", 0);
            YoutubeDL.getInstance().execute(req, null, (pct, eta, line) -> {
                if (line != null && (line.startsWith("[Merger]")
                        || line.startsWith("[ExtractAudio]")
                        || line.startsWith("[EmbedThumbnail]"))) {
                    progress.update("Almost done, finishing the file...", 100);
                } else if (pct != null && pct >= 0) {
                    progress.update(String.format(Locale.US, "Downloading... %.0f%%", pct),
                            Math.round(pct));
                }
                return Unit.INSTANCE;
            });

            File result = findResult(work);
            if (result == null) throw new IOException("The download finished but no file was found.");
            progress.update("Saving to your phone...", 100);
            return saveToDownloads(app, result);
        } finally {
            deleteContents(work);
        }
    }

    /** The finished file is the one that isn't a leftover part or thumbnail. */
    private static File findResult(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.US);
            if (n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".jpg")
                    || n.endsWith(".webp") || n.endsWith(".png") || n.contains(".temp.")) continue;
            if (best == null || f.length() > best.length()) best = f;
        }
        return best;
    }

    /** Copy the file into the phone's public Download/FamilyTube folder. */
    private static Result saveToDownloads(Context context, File file) throws IOException {
        String name = file.getName();
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.US);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime == null) mime = "application/octet-stream";

        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Couldn't save to Downloads.");
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = cr.openOutputStream(uri)) {
                copy(in, out);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, values, null, null);
            return new Result(name, uri, mime);
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), FOLDER);
        dir.mkdirs();
        File dest = new File(dir, name);
        try (InputStream in = new FileInputStream(file);
             OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        MediaScannerConnection.scanFile(context, new String[]{dest.getAbsolutePath()},
                new String[]{mime}, null);
        return new Result(name, null, mime);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        if (out == null) throw new IOException("Couldn't save to Downloads.");
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void deleteContents(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) deleteContents(f);
            f.delete();
        }
    }

    static String friendlyError(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.US);
        if (m.contains("[instagram]") && (m.contains("login") || m.contains("rate-limit")
                || m.contains("rate limit") || m.contains("empty media")
                || m.contains("cookies") || m.contains("not available"))) {
            return "Instagram didn't allow this one. Private accounts, Stories and some "
                    + "posts need an Instagram login, which FamilyTube doesn't use. "
                    + "Public Reels usually work. If it was public, wait a few minutes "
                    + "and try again.";
        }
        if (m.contains("[instagram]") && m.contains("no video formats")) {
            return "This Instagram post is a photo. FamilyTube can only save videos and Reels.";
        }
        if (m.contains("private video") || m.contains("sign in")) {
            return "This video is private or needs a sign-in, so it can't be downloaded.";
        }
        if (m.contains("unavailable")) {
            return "This video is unavailable (removed, blocked, or a wrong link).";
        }
        if (m.contains("unable to download") || m.contains("timed out")
                || m.contains("network") || m.contains("resolve")) {
            return "Couldn't reach the website. Check the internet and try again.";
        }
        String detail = message == null ? "" : message.trim();
        int at = detail.lastIndexOf("ERROR:");
        if (at >= 0) detail = detail.substring(at);
        if (detail.length() > 300) detail = detail.substring(0, 300) + "...";
        return "Something went wrong. Please try again.\n\n" + detail;
    }
}
