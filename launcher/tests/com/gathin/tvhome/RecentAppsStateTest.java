package com.gathin.tvhome;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

public final class RecentAppsStateTest {
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }

    public static void main(String[] args) {
        RecentAppsState recent = new RecentAppsState(Arrays.asList("a", "b", "a", "", "missing", "hidden", "dock"));
        recent.record("b");
        equal(Arrays.asList("b", "a", "missing", "hidden", "dock"), recent.snapshot());
        equal(Arrays.asList("b", "a"), recent.visible(new HashSet<>(Arrays.asList("a", "b", "hidden", "dock")),
                Collections.singleton("hidden"), Collections.singleton("dock"), 4));
        recent.record("dock"); // Saved successful dock opens still do not render in the Recent row.
        equal(Arrays.asList("b", "a"), recent.visible(new HashSet<>(Arrays.asList("a", "b", "hidden", "dock")),
                Collections.singleton("hidden"), Collections.singleton("dock"), 4));
        for (int i = 0; i < 30; i++) recent.record("app" + i);
        if (recent.snapshot().size() != 20) throw new AssertionError("Saved history is unbounded");
        RecentAppsState restored = new RecentAppsState(recent.snapshot());
        equal(recent.snapshot(), restored.snapshot());
        equal(Arrays.asList("app29", "app28", "app27", "app26"), restored.visible(
                new HashSet<>(restored.snapshot()), Collections.emptySet(), Collections.emptySet(), 4));
        StableItemIds ids = new StableItemIds();
        if (ids.idFor("recent:pkg/activity") == ids.idFor("pkg/activity")) {
            throw new AssertionError("Recent and canonical app share an ID");
        }
        System.out.println("RecentAppsStateTest passed");
    }
}
