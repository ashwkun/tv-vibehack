package com.gathin.tvhome;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public final class HomeLayoutStateTest {
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }

    public static void main(String[] args) {
        HomeLayoutState original = new HomeLayoutState(4, Arrays.asList("netflix", "prime", "youtube", "hotstar"),
                Arrays.asList("a", "b", "c"), Collections.singleton("hidden"));
        HomeLayoutState editing = original.copy();
        editing.move(4, 5, Arrays.asList("a", "b", "c"));
        equal(Arrays.asList("b", "a", "c"), editing.grid());
        equal(Arrays.asList("a", "b", "c"), original.grid()); // Cancel restores snapshot, no mutation.
        HomeLayoutState cross = original.copy();
        HashSet<String> all = new HashSet<>(Arrays.asList("netflix", "prime", "youtube", "hotstar", "a", "b", "c"));
        cross.move(0, 5, cross.visibleGrid(all));
        equal("b", cross.dock().get(0));
        equal(Arrays.asList("a", "netflix", "c"), cross.visibleGrid(all));
        cross.move(5, 0, cross.visibleGrid(all));
        equal(original.dock(), cross.dock());
        equal(Arrays.asList("a", "b", "c"), cross.visibleGrid(all));
        editing.move(5, 1, Arrays.asList("b", "a", "c"));
        equal(Arrays.asList("netflix", "a", "youtube", "hotstar"), editing.dock());
        if (!editing.grid().contains("prime")) throw new AssertionError("Displaced pin disappeared");
        HomeLayoutState persisted = new HomeLayoutState(4, editing.dock(), editing.grid(), editing.hidden());
        equal(editing.dock(), persisted.dock());
        equal(editing.grid(), persisted.grid());
        // A missing pin must survive another shortcut being changed or the app list being reconciled.
        persisted.reconcileDefaultApps(Arrays.asList("new"));
        persisted.setDock(2, "replacement");
        equal("hotstar", persisted.dock().get(3));
        List<String> visible = persisted.visibleGrid(new HashSet<>(Arrays.asList("a", "b", "c", "new")));
        if (visible.contains("hotstar")) throw new AssertionError("Unavailable app rendered as available");
        persisted.removeFromHome("b");
        if (persisted.isOnHome("b")) throw new AssertionError("Removed app remained on Home");
        persisted.addToHome("b");
        if (!persisted.isOnHome("b")) throw new AssertionError("Library restore failed");
        persisted.setDock(0, "");
        equal("", persisted.dock().get(0));
        StableItemIds ids = new StableItemIds();
        long empty0 = ids.idFor(HomeLayoutState.emptySlotKey(0));
        long empty1 = ids.idFor(HomeLayoutState.emptySlotKey(1));
        long footer = ids.idFor(HomeLayoutState.libraryKey());
        long app = ids.idFor("pkg/activity");
        long missing = ids.idFor(HomeLayoutState.unavailablePinKey("pkg"));
        if (empty0 == empty1 || empty0 == footer || footer == app || missing == app || missing == empty0) {
            throw new AssertionError("Item kinds share IDs");
        }
        System.out.println("HomeLayoutStateTest passed");
    }
}
