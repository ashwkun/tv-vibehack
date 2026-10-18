package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One activity-owned catalog: package/resource work is serial and never runs on the UI thread. */
final class AppCatalog implements AutoCloseable {
    interface Callback {
        void onLoaded(Snapshot snapshot);
    }

    static final class App {
        final String label;
        final ComponentName component;
        final String pkg;
        final boolean television;
        final Drawable.ConstantState bannerState;
        final Drawable.ConstantState iconState;

        private App(String label, ComponentName component, boolean television,
                    Drawable.ConstantState bannerState, Drawable.ConstantState iconState) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.television = television;
            this.bannerState = bannerState;
            this.iconState = iconState;
        }

        Drawable newBanner(Resources resources) {
            return bannerState == null ? null : bannerState.newDrawable(resources).mutate();
        }

        Drawable newIcon(Resources resources) {
            return iconState == null ? null : iconState.newDrawable(resources).mutate();
        }
    }

    static final class Snapshot {
        final long revision;
        final String configurationKey;
        final List<App> apps;
        final int loadedCount;
        final int reusedCount;

        private Snapshot(long revision, String configurationKey, List<App> apps,
                         int loadedCount, int reusedCount) {
            this.revision = revision;
            this.configurationKey = configurationKey;
            this.apps = apps;
            this.loadedCount = loadedCount;
            this.reusedCount = reusedCount;
        }
    }

    private static final String TAG = "AppCatalog";
    private static final Comparator<ResolveInfo> BY_COMPONENT = Comparator.comparing(
            info -> new ComponentName(info.activityInfo.packageName, info.activityInfo.name).flattenToString());

    private final Context applicationContext;
    private final boolean debug;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "home-app-catalog"));
    private final AppCatalogCache<App> cache = new AppCatalogCache<>();
    private volatile Snapshot latest;
    private long requestGeneration;
    private long nextRevision;
    private boolean closed;

    AppCatalog(Context context) {
        applicationContext = Objects.requireNonNull(context.getApplicationContext(), "applicationContext");
        debug = (applicationContext.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /** Old resource configurations are never offered as a warm-start snapshot. */
    synchronized Snapshot snapshot() {
        Snapshot snapshot = latest;
        return !closed && snapshot != null && snapshot.configurationKey.equals(currentConfigurationKey())
                ? snapshot : null;
    }

    synchronized void refresh(Callback callback) {
        Objects.requireNonNull(callback, "callback");
        if (closed) return;
        long generation = ++requestGeneration;
        Configuration configuration = new Configuration(applicationContext.getResources().getConfiguration());
        String configurationKey = configurationKey(configuration);
        worker.execute(() -> load(generation, configuration, configurationKey, callback));
    }

    synchronized void invalidatePackage(String pkg) {
        if (closed) return;
        cache.invalidatePackage(pkg);
        requestGeneration++;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        requestGeneration++;
        latest = null;
        worker.shutdownNow();
    }

    private synchronized boolean isCurrent(long generation, String configurationKey) {
        return !closed && generation == requestGeneration
                && configurationKey.equals(currentConfigurationKey());
    }

    private String currentConfigurationKey() {
        return configurationKey(new Configuration(applicationContext.getResources().getConfiguration()));
    }

    private void load(long generation, Configuration configuration, String configurationKey, Callback callback) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                loadOnce(generation, configuration, configurationKey, callback);
                return;
            } catch (PackageManager.NameNotFoundException error) {
                if (attempt == 0 && isCurrent(generation, configurationKey)) {
                    // Discovery raced an uninstall/update. Retry once from a fresh package list.
                    if (debug) Log.d(TAG, "Package changed during refresh; retrying discovery", error);
                } else {
                    if (debug) Log.d(TAG, "Refresh failed; keeping previous catalog", error);
                    return;
                }
            }
        }
    }

    private void loadOnce(long generation, Configuration configuration, String configurationKey, Callback callback)
            throws PackageManager.NameNotFoundException {
        if (!isCurrent(generation, configurationKey)) return; // Skip superseded jobs still in the queue.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        long started = SystemClock.uptimeMillis();
        Trace.beginSection("AppCatalog.refresh");
        try {
            Context loadingContext = applicationContext.createConfigurationContext(configuration);
            PackageManager pm = loadingContext.getPackageManager();
            Resources resources = loadingContext.getResources();
            Map<String, ResolveInfo> resolved = new LinkedHashMap<>();
            Set<String> televisionPackages = new HashSet<>();
            Trace.beginSection("AppCatalog.discovery");
            try {
                addResolved(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0), resolved, televisionPackages);
                addResolved(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_LAUNCHER), 0), resolved, null);
            } finally {
                Trace.endSection();
            }
            long discovered = SystemClock.uptimeMillis();
            if (!isCurrent(generation, configurationKey)) return;

            AppCatalogCache.Scan scan = cache.beginScan(configurationKey);
            List<App> apps = new ArrayList<>(resolved.size());
            Set<String> installedKeys = new HashSet<>();
            int loadedCount = 0;
            int reusedCount = 0;
            Trace.beginSection("AppCatalog.resources");
            try {
                for (Map.Entry<String, ResolveInfo> candidate : resolved.entrySet()) {
                    if (!isCurrent(generation, configurationKey)) return;
                    String pkg = candidate.getKey();
                    ResolveInfo info = candidate.getValue();
                    ComponentName component = new ComponentName(pkg, info.activityInfo.name);
                    String componentKey = component.flattenToString();
                    PackageInfo packageInfo = pm.getPackageInfo(pkg, 0);
                    long version = Build.VERSION.SDK_INT >= 28 ? packageInfo.getLongVersionCode() : packageInfo.versionCode;
                    AppCatalogCache.Fingerprint fingerprint = new AppCatalogCache.Fingerprint(
                            pkg, componentKey, version, packageInfo.lastUpdateTime);
                    boolean television = televisionPackages.contains(pkg);
                    App app = cache.get(scan, fingerprint);
                    if (app != null && app.television == television) {
                        reusedCount++;
                    } else {
                        Drawable banner = info.activityInfo.loadBanner(pm);
                        if (banner == null) banner = info.activityInfo.applicationInfo.loadBanner(pm);
                        Drawable icon = info.loadIcon(pm);
                        if (icon == null) icon = pm.getDefaultActivityIcon();
                        CharSequence loadedLabel = info.loadLabel(pm);
                        String label = loadedLabel == null ? pkg : loadedLabel.toString();
                        if (label.isEmpty()) label = pkg;
                        app = new App(label, component, television,
                                artworkState(banner, resources, 512), artworkState(icon, resources, 192));
                        if (!cache.put(scan, fingerprint, app)) return;
                        loadedCount++;
                    }
                    installedKeys.add(componentKey);
                    apps.add(app);
                }
            } finally {
                Trace.endSection();
            }
            // PackageManager result ordering must not produce spurious revisions and UI rebinds.
            Collections.sort(apps, Comparator.comparing((App app) -> app.pkg)
                    .thenComparing(app -> app.component.flattenToString()));
            Snapshot result;
            synchronized (this) {
                if (!isCurrent(generation, configurationKey) || !cache.finishScan(scan, installedKeys)) return;
                Snapshot previous = latest;
                boolean unchanged = previous != null && previous.configurationKey.equals(configurationKey)
                        && sameRecords(previous.apps, apps);
                List<App> records = unchanged ? previous.apps
                        : Collections.unmodifiableList(new ArrayList<>(apps));
                long revision = unchanged ? previous.revision : ++nextRevision;
                result = new Snapshot(revision, configurationKey, records, loadedCount, reusedCount);
                latest = result; // Publish only a complete, current scan.
            }
            if (debug) {
                Log.d(TAG, "refresh revision=" + result.revision + " apps=" + apps.size()
                        + " loaded=" + loadedCount + " reused=" + reusedCount
                        + " discoveryMs=" + (discovered - started)
                        + " loadMs=" + (SystemClock.uptimeMillis() - discovered));
            }
            mainHandler.post(() -> {
                synchronized (AppCatalog.this) {
                    if (isCurrent(generation, configurationKey)) callback.onLoaded(result);
                }
            });
        } catch (RuntimeException error) {
            // Never publish a partial catalog or replace the last complete one on a resource/query failure.
            if (debug) Log.d(TAG, "Refresh failed; keeping previous catalog", error);
        } finally {
            Trace.endSection();
        }
    }

    private void addResolved(List<ResolveInfo> candidates, Map<String, ResolveInfo> resolved,
                             Set<String> televisionPackages) {
        Objects.requireNonNull(candidates, "PackageManager result");
        List<ResolveInfo> valid = new ArrayList<>();
        for (ResolveInfo candidate : candidates) {
            if (candidate.activityInfo == null || !candidate.activityInfo.exported
                    || candidate.activityInfo.packageName.equals(applicationContext.getPackageName())) continue;
            valid.add(candidate);
        }
        Collections.sort(valid, BY_COMPONENT);
        for (ResolveInfo candidate : valid) {
            String pkg = candidate.activityInfo.packageName;
            if (!resolved.containsKey(pkg)) resolved.put(pkg, candidate);
            if (televisionPackages != null) televisionPackages.add(pkg);
        }
    }

    private static boolean sameRecords(List<App> previous, List<App> current) {
        if (previous.size() != current.size()) return false;
        for (int index = 0; index < previous.size(); index++) {
            if (previous.get(index) != current.get(index)) return false;
        }
        return true;
    }

    /** A drawable without cloneable state is rendered once on the worker, never shared with a view. */
    private static Drawable.ConstantState artworkState(Drawable drawable, Resources resources, int maxEdge) {
        if (drawable == null) return null;
        Drawable.ConstantState state = drawable.getConstantState();
        if (state != null) return state;
        int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : maxEdge;
        int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : maxEdge;
        float scale = Math.min(1f, (float) maxEdge / Math.max(width, height));
        width = Math.max(1, Math.round(width * scale));
        height = Math.max(1, Math.round(height * scale));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(resources.getDisplayMetrics().densityDpi);
        Rect originalBounds = new Rect(drawable.getBounds());
        try {
            drawable.setBounds(0, 0, width, height);
            drawable.draw(new Canvas(bitmap));
            return new BitmapDrawable(resources, bitmap).getConstantState();
        } catch (RuntimeException error) {
            bitmap.recycle();
            throw error;
        } finally {
            drawable.setBounds(originalBounds);
        }
    }

    /** Resource qualifiers only; Configuration.seq changes alone do not invalidate loaded artwork. */
    private static String configurationKey(Configuration configuration) {
        String key = configuration.getLocales().toLanguageTags() + '|' + configuration.densityDpi
                + '|' + configuration.uiMode + '|' + configuration.orientation
                + '|' + configuration.screenLayout + '|' + configuration.colorMode
                + '|' + configuration.screenWidthDp + '|' + configuration.screenHeightDp
                + '|' + configuration.smallestScreenWidthDp + '|' + configuration.fontScale
                + '|' + configuration.mcc + '|' + configuration.mnc
                + '|' + configuration.touchscreen + '|' + configuration.keyboard
                + '|' + configuration.keyboardHidden + '|' + configuration.hardKeyboardHidden
                + '|' + configuration.navigation + '|' + configuration.navigationHidden;
        if (Build.VERSION.SDK_INT >= 31) key += '|' + Integer.toString(configuration.fontWeightAdjustment);
        if (Build.VERSION.SDK_INT >= 34) key += '|' + Integer.toString(configuration.getGrammaticalGender());
        return key;
    }
}
