package com.familytube.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

public class MainActivity extends AppCompatActivity implements DownloadService.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText url;
    private RadioGroup type;
    private RadioGroup quality;
    private RadioButton[] qualityButtons;
    private SharedPreferences prefs;
    private MaterialButton download;
    private MaterialButton play;
    private LinearProgressIndicator progress;
    private TextView status;
    private Uri lastFile;
    private String lastMime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Android 15+ draws apps edge to edge; keep content clear of the
        // status bar, camera cutout, navigation bar and keyboard.
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        url = findViewById(R.id.url);
        type = findViewById(R.id.type);
        quality = findViewById(R.id.quality);
        qualityButtons = new RadioButton[]{
                findViewById(R.id.q1), findViewById(R.id.q2),
                findViewById(R.id.q3), findViewById(R.id.q4)};
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        download = findViewById(R.id.download);
        play = findViewById(R.id.play);
        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);

        findViewById(R.id.paste).setOnClickListener(v -> pasteLink());
        findViewById(R.id.open).setOnClickListener(v -> openDownloads());
        findViewById(R.id.status_saver).setOnClickListener(v ->
                startActivity(new Intent(this, StatusActivity.class)));
        download.setOnClickListener(v -> startDownload());
        play.setOnClickListener(v -> playLast());

        type.setOnCheckedChangeListener((g, id) -> showQualityChoices());
        quality.setOnCheckedChangeListener((g, id) -> {
            int index = qualityIndex();
            if (index >= 0) prefs.edit().putInt(qualityKey(), index).apply();
        });
        showQualityChoices();

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }

        checkForUpdate();

        if (Downloader.isReady()) {
            setStatus("Ready. Paste a link and tap Download.", 0);
            download.setEnabled(true);
        } else {
            // First launch unpacks the downloader; later launches are quick.
            setStatus("Getting ready... (the first time takes a minute)", -1);
            new Thread(() -> {
                try {
                    Downloader.init(this, this::setStatus);
                    setStatus("Ready. Paste a link and tap Download.", 0);
                    main.post(() -> download.setEnabled(true));
                } catch (Exception e) {
                    setStatus("Setup failed. Close the app and open it again.", 0);
                    showError("Couldn't get ready:\n\n" + e.getMessage());
                }
            }).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        DownloadService.setListener(this);
    }

    @Override
    protected void onPause() {
        DownloadService.setListener(null);
        super.onPause();
    }

    /** Ask GitHub for a newer FamilyTube and offer to download it. */
    private void checkForUpdate() {
        new Thread(() -> {
            UpdateChecker.Update update = UpdateChecker.check(this, true);
            if (update == null) return;
            main.post(() -> {
                if (isFinishing()) return;
                String notes = update.notes.isEmpty() ? "" : update.notes + "\n\n";
                new AlertDialog.Builder(this)
                        .setTitle("New version " + update.version + " available")
                        .setMessage(notes + "Tap Download, then open the downloaded file "
                                + "and tap Update. Your settings are kept.")
                        .setPositiveButton("Download", (d, w) -> openLink(update.downloadUrl))
                        .setNegativeButton("Later", null)
                        .show();
            });
        }).start();
    }

    private void openLink(String link) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
        } catch (Exception e) {
            showError("Open this in your browser:\n" + link);
        }
    }

    // ------------------------------------------------------------ service updates

    @Override
    public void onStatus(String text, int pct) {
        setStatus(text, pct);
    }

    @Override
    public void onFinished(Downloader.Result result, String error) {
        if (error != null) {
            setStatus("Download failed.", 0);
            showError(error);
            return;
        }
        setStatus("Done! Saved in Download > " + Downloader.FOLDER + ":\n" + result.name, 100);
        lastFile = result.uri;
        lastMime = result.mime;
        play.setVisibility(lastFile != null ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------ download

    private void startDownload() {
        String link = Downloader.findLink(url.getText().toString().trim());
        if (link == null) {
            showError("That doesn't look like a YouTube or Instagram link.\n\n"
                    + "In the app, tap Share, then Copy link, then tap Paste here.");
            return;
        }
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            return;
        }
        boolean audioOnly = isAudio();
        int level = Math.max(qualityIndex(), 0);
        String what = audioOnly ? "Music " + Downloader.AUDIO_SHORT[level]
                : "Video " + Downloader.VIDEO_SHORT[level];
        DownloadService.start(this, link, audioOnly, level, what);
        url.setText("");
        play.setVisibility(View.GONE);
        setStatus(DownloadService.isRunning()
                ? "Added. It will start after the current download."
                : "Starting download... You can leave the app; see the notification bar.", -1);
    }

    private boolean isAudio() {
        return type.getCheckedRadioButtonId() == R.id.audio;
    }

    private String qualityKey() {
        return isAudio() ? "audioQuality" : "videoQuality";
    }

    private int qualityIndex() {
        for (int i = 0; i < qualityButtons.length; i++) {
            if (qualityButtons[i].isChecked()) return i;
        }
        return -1;
    }

    /** Swap the quality list between video and music, restoring the last pick. */
    private void showQualityChoices() {
        String[] labels = isAudio() ? Downloader.AUDIO_LABELS : Downloader.VIDEO_LABELS;
        int saved = prefs.getInt(qualityKey(), 1); // 720p video / high-quality music
        if (saved >= labels.length) saved = labels.length - 1;
        for (int i = 0; i < qualityButtons.length; i++) {
            boolean used = i < labels.length;
            qualityButtons[i].setVisibility(used ? View.VISIBLE : View.GONE);
            if (used) qualityButtons[i].setText(labels[i]);
        }
        quality.check(qualityButtons[saved].getId());
    }

    // ------------------------------------------------------------ buttons

    private void pasteLink() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            String link = Downloader.findLink(text == null ? null : text.toString());
            url.setText(link != null ? link : text);
        }
    }

    private void openDownloads() {
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (Exception e) {
            showError("Open your Files app, then go to Download > " + Downloader.FOLDER + ".");
        }
    }

    private void playLast() {
        if (lastFile == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(lastFile, lastMime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            openDownloads();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != 1) return; // Notification permission is optional.
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startDownload();
        } else {
            showError("FamilyTube needs permission to save files to your phone.");
        }
    }

    // ------------------------------------------------------------ ui helpers

    /** pct < 0 shows a moving "working" bar. Safe to call from any thread. */
    private void setStatus(String text, int pct) {
        main.post(() -> {
            status.setText(text);
            if (pct < 0) {
                progress.setIndeterminate(true);
            } else {
                progress.setIndeterminate(false);
                progress.setProgressCompat(pct, true);
            }
        });
    }

    private void showError(String message) {
        main.post(() -> {
            if (isFinishing()) return;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }
}
