package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView btnWifi;
    private TextView networkStatus;
    private View btnAllApps;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    private int lastFocused = 0;
    private String lastFocusedKey;
    private String lastDockKey;
    private SharedPreferences preferences;
    private boolean appsExpanded;
    private boolean resumed;
    private boolean restoringFocus;
    private boolean packageReceiverRegistered;
    private int refreshGeneration;
    private int focusGeneration;
    private int heroGeneration;
    private RecyclerView.OnScrollListener pendingFocusListener;
    private PopupWindow inputsPopup;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network defaultNetwork;
    private NetworkCapabilities defaultNetworkCapabilities;
    private float density;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // An update removes then immediately replaces the same package. Refresh after replacement.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            handler.removeCallbacks(packageRefresh);
            if (resumed) handler.postDelayed(packageRefresh, 150L);
        }
    };
    private final Runnable packageRefresh = () -> {
        if (resumed) refreshApps(false);
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();
        preferences = getSharedPreferences("home_state", MODE_PRIVATE);
        lastFocusedKey = preferences.getString("focused_component", null);
        lastDockKey = preferences.getString("dock_component", null);

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        networkStatus = findViewById(R.id.networkStatus);
        btnAllApps = findViewById(R.id.btnAllApps);
        btnWifi.setText(R.string.network);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = Math.round(164 * density);
            RecyclerView.ViewHolder dock = grid.findViewHolderForAdapterPosition(0);
            if (dock != null && grid.getLayoutManager() != null) {
                dockRow = grid.getLayoutManager().getDecoratedMeasuredHeight(dock.itemView);
            }
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkSettings());
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        btnAllApps.setOnClickListener(v -> expandApps());
        btnAllApps.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                focusDock();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                expandApps();
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusDock();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) {
                hideGlass();
                if (newF != null) cancelPendingFocus();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        cancelHeroAnimations();
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        refreshApps(true);
        updateGreeting();
        startNetworkMonitoring();
        registerPackageReceiver();
        handler.postDelayed(minuteTick, MINUTE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        refreshGeneration++;
        restoringFocus = false;
        cancelPendingFocus();
        cancelHeroAnimations();
        if (inputsPopup != null) inputsPopup.dismiss();
        stopNetworkMonitoring();
        if (packageReceiverRegistered) {
            unregisterReceiver(packageReceiver);
            packageReceiverRegistered = false;
        }
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(packageRefresh);
    }

    @Override
    public void onBackPressed() {
        if (appsExpanded) focusDock();
    }

    private void registerPackageReceiver() {
        if (packageReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }
        packageReceiverRegistered = true;
    }

    private void refreshApps(boolean animateEntrance) {
        View focused = getCurrentFocus();
        boolean restoreTile = focused == null || focused.getId() == R.id.card;
        String selectedKey = lastFocusedKey;
        if (!restoringFocus && focused != null && focused.getId() == R.id.card) {
            RecyclerView.ViewHolder holder = grid.findContainingViewHolder(focused);
            if (holder != null) {
                String key = adapter.keyAt(holder.getBindingAdapterPosition());
                if (key != null) selectedKey = key;
            }
        }
        final String keyToRestore = selectedKey;
        final int generation = ++refreshGeneration;
        cancelPendingFocus();
        restoringFocus = true;
        adapter.reload();
        if (animateEntrance) grid.scheduleLayoutAnimation();
        updateAllAppsVisibility();
        grid.post(() -> {
            if (!resumed || generation != refreshGeneration) return;
            restoringFocus = false;
            if (restoreTile) {
                int position = adapter.positionOfKey(keyToRestore);
                focusTile(position != RecyclerView.NO_POSITION ? position : lastFocused);
            }
        });
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        if (restoringFocus) return;
        lastFocused = position;
        String key = adapter.keyAt(position);
        if (key != null) {
            SharedPreferences.Editor editor = null;
            if (!key.equals(lastFocusedKey)) {
                lastFocusedKey = key;
                editor = preferences.edit().putString("focused_component", key);
            }
            if (position < COLUMNS && !key.equals(lastDockKey)) {
                lastDockKey = key;
                if (editor == null) editor = preferences.edit();
                editor.putString("dock_component", key);
            }
            if (editor != null) editor.apply();
        }
        appsExpanded = position >= COLUMNS;
        updateAllAppsVisibility();
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
        cancelHeroAnimations();
        final int generation = heroGeneration;

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                if (generation != heroGeneration || !heroShown) return;
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    if (generation != heroGeneration || !heroShown) return;
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            } else {
                ambient.animate().alpha(1f).setDuration(200).start();
            }
        } else {
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.setAlpha(1f);
            setAmbient(color);
            ambient.setAlpha(1f);
        }
    }

    private void cancelHeroAnimations() {
        heroGeneration++;
        heroName.animate().cancel();
        ambient.animate().cancel();
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        cancelHeroAnimations();
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        cancelPendingFocus();
        grid.stopScroll();
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        final String key = adapter.keyAt(pos);
        final int generation = focusGeneration;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            focusCard(vh.itemView.findViewById(R.id.card), pos);
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        pendingFocusListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                completePendingFocus(generation, key);
            }
        };
        grid.addOnScrollListener(pendingFocusListener);
        grid.smoothScrollToPosition(pos);
        // Handles a target laid out without entering a scrolling state.
        grid.post(() -> {
            if (grid.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) completePendingFocus(generation, key);
        });
    }

    private void completePendingFocus(int generation, String key) {
        if (!resumed || generation != focusGeneration || pendingFocusListener == null) return;
        int position = adapter.positionOfKey(key);
        if (position == RecyclerView.NO_POSITION) {
            cancelPendingFocus();
            return;
        }
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder == null) return;
        cancelPendingFocus();
        focusCard(holder.itemView.findViewById(R.id.card), position);
    }

    private void focusCard(View card, int position) {
        boolean alreadyFocused = card.hasFocus();
        boolean focused = card.requestFocus();
        if (!focused) focused = card.requestFocusFromTouch();
        if (focused && alreadyFocused) onTileFocused(position, card);
    }

    private void cancelPendingFocus() {
        focusGeneration++;
        if (pendingFocusListener != null) {
            grid.removeOnScrollListener(pendingFocusListener);
            pendingFocusListener = null;
        }
    }

    private void focusDock() {
        int position = adapter.positionOfKey(lastDockKey);
        focusTile(position >= 0 && position < COLUMNS ? position : 0);
    }

    private void expandApps() {
        if (adapter.getItemCount() > COLUMNS) focusTile(COLUMNS);
    }

    private void updateAllAppsVisibility() {
        btnAllApps.setVisibility(!appsExpanded && adapter.getItemCount() > COLUMNS ? View.VISIBLE : View.INVISIBLE);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    private void startNetworkMonitoring() {
        if (networkCallback != null) return;
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        defaultNetwork = null;
        defaultNetworkCapabilities = null;
        if (connectivityManager == null) {
            updateNetworkStatus();
            return;
        }
        networkStatus.setText(R.string.network_connecting);
        btnWifi.setContentDescription(getString(R.string.network_status_description,
                getString(R.string.network_connecting)));
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                if (!resumed || networkCallback != this) return;
                defaultNetwork = network;
                defaultNetworkCapabilities = null;
                // Capabilities arrive in their own callback; querying here races network creation.
                updateNetworkStatus();
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
                if (!resumed || networkCallback != this || !network.equals(defaultNetwork)) return;
                defaultNetworkCapabilities = capabilities;
                updateNetworkStatus();
            }

            @Override
            public void onLost(@NonNull Network network) {
                // A late loss from the former default must not clear its replacement.
                if (!resumed || networkCallback != this || !network.equals(defaultNetwork)) return;
                defaultNetwork = null;
                defaultNetworkCapabilities = null;
                updateNetworkStatus();
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback, handler);
            // Establish the disconnected state too: no callback is promised when no network exists.
            Network initial = connectivityManager.getActiveNetwork();
            defaultNetwork = initial;
            defaultNetworkCapabilities = initial == null ? null : connectivityManager.getNetworkCapabilities(initial);
            updateNetworkStatus();
        } catch (SecurityException | IllegalArgumentException e) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
                // Registration itself failed.
            }
            networkCallback = null;
            updateNetworkStatus();
        }
    }

    private void stopNetworkMonitoring() {
        if (networkCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered after a failed registration.
            }
        }
        networkCallback = null;
        defaultNetwork = null;
        defaultNetworkCapabilities = null;
    }

    private void updateNetworkStatus() {
        int label;
        NetworkCapabilities capabilities = defaultNetworkCapabilities;
        if (defaultNetwork == null) label = R.string.network_disconnected;
        else if (capabilities == null) label = R.string.network_connecting;
        else if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            label = R.string.network_captive_portal;
        } else if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            label = R.string.network_no_internet;
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            label = R.string.network_connected_ethernet;
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            label = R.string.network_connected_wifi;
        } else label = R.string.network_connected;
        String status = getString(label);
        networkStatus.setText(status);
        btnWifi.setContentDescription(getString(R.string.network_status_description, status));
        if (Build.VERSION.SDK_INT >= 30) btnWifi.setStateDescription(status);
    }

    private void openNetworkSettings() {
        Intent networkSettings = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        if (networkSettings.resolveActivity(getPackageManager()) == null) {
            networkSettings = new Intent(Settings.ACTION_WIFI_SETTINGS);
        }
        open(networkSettings);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.action_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        if (inputsPopup != null) inputsPopup.dismiss();
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        final PopupWindow popup = new PopupWindow(panel, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setClippingEnabled(true);
        popup.setOnDismissListener(() -> {
            if (inputsPopup == popup) inputsPopup = null;
        });

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) {
                    label = getString(R.string.input_hdmi_label, label);
                }
                final String id = info.getId();
                int state = tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        Rect visibleBounds = new Rect();
        root.getWindowVisibleDisplayFrame(visibleBounds);
        if (visibleBounds.isEmpty()) visibleBounds.set(0, 0, root.getWidth(), root.getHeight());
        int margin = Math.round(16 * density);
        int maxWidth = Math.max(1, visibleBounds.width() - 2 * margin);
        panel.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = Math.min(maxWidth, Math.max(anchor.getWidth(), panel.getMeasuredWidth()));
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] anchorLocation = new int[2];
        anchor.getLocationOnScreen(anchorLocation);
        int x = Math.max(visibleBounds.left + margin,
                Math.min(anchorLocation[0], visibleBounds.right - margin - width));
        int y = anchorLocation[1] + anchor.getHeight() + Math.round(10 * density);
        int availableBelow = Math.max(1, visibleBounds.bottom - margin - y);
        int availableAbove = Math.max(1, anchorLocation[1] - margin - visibleBounds.top - Math.round(10 * density));
        int height;
        if (availableBelow < Math.round(64 * density) && availableAbove > availableBelow) {
            height = Math.min(panel.getMeasuredHeight(), availableAbove);
            y = anchorLocation[1] - Math.round(10 * density) - height;
        } else {
            height = Math.min(panel.getMeasuredHeight(), availableBelow);
        }
        popup.setWidth(width);
        popup.setHeight(height);
        inputsPopup = popup;
        popup.showAtLocation(root, Gravity.TOP | Gravity.LEFT, x, y);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
