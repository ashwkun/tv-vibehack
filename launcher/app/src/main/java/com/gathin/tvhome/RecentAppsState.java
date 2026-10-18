package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Launcher-local successful opens. No usage-access permission or background observation. */
final class RecentAppsState {
    private static final int MAX_SAVED = 20;
    private final List<String> packages = new ArrayList<>();

    RecentAppsState(List<String> saved) {
        for (String pkg : saved) {
            if (pkg != null && !pkg.isEmpty() && !packages.contains(pkg)) packages.add(pkg);
            if (packages.size() == MAX_SAVED) break;
        }
    }

    void record(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        packages.remove(pkg);
        packages.add(0, pkg);
        if (packages.size() > MAX_SAVED) packages.remove(packages.size() - 1);
    }

    List<String> snapshot() { return new ArrayList<>(packages); }

    List<String> visible(Set<String> available, Set<String> hidden, Set<String> dock, int limit) {
        List<String> result = new ArrayList<>();
        if (limit <= 0) return result;
        for (String pkg : packages) {
            if (available.contains(pkg) && !hidden.contains(pkg) && !dock.contains(pkg)) result.add(pkg);
            if (result.size() >= limit) break;
        }
        return result;
    }
}
