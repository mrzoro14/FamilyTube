package com.familytube.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

/**
 * Opened from YouTube's Share button. Shows a small panel over YouTube with the
 * download choices; one tap starts a background download and closes the panel.
 */
public class ShareActivity extends AppCompatActivity {

    private View sheet;
    private boolean started = false;
    private boolean closing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String link = Downloader.findLink(intent == null ? null
                : intent.getStringExtra(Intent.EXTRA_TEXT));
        if (link == null) {
            Toast.makeText(this, "No YouTube or Instagram link found in what was shared.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Android 13+ asks before an app may show notifications (our progress bar).
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }

        setContentView(R.layout.sheet_share);
        sheet = findViewById(R.id.sheet);
        TextView title = findViewById(R.id.title);
        ChipGroup video = findViewById(R.id.video_choices);
        ChipGroup audio = findViewById(R.id.audio_choices);

        // Tap outside the card, or press Back, to close without downloading.
        findViewById(R.id.scrim).setOnClickListener(v -> close());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                close();
            }
        });

        // Keep the card's buttons above the phone's navigation bar.
        int basePadding = sheet.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(sheet, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    basePadding + bars.bottom);
            return insets;
        });

        for (int i = 0; i < Downloader.VIDEO_SHORT.length; i++) {
            int level = i;
            String text = Downloader.VIDEO_SHORT[i] + (i == 1 ? "  ★" : "");
            video.addView(choice(text, v -> begin(link, false, level,
                    "Video " + Downloader.VIDEO_SHORT[level], title)));
        }
        for (int i = 0; i < Downloader.AUDIO_SHORT.length; i++) {
            int level = i;
            audio.addView(choice("MP3 " + Downloader.AUDIO_SHORT[i], v -> begin(link, true, level,
                    "Music " + Downloader.AUDIO_SHORT[level], title)));
        }

        // Slide the card up from the bottom.
        sheet.setVisibility(View.INVISIBLE);
        sheet.post(() -> {
            sheet.setTranslationY(sheet.getHeight());
            sheet.setVisibility(View.VISIBLE);
            sheet.animate().translationY(0).setDuration(220).start();
        });

        // Show the video's title so it's clear what's being saved.
        new Thread(() -> {
            String name = Downloader.fetchTitle(this, link);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                title.setText(name != null ? name : link);
            });
        }).start();
    }

    private Chip choice(String text, View.OnClickListener onClick) {
        Chip chip = new Chip(this);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        chip.setChipMinHeight(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 52, getResources().getDisplayMetrics()));
        chip.setOnClickListener(onClick);
        return chip;
    }

    private void begin(String link, boolean audio, int level, String what, TextView title) {
        if (started) return;
        started = true;
        CharSequence name = title.getText();
        String label = getString(R.string.getting_details).contentEquals(name)
                ? what : what + ": " + name;
        DownloadService.start(this, link, audio, level, label);
        Toast.makeText(this, "Downloading " + what.toLowerCase()
                + ". Check the notification bar.", Toast.LENGTH_LONG).show();
        close();
    }

    /** Slide the card away, then close so YouTube is back in front. */
    private void close() {
        if (closing) return;
        closing = true;
        sheet.animate().translationY(sheet.getHeight()).setDuration(180)
                .withEndAction(this::finish).start();
    }
}
