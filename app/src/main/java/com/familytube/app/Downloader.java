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
import com.yausername.youtubedl_android.mapper.VideoInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
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

    /** A part of a video, in whole seconds. */
    static final class Clip {
        final int start;
        final int end;

        Clip(int start, int end) {
            this.start = start;
            this.end = end;
        }

        String label() {
            return formatTime(start) + " to " + formatTime(end);
        }
    }

    private Downloader() {
    }

    /** "90", "1:30" or "1:02:03" to seconds, or -1 if it isn't a time. */
    static int parseTime(String text) {
        if (text == null) return -1;
        String t = text.trim();
        if (!t.matches("\\d+(:\\d{1,2}){0,2}")) return -1;
        try {
            int total = 0;
            for (String part : t.split(":")) total = Math.multiplyExact(total, 60)
                    + Integer.parseInt(part);
            return total;
        } catch (ArithmeticException | NumberFormatException e) {
            return -1;
        }
    }

    static String formatTime(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%d:%02d", m, s);
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

    /** Title and length for the clip controls and popup, or null if they can't be fetched. */
    static VideoInfo fetchInfo(Context context, String link) {
        try {
            init(context, (s, p) -> { });
            YoutubeDLRequest req = new YoutubeDLRequest(link);
            req.addOption("--ignore-no-formats-error"); // Instagram photos have no video
            req.addOption("--flat-playlist");           // one line for multi-item posts
            req.addOption("--no-warnings");
            return YoutubeDL.getInstance().getInfo(req);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isInstagram(String link) {
        return link != null && link.toLowerCase(Locale.US).matches("https?://([\\w-]+\\.)?(instagram\\.com|instagr\\.am)/.*");
    }

    static boolean isInstagramStory(String link) {
        return isInstagram(link) && link.toLowerCase(Locale.US).contains("/stories/");
    }

    /** Download one link and save it to Download/FamilyTube. Runs on the caller's thread. */
    static Result download(Context context, String link, boolean audioOnly, int level, Clip clip,
                           Progress progress) throws Exception {
        init(context, progress);
        if (isInstagramStory(link)) {
            throw new IOException("FAMILYTUBE_STORY");
        }
        if (clip != null && isInstagram(link)) {
            throw new IOException("FAMILYTUBE_CLIP_YOUTUBE");
        }
        // Instagram posts can be photos, videos or a mix; save whatever is in them.
        if (isInstagram(link) && !audioOnly) {
            return downloadInstagramPost(context, link, level, progress);
        }
        return downloadWithYtDlp(context, link, audioOnly, level, progress, 0, clip);
    }

    /**
     * Let yt-dlp fetch the post details (it keeps up with Instagram's changes), then save
     * photos directly from their full-size address and videos through yt-dlp.
     */
    private static Result downloadInstagramPost(Context context, String link, int level,
                                                Progress progress) throws Exception {
        progress.update("Looking at the post...", -1);
        YoutubeDLRequest req = new YoutubeDLRequest(link);
        req.addOption("-J");                          // all details as one JSON
        req.addOption("--ignore-no-formats-error");   // a photo has no video formats
        req.addOption("--no-warnings");
        req.addOption("--cache-dir", new File(context.getCacheDir(), "yt-dlp").getAbsolutePath());
        String out = YoutubeDL.getInstance().execute(req).getOut();
        JSONObject info = new JSONObject(out.substring(out.indexOf('{')));

        boolean isPost = "playlist".equals(info.optString("_type"));
        JSONArray entries = isPost ? info.optJSONArray("entries") : null;
        List<JSONObject> items = new ArrayList<>();
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) items.add(entries.optJSONObject(i));
        } else {
            items.add(info);
        }

        String owner = info.optString("channel", "instagram");
        String postId = info.optString("id", "post");
        Result first = null;
        int saved = 0;
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.get(i);
            if (item == null) continue;
            String step = items.size() > 1 ? " (" + (i + 1) + " of " + items.size() + ")" : "";
            JSONArray formats = item.optJSONArray("formats");
            Result r;
            if (formats != null && formats.length() > 0) {
                Progress p = (s, pct) -> progress.update(s + step, pct);
                r = downloadWithYtDlp(context, link, false, level, p, isPost ? i + 1 : 0, null);
            } else {
                String url = bestImageUrl(item);
                if (url == null) continue;
                progress.update("Saving photo" + step + "...", (i * 100) / items.size());
                String name = owner + "_" + postId + (items.size() > 1 ? "_" + (i + 1) : "");
                r = saveImage(context, url, name);
            }
            if (first == null) first = r;
            saved++;
        }
        if (first == null) throw new IOException("FAMILYTUBE_NOTHING");
        if (saved == 1) return first;
        return new Result(saved + " photos/videos from @" + owner, first.uri, first.mime);
    }

    /** yt-dlp lists a photo's sizes as thumbnails; pick the biggest. */
    private static String bestImageUrl(JSONObject item) {
        JSONArray thumbs = item.optJSONArray("thumbnails");
        if (thumbs == null || thumbs.length() == 0) return null;
        String best = null;
        long bestArea = -1;
        for (int i = 0; i < thumbs.length(); i++) {
            JSONObject t = thumbs.optJSONObject(i);
            if (t == null || t.optString("url").isEmpty()) continue;
            long area = (long) t.optInt("width", 0) * t.optInt("height", 0);
            // Unknown sizes: the list goes small to large, so later wins.
            if (area >= bestArea) {
                bestArea = area;
                best = t.optString("url");
            }
        }
        return best;
    }

    private static Result saveImage(Context context, String url, String baseName)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("Referer", "https://www.instagram.com/");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        if (conn.getResponseCode() != 200) {
            throw new IOException("Instagram answered " + conn.getResponseCode());
        }
        String type = conn.getContentType() == null ? "" : conn.getContentType();
        String ext = type.contains("webp") ? "webp" : type.contains("heic") ? "heic" : "jpg";
        String name = baseName.replaceAll("[\\\\/:*?\"<>|]", "_") + "." + ext;
        try (InputStream in = conn.getInputStream()) {
            return saveStream(context, in, name, FOLDER);
        }
    }

    /**
     * One yt-dlp download. item > 0 picks that item from a multi-item post;
     * clip, if set, saves only that part of the video.
     */
    private static Result downloadWithYtDlp(Context context, String link, boolean audioOnly,
                                            int level, Progress progress, int item, Clip clip)
            throws Exception {
        Context app = context.getApplicationContext();
        File work = new File(app.getCacheDir(), "download");
        deleteContents(work);
        work.mkdirs();
        try {
            YoutubeDLRequest req = new YoutubeDLRequest(link);
            if (item > 0) {
                req.addOption("-I", String.valueOf(item));
            } else {
                req.addOption("--no-playlist");
            }
            req.addOption("--no-mtime");
            req.addOption("--windows-filenames");
            req.addOption("--cache-dir", new File(app.getCacheDir(), "yt-dlp").getAbsolutePath());
            req.addOption("--embed-metadata");
            String suffix = "";
            if (clip != null) {
                // Copies the part without re-encoding. --force-keyframes-at-cuts would be
                // frame-exact, but re-encoding took phones many minutes at full CPU.
                req.addOption("--download-sections", "*" + clip.start + "-" + clip.end);
                suffix = " (clip " + clip.label().replace(':', '.') + ")";
            }
            req.addOption("-o", work.getAbsolutePath() + "/%(title).100B" + suffix + ".%(ext)s");
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

            // Clips are cut by ffmpeg, which gives no percentage, so show a moving bar.
            if (clip != null) {
                progress.update("Getting your clip...", -1);
            } else {
                progress.update("Starting download...", 0);
            }
            YoutubeDL.getInstance().execute(req, null, (pct, eta, line) -> {
                if (line != null && (line.startsWith("[Merger]")
                        || line.startsWith("[ExtractAudio]")
                        || line.startsWith("[EmbedThumbnail]"))) {
                    progress.update("Almost done, finishing the file...", 100);
                } else if (clip == null && pct != null && pct >= 0) {
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
        try (InputStream in = new FileInputStream(file)) {
            return saveStream(context, in, file.getName(), FOLDER);
        }
    }

    static String mimeFor(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.US);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    /**
     * Save a stream as Download/&lt;subfolder&gt;/&lt;name&gt; so it shows in Files and Gallery.
     * subfolder may contain "/" (e.g. "FamilyTube/WhatsApp Status").
     */
    static Result saveStream(Context context, InputStream in, String name, String subfolder)
            throws IOException {
        String mime = mimeFor(name);

        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + subfolder);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Couldn't save to Downloads.");
            try (OutputStream out = cr.openOutputStream(uri)) {
                copy(in, out);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, values, null, null);
            return new Result(name, uri, mime);
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), subfolder);
        dir.mkdirs();
        File dest = new File(dir, name);
        try (OutputStream out = new FileOutputStream(dest)) {
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
        if (m.contains("familytube_story")) {
            return "Instagram Stories can't be saved. Instagram only shows them to people "
                    + "who are logged in, and FamilyTube doesn't log in to keep your account "
                    + "safe. Posts, photos and Reels work.";
        }
        if (m.contains("familytube_clip_youtube")) {
            return "Clips work with YouTube links. For Instagram, turn the clip switch off.";
        }
        if (m.contains("familytube_nothing")) {
            return "Couldn't find any photo or video in this Instagram post.";
        }
        if (m.contains("[instagram]") && (m.contains("login") || m.contains("rate-limit")
                || m.contains("rate limit") || m.contains("empty media")
                || m.contains("cookies") || m.contains("not available"))) {
            return "Instagram didn't allow this one. Private accounts, Stories and some "
                    + "posts need an Instagram login, which FamilyTube doesn't use. "
                    + "Public Reels usually work. If it was public, wait a few minutes "
                    + "and try again.";
        }
        if (m.contains("[instagram]") && (m.contains("no video") || m.contains("no video formats"))) {
            return "This Instagram post is a photo, so it can't be saved as music. "
                    + "Choose Video / Save post instead.";
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
