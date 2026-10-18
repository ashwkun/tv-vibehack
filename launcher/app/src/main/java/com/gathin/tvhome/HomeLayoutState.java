package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** User intent, independent of the currently installed packages or Android views. */
final class HomeLayoutState {
    private final List<String> dock;
    private final List<String> grid;
    private final Set<String> hidden;

    HomeLayoutState(int slots, List<String> dock, List<String> grid, Collection<String> hidden) {
        this.dock = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < slots; i++) {
            String pkg = i < dock.size() && dock.get(i) != null ? dock.get(i) : "";
            this.dock.add(pkg.isEmpty() || seen.add(pkg) ? pkg : "");
        }
        this.grid = new ArrayList<>(new LinkedHashSet<>(grid));
        this.hidden = new HashSet<>(hidden);
    }

    HomeLayoutState copy() {
        return new HomeLayoutState(dock.size(), dock, grid, hidden);
    }

    List<String> dock() { return new ArrayList<>(dock); }
    List<String> grid() { return new ArrayList<>(grid); }
    Set<String> hidden() { return new HashSet<>(hidden); }

    void reconcileDefaultApps(List<String> packages) {
        for (String pkg : packages) if (!hidden.contains(pkg) && !grid.contains(pkg)) grid.add(pkg);
    }

    List<String> visibleGrid(Set<String> available) {
        List<String> result = new ArrayList<>();
        for (String pkg : grid) {
            if (available.contains(pkg) && !hidden.contains(pkg) && !dock.contains(pkg)) result.add(pkg);
        }
        return result;
    }

    boolean isOnHome(String pkg) {
        return !hidden.contains(pkg) && (dock.contains(pkg) || grid.contains(pkg));
    }

    void addToHome(String pkg) {
        hidden.remove(pkg);
        if (!grid.contains(pkg)) grid.add(pkg);
    }

    void setDock(int slot, String pkg) {
        if (slot < 0 || slot >= dock.size()) return;
        if (pkg == null) pkg = "";
        if (!pkg.isEmpty()) {
            addToHome(pkg);
            for (int i = 0; i < dock.size(); i++) if (pkg.equals(dock.get(i))) dock.set(i, "");
        }
        dock.set(slot, pkg);
    }

    void removeFromHome(String pkg) {
        hidden.add(pkg);
        for (int i = 0; i < dock.size(); i++) if (pkg.equals(dock.get(i))) dock.set(i, "");
    }

    /** Move in the visible layout without dropping unavailable pins or hidden grid entries. */
    void move(int from, int to, List<String> visibleGrid) {
        int slots = dock.size();
        int count = slots + visibleGrid.size();
        if (from < 0 || to < 0 || from >= count || to >= count || from == to) return;
        String source = from < slots ? dock.get(from) : visibleGrid.get(from - slots);
        if (source.isEmpty()) return;
        String target = to < slots ? dock.get(to) : visibleGrid.get(to - slots);
        if (from < slots && to < slots) {
            dock.set(to, source);
            dock.set(from, target);
        } else if (from >= slots && to >= slots) {
            grid.remove(source);
            int targetIndex = grid.indexOf(target);
            grid.add(from < to ? targetIndex + 1 : targetIndex, source);
        } else {
            int slot = from < slots ? from : to;
            String gridApp = from < slots ? target : source;
            String oldPin = dock.get(slot);
            dock.set(slot, gridApp);
            if (!oldPin.isEmpty()) {
                grid.remove(oldPin);
                int gridIndex = grid.indexOf(gridApp);
                grid.add(Math.max(0, gridIndex), oldPin);
            }
        }
    }

    static String emptySlotKey(int slot) { return "empty-dock:" + slot; }
    static String unavailablePinKey(String pkg) { return "unavailable-dock:" + pkg; }
    static String libraryKey() { return "footer:app-library"; }
}
