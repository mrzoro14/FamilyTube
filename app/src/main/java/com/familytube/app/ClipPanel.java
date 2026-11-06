package com.familytube.app;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.RangeSlider;

import java.util.Arrays;
import java.util.List;

/**
 * The "only download a part" controls: a switch, a slider across the whole video, and
 * From / To boxes for exact times. The slider and the boxes stay in step.
 */
final class ClipPanel {

    private final View panel;
    private final MaterialSwitch toggle;
    private final View body;
    private final RangeSlider slider;
    private final EditText from;
    private final EditText to;
    private final TextView length;
    private int duration = 0; // seconds; 0 until the video's length is known
    private boolean syncing = false;
    private Runnable onSwitchedOn;

    ClipPanel(View root) {
        panel = root.findViewById(R.id.clip_panel);
        toggle = root.findViewById(R.id.clip_switch);
        body = root.findViewById(R.id.clip_body);
        slider = root.findViewById(R.id.clip_slider);
        from = root.findViewById(R.id.clip_from);
        to = root.findViewById(R.id.clip_to);
        length = root.findViewById(R.id.clip_length);

        toggle.setOnCheckedChangeListener((b, on) -> {
            body.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on && onSwitchedOn != null) onSwitchedOn.run();
        });
        slider.setLabelFormatter(v -> Downloader.formatTime((int) v));
        slider.setTickVisible(false);
        slider.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) showSliderTimes();
        });

        TextWatcher typing = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!syncing) moveSliderToTimes();
            }
        };
        from.addTextChangedListener(typing);
        to.addTextChangedListener(typing);
    }

    void setOnSwitchedOn(Runnable action) {
        onSwitchedOn = action;
    }

    /** Clips are for YouTube only, so the popup hides this for Instagram. */
    void hide() {
        panel.setVisibility(View.GONE);
        toggle.setChecked(false);
    }

    boolean isOn() {
        return toggle.isChecked();
    }

    /** Give the video's length to enable the slider; 0 means unknown (boxes only). */
    void setDuration(int seconds) {
        if (seconds == duration) return;
        duration = seconds;
        if (seconds <= 0) {
            slider.setVisibility(View.GONE);
            return;
        }
        int start = Math.max(Downloader.parseTime(text(from)), 0);
        int end = Downloader.parseTime(text(to));
        if (start >= seconds) start = 0;
        if (end <= start || end > seconds) end = Math.min(start + 30, seconds);
        slider.setValues(0f, 1f); // keep values valid while the range changes
        slider.setValueFrom(0f);
        slider.setValueTo(seconds);
        slider.setStepSize(1f);
        slider.setValues((float) start, (float) end);
        slider.setVisibility(View.VISIBLE);
        showSliderTimes();
    }

    private void showSliderTimes() {
        List<Float> v = slider.getValues();
        syncing = true;
        from.setText(Downloader.formatTime(Math.round(v.get(0))));
        to.setText(Downloader.formatTime(Math.round(v.get(1))));
        syncing = false;
        showLength();
    }

    private void moveSliderToTimes() {
        int start = Downloader.parseTime(text(from));
        int end = Downloader.parseTime(text(to));
        if (duration > 0 && start >= 0 && end > start && end <= duration) {
            slider.setValues(Arrays.asList((float) start, (float) end));
        }
        showLength();
    }

    private void showLength() {
        int start = Downloader.parseTime(text(from));
        int end = Downloader.parseTime(text(to));
        length.setText(start >= 0 && end > start
                ? "Clip length: " + Downloader.formatTime(end - start)
                : panel.getContext().getString(R.string.clip_help));
    }

    /** What's wrong with the chosen times, or null if they're fine. */
    String problem() {
        if (!isOn()) return null;
        int start = Downloader.parseTime(text(from));
        int end = Downloader.parseTime(text(to));
        if (start < 0 || end < 0) {
            return "Type the clip times like 1:05 (1 minute 5 seconds) or 1:02:30.";
        }
        if (end <= start) return "The To time must be after the From time.";
        if (duration > 0 && end > duration) {
            return "This video is only " + Downloader.formatTime(duration) + " long.";
        }
        return null;
    }

    /** The chosen part, or null when the switch is off. Check problem() first. */
    Downloader.Clip clip() {
        if (!isOn()) return null;
        return new Downloader.Clip(Downloader.parseTime(text(from)),
                Downloader.parseTime(text(to)));
    }

    private static String text(EditText box) {
        return box.getText() == null ? "" : box.getText().toString();
    }
}
