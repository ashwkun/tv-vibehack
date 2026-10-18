package com.gathin.tvhome;

/** Run with javac/java; intentionally independent of Android and Gradle test dependencies. */
public final class StableItemIdsTest {
    public static void main(String[] args) {
        StableItemIds ids = new StableItemIds();
        long first = ids.idFor("Aa");
        long second = ids.idFor("BB"); // Same Java String hash as Aa.
        if (first == second) throw new AssertionError("Hash collisions must not share an item ID");
        if (ids.idFor("Aa") != first) throw new AssertionError("Existing identity changed");
        ids.idFor("another/app");
        if (ids.idFor("BB") != second) throw new AssertionError("Reordering changed an identity");
        try {
            ids.idFor(null);
            throw new AssertionError("Null identities must be rejected");
        } catch (IllegalArgumentException expected) {
            // No ID may silently collapse onto an unknown item.
        }
        System.out.println("StableItemIdsTest passed");
    }
}
