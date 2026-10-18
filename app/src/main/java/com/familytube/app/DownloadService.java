package com.familytube.app;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs downloads one after another in the background, with progress in the
 * notification bar, so nobody has to keep the app open.
 */
public class DownloadService extends Service {

    private static final String EXTRA_LINK = "link";
    private static final String EXTRA_AUDIO = "audio";
    private static final String EXTRA_LEVEL = "level";
    private static final String EXTRA_LABEL = "label";

    private static final String CHANNEL_PROGRESS = "progress";
    private static final String CHANNEL_DONE = "done";
    private static final int ONGOING_ID = 1;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(100);

    /** Lets the main screen mirror progress while it's visible. */
    interface Listener {
        void onStatus(String status, int pct);

        void onFinished(Downloader.Result result, String error);
    }

    private static volatile Listener listener;
    private static volatile boolean running = false;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger queued = new AtomicInteger();
    private NotificationManager notifications;
    private long lastNotify;

    static void start(Context context, String link, boolean audio, int level, String label) {
        Intent i = new Intent(context, DownloadService.class)
                .putExtra(EXTRA_LINK, link)
                .putExtra(EXTRA_AUDIO, audio)
                .putExtra(EXTRA_LEVEL, level)
                .putExtra(EXTRA_LABEL, label);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    static void setListener(Listener l) {
        listener = l;
    }

    static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notifications = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(new NotificationChannel(
                    CHANNEL_PROGRESS, "Downloading", NotificationManager.IMPORTANCE_LOW));
            notifications.createNotificationChannel(new NotificationChannel(
                    CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int type = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;
        ServiceCompat.startForeground(this, ONGOING_ID,
                progressNotification("Waiting to start...", "", -1), type);

        if (intent == null || intent.getStringExtra(EXTRA_LINK) == null) {
            if (queued.get() == 0) stopSelf();
            return START_NOT_STICKY;
        }
        String link = intent.getStringExtra(EXTRA_LINK);
        boolean audio = intent.getBooleanExtra(EXTRA_AUDIO, false);
        int level = intent.getIntExtra(EXTRA_LEVEL, 1);
        String label = intent.getStringExtra(EXTRA_LABEL);

        queued.incrementAndGet();
        running = true;
        worker.execute(() -> runOne(link, audio, level, label));
        return START_NOT_STICKY;
    }

    private void runOne(String link, boolean audio, int level, String label) {
        String title = label == null ? "FamilyTube" : label;
        Downloader.Progress progress = (status, pct) -> {
            Listener l = listener;
            if (l != null) main.post(() -> l.onStatus(status, pct));
            // Notifications are rate-limited by Android; update at most twice a second.
            long now = SystemClock.elapsedRealtime();
            if (pct >= 100 || now - lastNotify > 500) {
                lastNotify = now;
                notifications.notify(ONGOING_ID, progressNotification(title, status, pct));
            }
        };
        Downloader.Result result = null;
        String error = null;
        try {
            result = Downloader.download(this, link, audio, level, progress);
            notifications.notify(NEXT_ID.getAndIncrement(), doneNotification(result));
        } catch (Exception e) {
            error = Downloader.friendlyError(e.getMessage());
            notifications.notify(NEXT_ID.getAndIncrement(), failedNotification(error));
        }

        Listener l = listener;
        Downloader.Result r = result;
        String err = error;
        if (l != null) main.post(() -> l.onFinished(r, err));

        if (queued.decrementAndGet() == 0) {
            running = false;
            // Family mostly uses the Share popup, so mention new versions here too
            // (UpdateChecker limits this to once a day).
            UpdateChecker.Update update = UpdateChecker.check(this, false);
            if (update != null) {
                notifications.notify(NEXT_ID.getAndIncrement(), updateNotification(update));
            }
            main.post(() -> {
                if (queued.get() == 0) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            });
        }
    }

    // ------------------------------------------------------------ notifications

    private Notification progressNotification(String title, String text, int pct) {
        int waiting = queued.get() - 1;
        String more = waiting > 0 ? " (" + waiting + " more waiting)" : "";
        return new NotificationCompat.Builder(this, CHANNEL_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text + more)
                .setProgress(100, Math.max(pct, 0), pct < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .build();
    }

    private Notification doneNotification(Downloader.Result result) {
        Intent open;
        if (result.uri != null) {
            open = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(result.uri, result.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            open = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        PendingIntent tap = PendingIntent.getActivity(this, NEXT_ID.get(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Done! Tap to play")
                .setContentText(result.name)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
    }

    private Notification failedNotification(String error) {
        return new NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download failed")
                .setContentText(error)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(error))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build();
    }

    private Notification updateNotification(UpdateChecker.Update update) {
        // Opening the app shows the "Update now" popup, which updates in-app.
        return new NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("FamilyTube " + update.version + " is available")
                .setContentText("Tap to update FamilyTube.")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build();
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
