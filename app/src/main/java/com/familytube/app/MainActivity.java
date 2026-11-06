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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.util.List;

public class MainActivity extends AppCompatActivity
        implements DownloadStore.Listener, DownloadsAdapter.Actions {

    static final String EXTRA_SHOW_DOWNLOADS = "showDownloads";

    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText url;
    private MaterialButtonToggleGroup type;
    private ChipGroup quality;
    private TextView qualityHint;
    private SharedPreferences prefs;
    private MaterialButton download;
    private View root;
    private BottomNavigationView nav;
    private View homePage;
    private View downloadsPage;
    private View activeCard;
    private TextView activeTitle;
    private TextView activeStatus;
    private LinearProgressIndicator activeProgress;
    private View recentSection;
    private View downloadsEmpty;
    private View clearFinished;
    private DownloadStore store;
    private DownloadsAdapter recentAdapter;
    private DownloadsAdapter downloadsAdapter;
    private OnBackPressedCallback backToHome;
    private String setupMessage; // shown on the Home card until the downloader is ready
    private boolean showingChoices = false;
    private UpdateChecker.Update pendingUpdate; // waiting for the install-permission switch
    private ClipPanel clipPanel;
    private String clipLink; // the link clipPanel's length belongs to

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Android 15+ draws apps edge to edge; keep content clear of the
        // status bar, camera cutout, navigation bar and keyboard.
        root = findViewById(R.id.root);
        nav = findViewById(R.id.nav);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            // The bottom bar would otherwise ride up on top of the keyboard.
            nav.setVisibility(insets.isVisible(WindowInsetsCompat.Type.ime())
                    ? View.GONE : View.VISIBLE);
            return WindowInsetsCompat.CONSUMED;
        });

        url = findViewById(R.id.url);
        type = findViewById(R.id.type);
        quality = findViewById(R.id.quality);
        qualityHint = findViewById(R.id.quality_hint);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        download = findViewById(R.id.download);
        homePage = findViewById(R.id.home_page);
        downloadsPage = findViewById(R.id.downloads_page);
        activeCard = findViewById(R.id.active_card);
        activeTitle = findViewById(R.id.active_title);
        activeStatus = findViewById(R.id.active_status);
        activeProgress = findViewById(R.id.active_progress);
        recentSection = findViewById(R.id.recent_section);
        downloadsEmpty = findViewById(R.id.downloads_empty);
        clearFinished = findViewById(R.id.clear_finished);
        store = DownloadStore.get(this);

        findViewById(R.id.paste).setOnClickListener(v -> pasteLink());
        findViewById(R.id.open).setOnClickListener(v -> openDownloads());
        findViewById(R.id.status_saver).setOnClickListener(v ->
                startActivity(new Intent(this, StatusActivity.class)));
        download.setOnClickListener(v -> startDownload());
        clearFinished.setOnClickListener(v -> store.clearFinished());
        findViewById(R.id.see_all).setOnClickListener(v -> showDownloadsTab());
        activeCard.setOnClickListener(v -> {
            if (setupMessage == null) showDownloadsTab();
        });

        recentAdapter = new DownloadsAdapter(this, 3);
        downloadsAdapter = new DownloadsAdapter(this, 0);
        setUpList(findViewById(R.id.recent_list), recentAdapter);
        setUpList(findViewById(R.id.downloads_list), downloadsAdapter);

        // Back from Downloads returns to Home instead of leaving the app.
        backToHome = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                nav.setSelectedItemId(R.id.nav_home);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backToHome);
        nav.setOnItemSelectedListener(item -> {
            boolean downloads = item.getItemId() == R.id.nav_downloads;
            homePage.setVisibility(downloads ? View.GONE : View.VISIBLE);
            downloadsPage.setVisibility(downloads ? View.VISIBLE : View.GONE);
            backToHome.setEnabled(downloads);
            return true;
        });

        type.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) showQualityChoices();
        });
        quality.setOnCheckedStateChangeListener((g, ids) -> {
            int index = qualityIndex();
            if (showingChoices || index < 0) return;
            prefs.edit().putInt(qualityKey(), index).apply();
            showQualityHint(index);
        });
        showQualityChoices();

        clipPanel = new ClipPanel(root);
        clipPanel.setOnSwitchedOn(this::lookUpClipLength);
        url.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                lookUpClipLength();
            }
        });

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }

        if (!showWhatsNewIfUpdated()) checkForUpdate();

        if (Downloader.isReady()) {
            download.setEnabled(true);
        } else {
            // First launch unpacks the downloader; later launches are quick.
            setSetupMessage("Getting ready... (the first time takes a minute)");
            new Thread(() -> {
                try {
                    Downloader.init(this, (s, p) -> setSetupMessage(s));
                    setSetupMessage(null);
                    main.post(() -> download.setEnabled(true));
                } catch (Exception e) {
                    setSetupMessage("Setup failed. Close the app and open it again.");
                    showError("Couldn't get ready:\n\n" + e.getMessage());
                }
            }).start();
        }
        handleOpenIntent(getIntent());
    }

    private void setUpList(RecyclerView list, DownloadsAdapter adapter) {
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null); // Rows refresh often; animating them flickers.
        list.setAdapter(adapter);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenIntent(intent);
    }

    /** Tapping a FamilyTube notification lands on the Downloads tab. */
    private void handleOpenIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_SHOW_DOWNLOADS, false)) {
            showDownloadsTab();
        }
    }

    private void showDownloadsTab() {
        nav.setSelectedItemId(R.id.nav_downloads);
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.addListener(this);
        refreshDashboard();
        // Back from the "Allow from this source" screen: carry on with the update.
        if (pendingUpdate != null && UpdateInstaller.hasInstallPermission(this)) {
            UpdateChecker.Update update = pendingUpdate;
            pendingUpdate = null;
            startUpdate(update);
        }
    }

    @Override
    protected void onPause() {
        store.removeListener(this);
        super.onPause();
    }

    /** Once per version, right after an update, list what changed. Returns true if shown. */
    private boolean showWhatsNewIfUpdated() {
        String current = UpdateChecker.currentVersion(this);
        String seen = prefs.getString("whatsNewSeen", null);
        prefs.edit().putString("whatsNewSeen", current).apply();
        if (current.equals(seen)) return false;
        if (seen == null && !wasUpdated()) return false; // Fresh install: nothing "new" yet.
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.whats_new_title, current))
                .setMessage(R.string.whats_new)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        return true;
    }

    /** True if this install is an update rather than a first install. */
    private boolean wasUpdated() {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.lastUpdateTime > info.firstInstallTime;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
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
                        .setMessage(notes + "Tap Update now. Your settings are kept.")
                        .setPositiveButton("Update now", (d, w) -> startUpdate(update))
                        .setNegativeButton("Later", null)
                        .show();
            });
        }).start();
    }

    /** Download the new APK here and open Android's installer, no browser needed. */
    private void startUpdate(UpdateChecker.Update update) {
        if (!UpdateInstaller.canInstallInApp(update)) {
            openLink(update.downloadUrl); // Release without an APK: fall back to the web page.
            return;
        }
        if (!UpdateInstaller.hasInstallPermission(this)) {
            pendingUpdate = update;
            new AlertDialog.Builder(this)
                    .setTitle("One-time permission")
                    .setMessage("Android needs your OK before FamilyTube can update itself.\n\n"
                            + "Tap Continue, switch on \"Allow from this source\", "
                            + "then press Back to return here.")
                    .setPositiveButton("Continue", (d, w) -> {
                        try {
                            startActivity(UpdateInstaller.installPermissionScreen(this));
                        } catch (Exception e) {
                            pendingUpdate = null;
                            openLink(update.downloadUrl);
                        }
                    })
                    .setNegativeButton("Later", (d, w) -> pendingUpdate = null)
                    .show();
            return;
        }

        View box = getLayoutInflater().inflate(R.layout.dialog_update, null);
        LinearProgressIndicator bar = box.findViewById(R.id.update_progress);
        TextView text = box.findViewById(R.id.update_text);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Updating FamilyTube")
                .setView(box)
                .setCancelable(false)
                .show();
        new Thread(() -> {
            try {
                java.io.File apk = UpdateInstaller.download(this, update.downloadUrl,
                        (s, pct) -> main.post(() -> {
                            text.setText(s);
                            bar.setIndeterminate(false);
                            bar.setProgressCompat(pct, true);
                        }));
                main.post(() -> {
                    dialog.dismiss();
                    startActivity(UpdateInstaller.installScreen(this, apk));
                });
            } catch (Exception e) {
                main.post(() -> {
                    dialog.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("Update didn't download")
                            .setMessage("Check the internet and try again, or get it from "
                                    + "the website instead.")
                            .setPositiveButton("Try again", (d, w) -> startUpdate(update))
                            .setNegativeButton("Open website", (d, w) ->
                                    openLink(UpdateChecker.RELEASES_PAGE))
                            .show();
                });
            }
        }).start();
    }

    private void openLink(String link) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
        } catch (Exception e) {
            showError("Open this in your browser:\n" + link);
        }
    }

    // ------------------------------------------------------------ dashboard

    @Override
    public void onDownloadsChanged() {
        refreshDashboard();
    }

    /** The message inside the app when a download ends, wherever the user is in it. */
    @Override
    public void onDownloadFinished(DownloadStore.Item item) {
        boolean done = item.state == DownloadStore.DONE;
        String name = item.fileName != null ? item.fileName : item.label;
        Snackbar bar = Snackbar.make(root,
                done ? "Done: " + name : "Download failed: " + name, Snackbar.LENGTH_LONG)
                .setAnchorView(nav.getVisibility() == View.VISIBLE ? nav : null);
        if (done) {
            bar.setAction("Play", v -> open(item));
        } else {
            bar.setAction("Why?", v -> showError(item.error));
        }
        bar.show();
    }

    private void refreshDashboard() {
        List<DownloadStore.Item> all = store.all();
        recentAdapter.submit(all);
        downloadsAdapter.submit(all);
        recentSection.setVisibility(recentAdapter.getItemCount() > 0 ? View.VISIBLE : View.GONE);
        downloadsEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        clearFinished.setVisibility(all.size() > store.activeCount() ? View.VISIBLE : View.GONE);

        int active = store.activeCount();
        BadgeDrawable badge = nav.getOrCreateBadge(R.id.nav_downloads);
        badge.setVisible(active > 0);
        badge.setNumber(active);
        showActiveCard(active);
    }

    private void showActiveCard(int active) {
        DownloadStore.Item current = store.currentActive();
        if (setupMessage != null) {
            activeCard.setVisibility(View.VISIBLE);
            activeTitle.setText(R.string.app_name);
            activeStatus.setText(setupMessage);
            DownloadsAdapter.showProgress(activeProgress, -1);
        } else if (current != null) {
            activeCard.setVisibility(View.VISIBLE);
            activeTitle.setText(current.label);
            int waiting = active - 1;
            activeStatus.setText(current.status + (waiting > 0 ? " (" + waiting + " more waiting)" : ""));
            DownloadsAdapter.showProgress(activeProgress, current.pct);
        } else {
            activeCard.setVisibility(View.GONE);
        }
    }

    private void setSetupMessage(String message) {
        main.post(() -> {
            setupMessage = message;
            refreshDashboard();
        });
    }

    // ------------------------------------------------------------ download rows

    @Override
    public void open(DownloadStore.Item item) {
        if (item.state == DownloadStore.FAILED) {
            showError(item.error);
        } else if (item.state == DownloadStore.DONE) {
            if (item.fileUri() == null) {
                openDownloads();
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(item.fileUri(), item.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (Exception e) {
                openDownloads();
            }
        }
    }

    @Override
    public void share(DownloadStore.Item item) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(item.mime)
                .putExtra(Intent.EXTRA_STREAM, item.fileUri())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share"));
    }

    @Override
    public void retry(DownloadStore.Item item) {
        DownloadService.retry(this, item);
    }

    @Override
    public void cancel(DownloadStore.Item item) {
        boolean waiting = item.state == DownloadStore.QUEUED;
        DownloadService.cancel(item);
        if (waiting) store.remove(item);
    }

    @Override
    public void remove(DownloadStore.Item item) {
        store.remove(item); // Only the list entry; the saved file stays on the phone.
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
        if (clipPanel.isOn() && Downloader.isInstagram(link)) {
            showError(Downloader.friendlyError("FAMILYTUBE_CLIP_YOUTUBE"));
            return;
        }
        String problem = clipPanel.problem();
        if (problem != null) {
            showError(problem);
            return;
        }
        boolean audioOnly = isAudio();
        int level = Math.max(qualityIndex(), 0);
        String what = audioOnly ? "Music " + Downloader.AUDIO_SHORT[level]
                : "Video " + Downloader.VIDEO_SHORT[level];
        Downloader.Clip clip = clipPanel.clip();
        if (clip != null) what += " clip " + clip.label();
        DownloadStore.Item item = DownloadService.start(this, link, audioOnly, level, what, clip);
        url.setText("");

        // Pasted links arrive without a title; look it up so the list reads properly.
        String kind = what;
        new Thread(() -> {
            VideoInfo info = Downloader.fetchInfo(this, link);
            if (info != null && info.getTitle() != null && item.isActive()) {
                store.rename(item, kind + ": " + info.getTitle());
            }
        }).start();
    }

    /** Get the pasted video's length for the clip slider, once typing has settled. */
    private void lookUpClipLength() {
        main.removeCallbacks(clipLookup);
        if (clipPanel.isOn()) main.postDelayed(clipLookup, 700);
    }

    private final Runnable clipLookup = () -> {
        String link = Downloader.findLink(url.getText().toString().trim());
        if (link == null || Downloader.isInstagram(link)) {
            clipLink = null;
            clipPanel.setDuration(0);
            return;
        }
        if (link.equals(clipLink)) return;
        clipLink = link;
        new Thread(() -> {
            VideoInfo info = Downloader.fetchInfo(this, link);
            main.post(() -> {
                if (info != null && link.equals(clipLink)) {
                    clipPanel.setDuration(info.getDuration());
                }
            });
        }).start();
    };

    private boolean isAudio() {
        return type.getCheckedButtonId() == R.id.type_audio;
    }

    private String qualityKey() {
        return isAudio() ? "audioQuality" : "videoQuality";
    }

    private int qualityIndex() {
        return quality.indexOfChild(quality.findViewById(quality.getCheckedChipId()));
    }

    /** Swap the quality chips between video and music, restoring the last pick. */
    private void showQualityChoices() {
        String[] names = isAudio() ? Downloader.AUDIO_SHORT : Downloader.VIDEO_SHORT;
        int saved = prefs.getInt(qualityKey(), 1); // 720p video / high-quality music
        if (saved >= names.length) saved = names.length - 1;
        showingChoices = true;
        quality.removeAllViews();
        for (int i = 0; i < names.length; i++) {
            Chip chip = new Chip(this);
            chip.setId(View.generateViewId());
            chip.setText(names[i]);
            chip.setCheckable(true);
            quality.addView(chip);
        }
        quality.check(quality.getChildAt(saved).getId());
        showingChoices = false;
        showQualityHint(saved);
    }

    private void showQualityHint(int index) {
        qualityHint.setText((isAudio() ? Downloader.AUDIO_LABELS : Downloader.VIDEO_LABELS)[index]);
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
