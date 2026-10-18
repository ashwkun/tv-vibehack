package com.gathin.tvhome;

import java.util.HashMap;
import java.util.Map;

/** Collision-free identity registry for one adapter's lifetime, including removed/reinstalled apps. */
final class StableItemIds {
    private final Map<String, Long> ids = new HashMap<>();
    private long nextId = 0L;

    long idFor(String key) {
        if (key == null) throw new IllegalArgumentException("An item needs a non-null identity");
        Long existing = ids.get(key);
        if (existing != null) return existing;
        long id = nextId++;
        ids.put(key, id);
        return id;
    }
}
