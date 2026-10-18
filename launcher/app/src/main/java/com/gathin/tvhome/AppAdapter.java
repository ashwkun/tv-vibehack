package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;

/**
 * Home shortcuts: fixed dock slots, the user's ordered grid, and the App library entry.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        boolean onTileKey(int position, int keyCode);
        void onTileClicked(int position);
        void onTileLongPressed(int position);
    }

    static final class Entry {
        static final int APP = 0, EMPTY = 1, UNAVAILABLE = 2, LIBRARY = 3,
                RECENT = 4, RECENT_HEADER = 5, ALL_HEADER = 6;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        final int kind;
        final String key;
        final String canonicalKey;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.kind = APP;
            this.key = component.flattenToString();
            this.canonicalKey = key;
        }

        Entry(Entry app) {
            this.label = app.label;
            this.component = app.component;
            this.pkg = app.pkg;
            this.banner = app.banner;
            this.icon = app.icon;
            this.kind = RECENT;
            this.key = "recent:" + app.key;
            this.canonicalKey = app.key;
            this.ambient = app.ambient;
        }

        Entry(String label, String pkg, int kind, String key) {
            this.label = label;
            this.pkg = pkg;
            this.kind = kind;
            this.key = key;
            this.component = null;
            this.banner = null;
            this.icon = null;
            this.canonicalKey = null;
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    private final SharedPreferences layoutPreferences;
    private HomeLayoutState layout;
    private HomeLayoutState beforeMove;
    private String movingKey;
    private final RecentAppsState recentApps;
    private SectionNavigation sections;
    private final Map<String, Entry> available = new LinkedHashMap<>();
    private final Map<String, AppCatalog.App> catalogApps = new LinkedHashMap<>();
    private long catalogRevision = -1;
    private final List<Entry> items = new ArrayList<>();
    private final StableItemIds stableIds = new StableItemIds();

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        layoutPreferences = ctx.getSharedPreferences("home_layout", Context.MODE_PRIVATE);
        layout = new HomeLayoutState(dockSize,
                readList("dock", Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps))),
                readList("grid", Collections.emptyList()),
                readList("hidden", Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps))));
        recentApps = new RecentAppsState(readList("recent", Collections.emptyList()));
        sections = new SectionNavigation(dockSize, 0, 0);
        setHasStableIds(true);
    }

    boolean hasCatalog() { return catalogRevision >= 0; }

    boolean matchesCatalog(AppCatalog.Snapshot snapshot) { return catalogRevision == snapshot.revision; }

    /** Only changed catalog records get new view-owned drawables. Called on the main thread. */
    boolean applyCatalog(AppCatalog.Snapshot snapshot) {
        if (matchesCatalog(snapshot)) return false;
        boolean cancelledMove = beforeMove != null;
        if (cancelledMove) {
            layout = beforeMove;
            beforeMove = null;
            movingKey = null;
        }
        Map<String, Entry> next = new LinkedHashMap<>();
        Map<String, AppCatalog.App> records = new LinkedHashMap<>();
        SharedPreferences.Editor labels = null;
        Set<String> tvPackages = new HashSet<>();
        for (AppCatalog.App app : snapshot.apps) {
            Entry entry = catalogApps.get(app.pkg) == app ? available.get(app.pkg) : null;
            if (entry == null) {
                entry = new Entry(app.label, app.component, app.newBanner(ctx.getResources()), app.newIcon(ctx.getResources()));
                if (!app.label.equals(layoutPreferences.getString("label:" + app.pkg, null))) {
                    if (labels == null) labels = layoutPreferences.edit();
                    labels.putString("label:" + app.pkg, app.label);
                }
            }
            next.put(app.pkg, entry);
            records.put(app.pkg, app);
            if (app.television) tvPackages.add(app.pkg);
        }
        if (labels != null) labels.apply();
        available.clear();
        available.putAll(next);
        catalogApps.clear();
        catalogApps.putAll(records);
        catalogRevision = snapshot.revision;
        List<String> defaults = new ArrayList<>();
        for (Entry app : libraryEntries()) if (tvPackages.contains(app.pkg)) defaults.add(app.pkg);
        List<String> oldGrid = layout.grid();
        layout.reconcileDefaultApps(defaults);
        if (!oldGrid.equals(layout.grid())) saveLayout();
        return rebuildItems(cancelledMove);
    }

    /** MRU updates do not require discovering packages or loading their artwork again. */
    boolean refreshRecentApps() {
        return hasCatalog() && rebuildItems(false);
    }

    private List<String> readList(String key, List<String> fallback) {
        String serialized = layoutPreferences.getString(key, null);
        if (serialized == null) return new ArrayList<>(fallback);
        try {
            JSONArray array = new JSONArray(serialized);
            List<String> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
            return result;
        } catch (JSONException e) {
            return new ArrayList<>(fallback);
        }
    }

    private void saveLayout() {
        layoutPreferences.edit()
                .putString("dock", new JSONArray(layout.dock()).toString())
                .putString("grid", new JSONArray(layout.grid()).toString())
                .putString("hidden", new JSONArray(layout.hidden()).toString()).apply();
    }

    private void rebuildItems() { rebuildItems(true); }

    private boolean rebuildItems(boolean force) {
        List<Entry> next = new ArrayList<>();
        List<String> dock = layout.dock();
        for (int slot = 0; slot < dockSize; slot++) {
            String pkg = dock.get(slot);
            Entry app = available.get(pkg);
            if (pkg.isEmpty()) {
                next.add(new Entry(ctx.getString(R.string.add_app), "", Entry.EMPTY, HomeLayoutState.emptySlotKey(slot)));
            } else if (app != null) {
                next.add(app);
            } else {
                String label = layoutPreferences.getString("label:" + pkg, null);
                next.add(new Entry(label == null ? ctx.getString(R.string.unavailable_app)
                        : ctx.getString(R.string.unavailable_dock_app, label), pkg,
                        Entry.UNAVAILABLE, HomeLayoutState.unavailablePinKey(pkg)));
            }
        }
        List<String> recent = recentApps.visible(available.keySet(), layout.hidden(), new HashSet<>(dock), dockSize);
        if (recent.size() < dockSize) recent = Collections.emptyList();
        List<String> grid = layout.visibleGrid(available.keySet());
        sections = new SectionNavigation(dockSize, recent.size(), grid.size());
        if (!recent.isEmpty()) {
            next.add(new Entry(ctx.getString(R.string.recent_apps), "", Entry.RECENT_HEADER, "section:recent"));
            for (String pkg : recent) next.add(new Entry(available.get(pkg)));
        }
        next.add(new Entry(ctx.getString(R.string.all_apps), "", Entry.ALL_HEADER, "section:all-apps"));
        for (String pkg : grid) next.add(available.get(pkg));
        next.add(new Entry(ctx.getString(R.string.app_library), "", Entry.LIBRARY, HomeLayoutState.libraryKey()));
        if (!force && sameRows(items, next)) return false;
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
        return true;
    }

    private static boolean sameRows(List<Entry> before, List<Entry> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            Entry a = before.get(i), b = after.get(i);
            if (a.kind != b.kind || !a.key.equals(b.key) || !a.label.equals(b.label)
                    || a.banner != b.banner || a.icon != b.icon) return false;
        }
        return true;
    }

    List<Entry> libraryEntries() {
        List<Entry> result = new ArrayList<>(available.values());
        Collections.sort(result, (a, b) -> {
            int compared = a.label.compareToIgnoreCase(b.label);
            return compared != 0 ? compared : a.pkg.compareTo(b.pkg);
        });
        return result;
    }

    Entry entryAt(int position) { return position >= 0 && position < items.size() ? items.get(position) : null; }
    boolean isLibraryPosition(int position) { return position >= 0 && position == items.size() - 1; }
    boolean isOnHome(String pkg) { return layout.isOnHome(pkg); }
    boolean isMoving() { return beforeMove != null; }
    String movingKey() { return movingKey; }
    int homeItemCount() { return dockSize + sections.gridCount; }
    int homeIndex(int adapterPosition) { return sections.homeIndex(adapterPosition); }
    int adapterPositionForHomeIndex(int homeIndex) { return sections.adapterPosition(homeIndex); }
    int horizontalTarget(int position, int direction) { return sections.horizontal(position, direction); }
    int verticalTarget(int position, int direction) { return sections.vertical(position, direction); }
    int nearestFocusablePosition(int position) { return sections.nearestFocusable(position); }
    int firstBelowDock() { return sections.vertical(0, 1); }
    boolean isFullSpan(int position) {
        Entry entry = entryAt(position);
        return entry != null && (entry.kind == Entry.LIBRARY || entry.kind == Entry.RECENT_HEADER
                || entry.kind == Entry.ALL_HEADER);
    }

    void addToHome(String pkg) { layout.addToHome(pkg); saveLayout(); rebuildItems(); }
    void setDock(int slot, String pkg) { layout.setDock(slot, pkg); saveLayout(); rebuildItems(); }
    void removeFromHome(String pkg) { layout.removeFromHome(pkg); saveLayout(); rebuildItems(); }

    void beginMove(int position) {
        Entry app = entryAt(position);
        if (app != null && app.kind == Entry.RECENT) {
            position = positionOfKey(app.canonicalKey);
            app = entryAt(position);
        }
        if (app == null || app.kind != Entry.APP) return;
        beforeMove = layout.copy();
        movingKey = app.key;
        notifyDataSetChanged(); // Immediately exposes the moving state to accessibility.
    }

    void moveSelectedTo(int target) {
        if (!isMoving()) return;
        int from = homeIndex(positionOfKey(movingKey));
        if (from < 0 || target < 0 || target >= homeItemCount()) return;
        layout.move(from, target, layout.visibleGrid(available.keySet()));
        rebuildItems();
    }

    String finishMove(boolean save) {
        if (!isMoving()) return null;
        String key = movingKey;
        if (save) saveLayout();
        else layout = beforeMove;
        beforeMove = null;
        movingKey = null;
        rebuildItems(); // Clears moving descriptions even if the same holder keeps focus.
        return key;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == 1 ? R.layout.item_section_header : R.layout.item_app;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public int getItemViewType(int position) {
        int kind = items.get(position).kind;
        return kind == Entry.RECENT_HEADER || kind == Entry.ALL_HEADER ? 1 : 0;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        if (e.kind == Entry.RECENT_HEADER || e.kind == Entry.ALL_HEADER) {
            ((TextView) h.itemView.findViewById(R.id.sectionTitle)).setText(e.label);
            h.itemView.findViewById(R.id.sectionHint).setVisibility(e.kind == Entry.RECENT_HEADER ? View.VISIBLE : View.GONE);
            return;
        }
        h.name.setText(e.label);
        h.card.setContentDescription(e.key.equals(movingKey)
                ? ctx.getString(R.string.moving_description, e.label)
                : e.kind == Entry.EMPTY ? ctx.getString(R.string.empty_dock_slot_description, position + 1) : e.label);
        h.card.setAlpha(e.kind == Entry.UNAVAILABLE ? 0.6f : 1f);
        h.banner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        h.icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        h.label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        h.name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        h.name.setVisibility(e.kind == Entry.RECENT ? View.GONE
                : position < dockSize || e.kind == Entry.LIBRARY ? View.INVISIBLE : View.VISIBLE);
        boolean focused = h.card.hasFocus();
        h.name.animate().cancel();
        h.card.animate().cancel();
        h.name.setAlpha(focused ? 1f : 0f);
        float density = ctx.getResources().getDisplayMetrics().density;
        h.name.setTranslationY(density * (focused ? 4f : -6f));
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            if (e.kind == Entry.LIBRARY) h.icon.setImageResource(R.drawable.ic_apps);
            else if (e.kind == Entry.EMPTY) h.icon.setImageResource(android.R.drawable.ic_input_add);
            else if (e.kind == Entry.UNAVAILABLE) h.icon.setImageResource(android.R.drawable.ic_dialog_alert);
            else h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> {
            int current = h.getBindingAdapterPosition();
            if (current != RecyclerView.NO_POSITION) listener.onTileClicked(current);
        });
        h.card.setOnLongClickListener(v -> {
            int current = h.getBindingAdapterPosition();
            if (current != RecyclerView.NO_POSITION) listener.onTileLongPressed(current);
            return true;
        });
        ViewCompat.replaceAccessibilityAction(h.card,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                ctx.getString(R.string.app_actions), (view, arguments) -> view.performLongClick());
    }

    void openAppInfo(Entry e) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            Toast.makeText(ctx, R.string.action_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            recentApps.record(e.pkg);
            layoutPreferences.edit().putString("recent", new JSONArray(recentApps.snapshot()).toString()).apply();
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.app_unavailable, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return stableIds.idFor(keyAt(position));
    }

    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).key : null;
    }

    int positionOfKey(String key) {
        if (key == null) return RecyclerView.NO_POSITION;
        for (int i = 0; i < items.size(); i++) {
            if (key.equals(keyAt(i))) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            if (card == null) return; // Section headings never take D-pad focus.

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
