package com.familytube.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Every download the app knows about, newest first: what's waiting, running, finished or
 * failed. The service updates it and the screens show it. Saved so history survives
 * closing the app. Listeners are called on the main thread.
 */
final class DownloadStore {

    static final int QUEUED = 0;
    static final int RUNNING = 1;
    static final int DONE = 2;
    static final int FAILED = 3;

    private static final int MAX_FINISHED = 100;
    private static final long PROGRESS_EVERY_MS = 250;
    private static DownloadStore instance;

    static final class Item {
        final String id;
        volatile String label;
        final String link;
        final boolean audio;
        final int level;
        final int clipStart; // -1 when it isn't a clip
        final int clipEnd;
        volatile int state = QUEUED;
        volatile int pct = 0; // < 0 means no percentage yet
        volatile String status = "Waiting...";
        volatile String error;
        volatile String fileName;
        volatile String uri;
        volatile String mime;
        volatile long time = System.currentTimeMillis();
        volatile boolean cancelled = false;

        Item(String id, String label, String link, boolean audio, int level,
             int clipStart, int clipEnd) {
            this.id = id;
            this.label = label;
            this.link = link;
            this.audio = audio;
            this.level = level;
            this.clipStart = clipStart;
            this.clipEnd = clipEnd;
        }

        boolean isActive() {
            return state == QUEUED || state == RUNNING;
        }

        Downloader.Clip clip() {
            return clipStart >= 0 ? new Downloader.Clip(clipStart, clipEnd) : null;
        }

        Uri fileUri() {
            return uri == null ? null : Uri.parse(uri);
        }
    }

    interface Listener {
        void onDownloadsChanged();

        /** One download just finished or failed. */
        void onDownloadFinished(Item item);
    }

    private final File file;
    private final List<Item> items = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastProgress;

    static synchronized DownloadStore get(Context context) {
        if (instance == null) instance = new DownloadStore(context.getApplicationContext());
        return instance;
    }

    private DownloadStore(Context app) {
        file = new File(app.getFilesDir(), "downloads.json");
        load();
    }

    /** Must be called on the main thread. */
    void addListener(Listener l) {
        listeners.add(l);
    }

    /** Must be called on the main thread. */
    void removeListener(Listener l) {
        listeners.remove(l);
    }

    synchronized List<Item> all() {
        return new ArrayList<>(items);
    }

    synchronized Item find(String id) {
        for (Item i : items) {
            if (i.id.equals(id)) return i;
        }
        return null;
    }

    synchronized int activeCount() {
        int n = 0;
        for (Item i : items) {
            if (i.isActive()) n++;
        }
        return n;
    }

    /** The download to show on the Home card: the running one, else the next waiting. */
    synchronized Item currentActive() {
        Item waiting = null;
        for (Item i : items) {
            if (i.state == RUNNING) return i;
            if (i.state == QUEUED) waiting = i;
        }
        return waiting;
    }

    Item add(String label, String link, boolean audio, int level, Downloader.Clip clip) {
        Item item = new Item(UUID.randomUUID().toString(), label, link, audio, level,
                clip == null ? -1 : clip.start, clip == null ? -1 : clip.end);
        synchronized (this) {
            items.add(0, item);
        }
        saveAndNotify();
        return item;
    }

    /** Swap in a friendlier name once the video's title is known. */
    void rename(Item item, String label) {
        item.label = label;
        saveAndNotify();
    }

    void markRunning(Item item) {
        item.state = RUNNING;
        item.status = "Starting...";
        item.pct = -1;
        notifyChanged();
    }

    /** Called often while downloading, so saving and screen updates are limited. */
    void progress(Item item, String status, int pct) {
        item.status = status;
        item.pct = pct;
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
            if (now - lastProgress < PROGRESS_EVERY_MS) return;
            lastProgress = now;
        }
        notifyChanged();
    }

    void markDone(Item item, Downloader.Result result) {
        item.state = DONE;
        item.pct = 100;
        item.fileName = result.name;
        item.uri = result.uri == null ? null : result.uri.toString();
        item.mime = result.mime;
        item.time = System.currentTimeMillis();
        finish(item);
    }

    void markFailed(Item item, String error) {
        item.state = FAILED;
        item.error = error;
        item.time = System.currentTimeMillis();
        finish(item);
    }

    private void finish(Item item) {
        saveAndNotify();
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onDownloadFinished(item);
        });
    }

    /** Put a failed download back in the queue. */
    void requeue(Item item) {
        item.state = QUEUED;
        item.error = null;
        item.pct = 0;
        item.status = "Waiting...";
        item.cancelled = false;
        item.time = System.currentTimeMillis();
        saveAndNotify();
    }

    void remove(Item item) {
        synchronized (this) {
            items.remove(item);
        }
        saveAndNotify();
    }

    void clearFinished() {
        synchronized (this) {
            Iterator<Item> it = items.iterator();
            while (it.hasNext()) {
                if (!it.next().isActive()) it.remove();
            }
        }
        saveAndNotify();
    }

    private void saveAndNotify() {
        save();
        notifyChanged();
    }

    private void notifyChanged() {
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onDownloadsChanged();
        });
    }

    // ------------------------------------------------------------ saving

    private synchronized void save() {
        try {
            JSONArray array = new JSONArray();
            int finished = 0;
            for (Item i : items) {
                if (!i.isActive() && ++finished > MAX_FINISHED) continue;
                array.put(new JSONObject()
                        .put("id", i.id).put("label", i.label).put("link", i.link)
                        .put("audio", i.audio).put("level", i.level)
                        .put("clipStart", i.clipStart).put("clipEnd", i.clipEnd)
                        .put("state", i.state).put("error", i.error)
                        .put("fileName", i.fileName).put("uri", i.uri).put("mime", i.mime)
                        .put("time", i.time));
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // History is a convenience; the download itself is already saved.
        }
    }

    private void load() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) throw new IOException("Short read");
                read += n;
            }
            JSONArray array = new JSONArray(new String(data, StandardCharsets.UTF_8));
            for (int n = 0; n < array.length(); n++) {
                JSONObject o = array.getJSONObject(n);
                Item i = new Item(o.getString("id"), o.optString("label", "Download"),
                        o.optString("link"), o.optBoolean("audio"), o.optInt("level", 1),
                        o.optInt("clipStart", -1), o.optInt("clipEnd", -1));
                i.state = o.optInt("state", FAILED);
                i.error = text(o, "error");
                i.fileName = text(o, "fileName");
                i.uri = text(o, "uri");
                i.mime = text(o, "mime");
                i.time = o.optLong("time");
                if (i.isActive()) {
                    // The app was closed by the phone part-way through.
                    i.state = FAILED;
                    i.error = "Stopped before it finished. Tap Retry to try again.";
                }
                items.add(i);
            }
        } catch (Exception ignored) {
            // A damaged history file just means starting with an empty list.
        }
    }

    private static String text(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }
}
