package com.familytube.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows the WhatsApp statuses the user has already watched, so they can view or save
 * them before they disappear. Android 11+ only lets apps read WhatsApp's status folder
 * after the user grants it once through the system folder picker.
 */
public class StatusActivity extends AppCompatActivity {

    private static final String EXTERNAL = "com.android.externalstorage.documents";
    private static final String WHATSAPP =
            "primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses";
    private static final String WHATSAPP_BUSINESS =
            "primary:Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses";
    // Before 2021 WhatsApp kept statuses here; older phones may still use it.
    private static final String WHATSAPP_OLD = "primary:WhatsApp/Media/.Statuses";
    private static final String SAVE_FOLDER = Downloader.FOLDER + "/WhatsApp Status";

    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private SharedPreferences prefs;
    private Set<String> saved;
    private final List<DocumentFile> items = new ArrayList<>();
    private StatusAdapter adapter;
    private View accessPanel;
    private TextView empty;
    private RecyclerView grid;

    private final ActivityResultLauncher<Uri> pickFolder = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::onFolderPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_status);

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        prefs = getSharedPreferences("app", MODE_PRIVATE);
        saved = new HashSet<>(prefs.getStringSet("savedStatuses", new HashSet<>()));
        accessPanel = findViewById(R.id.access_panel);
        empty = findViewById(R.id.empty);
        grid = findViewById(R.id.grid);

        grid.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new StatusAdapter();
        grid.setAdapter(adapter);

        findViewById(R.id.allow_whatsapp).setOnClickListener(v -> askForFolder(
                Build.VERSION.SDK_INT >= 30 ? WHATSAPP : WHATSAPP_OLD));
        findViewById(R.id.allow_business).setOnClickListener(v -> askForFolder(WHATSAPP_BUSINESS));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(); // Statuses change as the user watches more in WhatsApp.
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------ folder access

    private void askForFolder(String documentId) {
        try {
            pickFolder.launch(DocumentsContract.buildDocumentUri(EXTERNAL, documentId));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open the folder picker on this phone.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void onFolderPicked(Uri tree) {
        if (tree == null) return; // User backed out.
        getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String id = DocumentsContract.getTreeDocumentId(tree);
        if (!id.endsWith(".Statuses")) {
            Toast.makeText(this, "That isn't the WhatsApp status folder, but we'll try. "
                    + "If nothing shows, tap Allow again and don't change folders.",
                    Toast.LENGTH_LONG).show();
        }
        prefs.edit().putString("statusTree", tree.toString()).apply();
        load();
    }

    /** The granted status folder, or null if the user hasn't allowed one yet. */
    private Uri grantedFolder() {
        String stored = prefs.getString("statusTree", null);
        if (stored == null) return null;
        Uri tree = Uri.parse(stored);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isReadPermission()) return tree;
        }
        return null;
    }

    // ------------------------------------------------------------ listing

    private void load() {
        Uri tree = grantedFolder();
        if (tree == null) {
            accessPanel.setVisibility(View.VISIBLE);
            empty.setVisibility(View.GONE);
            grid.setVisibility(View.GONE);
            return;
        }
        accessPanel.setVisibility(View.GONE);
        worker.execute(() -> {
            List<DocumentFile> found = new ArrayList<>();
            DocumentFile dir = DocumentFile.fromTreeUri(this, tree);
            if (dir != null) {
                for (DocumentFile f : dir.listFiles()) {
                    String type = f.getType();
                    if (f.isFile() && type != null
                            && (type.startsWith("image/") || type.startsWith("video/"))) {
                        found.add(f);
                    }
                }
            }
            found.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            main.post(() -> {
                if (isFinishing()) return;
                items.clear();
                items.addAll(found);
                adapter.notifyDataSetChanged();
                empty.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
                grid.setVisibility(found.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    // ------------------------------------------------------------ view / save

    private void open(DocumentFile f) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(f.getUri(), f.getType())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            Toast.makeText(this, "No app found to open this.", Toast.LENGTH_SHORT).show();
        }
    }

    private void save(DocumentFile f, MaterialButton button) {
        button.setEnabled(false);
        worker.execute(() -> {
            String error = null;
            try (InputStream in = getContentResolver().openInputStream(f.getUri())) {
                if (in == null) throw new Exception("Couldn't read the status.");
                Downloader.saveStream(this, in, f.getName(), SAVE_FOLDER);
            } catch (Exception e) {
                error = e.getMessage();
            }
            String err = error;
            main.post(() -> {
                if (err != null) {
                    button.setEnabled(true);
                    Toast.makeText(this, "Couldn't save: " + err, Toast.LENGTH_LONG).show();
                    return;
                }
                saved.add(f.getName());
                prefs.edit().putStringSet("savedStatuses", new HashSet<>(saved)).apply();
                button.setText(R.string.status_saved);
                Toast.makeText(this, "Saved in Download > FamilyTube > WhatsApp Status",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ------------------------------------------------------------ thumbnails

    private Bitmap makeThumb(DocumentFile f, int size) {
        try {
            String type = f.getType();
            if (type != null && type.startsWith("video/")) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(this, f.getUri());
                    Bitmap frame = r.getFrameAtTime(0);
                    return frame == null ? null : scale(frame, size);
                } finally {
                    r.release();
                }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(f.getUri())) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (Math.min(bounds.outWidth, bounds.outHeight) / (opts.inSampleSize * 2) >= size) {
                opts.inSampleSize *= 2;
            }
            try (InputStream in = getContentResolver().openInputStream(f.getUri())) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap scale(Bitmap src, int size) {
        int shortSide = Math.min(src.getWidth(), src.getHeight());
        if (shortSide <= size) return src;
        float k = (float) size / shortSide;
        Bitmap out = Bitmap.createScaledBitmap(src,
                Math.round(src.getWidth() * k), Math.round(src.getHeight() * k), true);
        if (out != src) src.recycle();
        return out;
    }

    // ------------------------------------------------------------ grid

    private final class StatusAdapter extends RecyclerView.Adapter<StatusAdapter.Holder> {

        final class Holder extends RecyclerView.ViewHolder {
            final View frame;
            final ImageView thumb;
            final ImageView play;
            final MaterialButton save;

            Holder(View v) {
                super(v);
                frame = v.findViewById(R.id.frame);
                thumb = v.findViewById(R.id.thumb);
                play = v.findViewById(R.id.play);
                save = v.findViewById(R.id.save);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_status, parent, false);
            Holder h = new Holder(v);
            // Square-ish cells: 3 per row, a little taller than wide.
            int cell = parent.getWidth() / 3;
            if (cell > 0) h.frame.getLayoutParams().height = Math.round(cell * 1.3f);
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            DocumentFile f = items.get(position);
            String key = f.getUri().toString();
            String type = f.getType();
            h.play.setVisibility(type != null && type.startsWith("video/")
                    ? View.VISIBLE : View.GONE);
            h.thumb.setOnClickListener(v -> open(f));
            boolean isSaved = saved.contains(f.getName());
            h.save.setText(isSaved ? R.string.status_saved : R.string.status_save);
            h.save.setEnabled(!isSaved);
            h.save.setOnClickListener(v -> save(f, h.save));

            h.thumb.setTag(key);
            Bitmap cached = thumbs.get(key);
            h.thumb.setImageBitmap(cached);
            if (cached == null) {
                worker.execute(() -> {
                    Bitmap b = makeThumb(f, 300);
                    if (b == null) return;
                    thumbs.put(key, b);
                    main.post(() -> {
                        if (key.equals(h.thumb.getTag())) h.thumb.setImageBitmap(b);
                    });
                });
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
