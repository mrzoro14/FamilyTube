package com.familytube.app;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;

/** Rows for the Downloads tab and, with a limit, the Recent list on Home. */
final class DownloadsAdapter extends RecyclerView.Adapter<DownloadsAdapter.Holder> {

    interface Actions {
        void open(DownloadStore.Item item);

        void share(DownloadStore.Item item);

        void retry(DownloadStore.Item item);

        void cancel(DownloadStore.Item item);

        void remove(DownloadStore.Item item);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final LinearProgressIndicator bar;
        final ImageButton action1;
        final ImageButton action2;

        Holder(View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
            bar = v.findViewById(R.id.bar);
            action1 = v.findViewById(R.id.action1);
            action2 = v.findViewById(R.id.action2);
        }
    }

    private final Actions actions;
    private final int limit; // > 0: show only that many finished items
    private final List<DownloadStore.Item> items = new ArrayList<>();

    DownloadsAdapter(Actions actions, int limit) {
        this.actions = actions;
        this.limit = limit;
    }

    void submit(List<DownloadStore.Item> all) {
        items.clear();
        for (DownloadStore.Item i : all) {
            if (limit > 0 && (i.isActive() || items.size() >= limit)) continue;
            items.add(i);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_download, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        DownloadStore.Item item = items.get(position);
        h.icon.setImageResource(iconFor(item));
        h.itemView.setOnClickListener(v -> actions.open(item));
        h.action1.setVisibility(View.GONE);
        h.bar.setVisibility(item.isActive() ? View.VISIBLE : View.GONE);

        switch (item.state) {
            case DownloadStore.DONE:
                h.title.setText(item.fileName != null ? item.fileName : item.label);
                h.subtitle.setText(when(item));
                if (item.fileUri() != null) {
                    show(h.action1, R.drawable.ic_share, "Share", v -> actions.share(item));
                }
                show(h.action2, R.drawable.ic_close, "Remove from list",
                        v -> actions.remove(item));
                break;
            case DownloadStore.FAILED:
                h.title.setText(item.label);
                h.subtitle.setText(item.error);
                show(h.action1, R.drawable.ic_retry, "Retry", v -> actions.retry(item));
                show(h.action2, R.drawable.ic_close, "Remove from list",
                        v -> actions.remove(item));
                break;
            default:
                h.title.setText(item.label);
                h.subtitle.setText(item.status);
                showProgress(h.bar, item.pct);
                show(h.action2, R.drawable.ic_close, "Cancel", v -> actions.cancel(item));
                break;
        }
    }

    private static CharSequence when(DownloadStore.Item item) {
        return DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS);
    }

    private static int iconFor(DownloadStore.Item item) {
        if (item.mime != null && item.mime.startsWith("image/")) return R.drawable.ic_photo;
        return item.audio ? R.drawable.ic_music : R.drawable.ic_play;
    }

    private static void show(ImageButton button, int icon, String name,
                             View.OnClickListener onClick) {
        button.setVisibility(View.VISIBLE);
        button.setImageResource(icon);
        button.setContentDescription(name);
        button.setOnClickListener(onClick);
    }

    /** pct < 0 shows a moving bar. It can only switch to that while hidden. */
    static void showProgress(LinearProgressIndicator bar, int pct) {
        if (pct < 0) {
            if (bar.isIndeterminate()) return;
            int visibility = bar.getVisibility();
            bar.setVisibility(View.INVISIBLE);
            bar.setIndeterminate(true);
            bar.setVisibility(visibility);
        } else {
            bar.setIndeterminate(false);
            bar.setProgressCompat(pct, false);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
