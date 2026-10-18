package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import java.util.List;

/** Native remote-friendly menus; shortcut edits and system-confirmed uninstall stay distinct. */
final class AppMenu {
    interface Callbacks {
        void onLayoutChanged(String preferredKey);
        void onMoveRequested(int position);
        void onMoveFinished(boolean save);
    }

    private final Activity activity;
    private final AppAdapter adapter;
    private final int dockSize;
    private final Callbacks callbacks;
    private AlertDialog dialog;

    AppMenu(Activity activity, AppAdapter adapter, int dockSize, Callbacks callbacks) {
        this.activity = activity;
        this.adapter = adapter;
        this.dockSize = dockSize;
        this.callbacks = callbacks;
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private void show(String title, String[] labels, java.util.function.IntConsumer action) {
        dismiss();
        dialog = new AlertDialog.Builder(activity, R.style.AppDialog).setTitle(title)
                .setAdapter(new ArrayAdapter<>(activity, R.layout.item_app_action, android.R.id.text1, labels),
                        (unused, which) -> action.accept(which))
                .setNegativeButton(android.R.string.cancel, null).create();
        showDialog();
    }

    private void showDialog() {
        dialog.show();
        if (dialog.getListView() != null) dialog.getListView().setSelector(android.R.color.transparent);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(R.drawable.glass_panel);
            window.setElevation(0f);
            window.setWindowAnimations(0);
            window.getDecorView().setClipToOutline(true);
        }
    }

    void showNetworkOptions(Runnable showName, Runnable networkSettings, Runnable appSettings) {
        show(activity.getString(R.string.network), new String[]{activity.getString(R.string.show_wifi_name),
                activity.getString(R.string.network_settings), activity.getString(R.string.open_app_settings)}, which -> {
            if (which == 0) showName.run();
            else if (which == 1) networkSettings.run();
            else appSettings.run();
        });
    }

    void showWifiNamePermission(Runnable requestPermission) {
        dismiss();
        dialog = new AlertDialog.Builder(activity, R.style.AppDialog)
                .setTitle(R.string.wifi_name_permission_title).setMessage(R.string.wifi_name_permission_message)
                .setPositiveButton(R.string.wifi_name_permission_action, (unused, which) -> requestPermission.run())
                .setNegativeButton(android.R.string.cancel, null).create();
        showDialog();
    }

    void showLocationRequirement(Runnable openLocationSettings) {
        dismiss();
        dialog = new AlertDialog.Builder(activity, R.style.AppDialog)
                .setTitle(R.string.wifi_name_location_title).setMessage(R.string.wifi_name_location_message)
                .setPositiveButton(R.string.open_location_settings, (unused, which) -> openLocationSettings.run())
                .setNegativeButton(android.R.string.cancel, null).create();
        showDialog();
    }

    void showHomeOptions(int position) {
        AppAdapter.Entry entry = adapter.entryAt(position);
        if (entry == null) return;
        if (entry.kind == AppAdapter.Entry.RECENT) {
            int canonical = adapter.positionOfKey(entry.canonicalKey);
            if (canonical >= 0) showHomeOptions(canonical);
            else showLibraryOptions(entry); // Ordinary apps opened from Library may not have a Home shortcut.
            return;
        }
        if (adapter.isMoving()) {
            show(activity.getString(R.string.app_actions), new String[]{activity.getString(R.string.save_move),
                    activity.getString(R.string.cancel_move)}, which -> callbacks.onMoveFinished(which == 0));
        } else if (entry.kind == AppAdapter.Entry.LIBRARY) {
            showLibrary();
        } else if (entry.kind == AppAdapter.Entry.EMPTY) {
            chooseDockApp(position);
        } else if (entry.kind == AppAdapter.Entry.UNAVAILABLE) {
            show(entry.label, new String[]{activity.getString(R.string.replace_dock_app),
                    activity.getString(R.string.remove_from_home), activity.getString(R.string.app_info),
                    activity.getString(R.string.uninstall_app)}, which -> {
                if (which == 0) chooseDockApp(position);
                else if (which == 1) removeFromHome(entry, HomeLayoutState.emptySlotKey(position));
                else if (which == 2) adapter.openAppInfo(entry);
                else requestUninstall(entry);
            });
        } else {
            show(entry.label, new String[]{activity.getString(R.string.move_app),
                    activity.getString(position < dockSize ? R.string.replace_dock_app : R.string.add_to_dock),
                    activity.getString(R.string.remove_from_home), activity.getString(R.string.app_info),
                    activity.getString(R.string.uninstall_app)}, which -> {
                if (which == 0) {
                    int current = adapter.positionOfKey(entry.key);
                    if (current >= 0) callbacks.onMoveRequested(current);
                }
                else if (which == 1) {
                    if (position < dockSize) chooseDockApp(position);
                    else chooseDockSlot(entry);
                } else if (which == 2) removeFromHome(entry,
                        position < dockSize ? HomeLayoutState.emptySlotKey(position) : null);
                else if (which == 3) adapter.openAppInfo(entry);
                else requestUninstall(entry);
            });
        }
    }

    void showLibrary() {
        List<AppAdapter.Entry> apps = adapter.libraryEntries();
        if (apps.isEmpty()) {
            Toast.makeText(activity, R.string.no_apps_found, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppAdapter.Entry app = apps.get(i);
            labels[i] = adapter.isOnHome(app.pkg) ? activity.getString(R.string.app_on_home, app.label) : app.label;
        }
        show(activity.getString(R.string.app_library), labels, which -> showLibraryOptions(apps.get(which)));
    }

    private void showLibraryOptions(AppAdapter.Entry entry) {
        boolean onHome = adapter.isOnHome(entry.pkg);
        show(entry.label, new String[]{activity.getString(R.string.open_app),
                activity.getString(onHome ? R.string.remove_from_home : R.string.add_to_home),
                activity.getString(R.string.add_to_dock), activity.getString(R.string.app_info),
                activity.getString(R.string.uninstall_app)}, which -> {
            if (which == 0) adapter.launch(entry);
            else if (which == 1) {
                if (onHome) removeFromHome(entry, HomeLayoutState.libraryKey());
                else {
                    adapter.addToHome(entry.pkg);
                    callbacks.onLayoutChanged(entry.canonicalKey);
                }
            } else if (which == 2) chooseDockSlot(entry);
            else if (which == 3) adapter.openAppInfo(entry);
            else requestUninstall(entry);
        });
    }

    private void requestUninstall(AppAdapter.Entry entry) {
        try {
            // Android owns confirmation and protected/system-app handling. Never mutate Home here.
            activity.startActivity(new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + entry.pkg)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.action_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    void chooseDockApp(int slot) {
        List<AppAdapter.Entry> apps = adapter.libraryEntries();
        if (apps.isEmpty()) {
            Toast.makeText(activity, R.string.no_apps_found, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).label;
        show(activity.getString(R.string.choose_dock_app), labels, which -> setDock(slot, apps.get(which)));
    }

    private void chooseDockSlot(AppAdapter.Entry entry) {
        String[] labels = new String[dockSize];
        for (int slot = 0; slot < dockSize; slot++) labels[slot] = activity.getString(R.string.dock_slot_with_app,
                activity.getString(R.string.dock_slot, slot + 1), adapter.labelAt(slot));
        show(activity.getString(R.string.replace_in_dock), labels, slot -> setDock(slot, entry));
    }

    private void setDock(int slot, AppAdapter.Entry entry) {
        adapter.setDock(slot, entry.pkg);
        callbacks.onLayoutChanged(entry.canonicalKey);
        Toast.makeText(activity, R.string.dock_updated, Toast.LENGTH_SHORT).show();
    }

    private void removeFromHome(AppAdapter.Entry entry, String preferredKey) {
        adapter.removeFromHome(entry.pkg);
        callbacks.onLayoutChanged(preferredKey);
        Toast.makeText(activity, R.string.removed_from_home, Toast.LENGTH_LONG).show();
    }
}
