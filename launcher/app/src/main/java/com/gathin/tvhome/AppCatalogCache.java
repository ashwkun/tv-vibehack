package com.gathin.tvhome;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** In-memory loaded app records, reused only while package and configuration identity match. */
final class AppCatalogCache<V> {
    static final class Fingerprint {
        final String packageName;
        final String componentKey;
        final long versionCode;
        final long lastUpdateTime;

        Fingerprint(String packageName, String componentKey, long versionCode, long lastUpdateTime) {
            this.packageName = requireIdentity(packageName);
            this.componentKey = requireIdentity(componentKey);
            this.versionCode = versionCode;
            this.lastUpdateTime = lastUpdateTime;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Fingerprint)) return false;
            Fingerprint that = (Fingerprint) other;
            return versionCode == that.versionCode && lastUpdateTime == that.lastUpdateTime
                    && packageName.equals(that.packageName) && componentKey.equals(that.componentKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, componentKey, versionCode, lastUpdateTime);
        }
    }

    /** Identity token: even A-to-B-to-A configuration changes cannot revive an earlier scan. */
    static final class Scan {
        private Scan() { }
    }

    private static final class Record<V> {
        final Fingerprint fingerprint;
        final V value;

        Record(Fingerprint fingerprint, V value) {
            this.fingerprint = fingerprint;
            this.value = value;
        }
    }

    private final Map<String, Record<V>> records = new HashMap<>();
    private String configurationKey;
    private Scan activeScan;

    /** Starts a scan; unchanged configurations can reuse records from earlier scans. */
    synchronized Scan beginScan(String configurationKey) {
        Objects.requireNonNull(configurationKey, "configurationKey");
        if (!configurationKey.equals(this.configurationKey)) {
            records.clear();
            this.configurationKey = configurationKey;
        }
        activeScan = new Scan();
        return activeScan;
    }

    synchronized boolean isCurrent(Scan scan) {
        return scan != null && scan == activeScan;
    }

    /** Returns null on a cache miss or when this scan has been superseded. */
    synchronized V get(Scan scan, Fingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (!isCurrent(scan)) return null;
        Record<V> record = records.get(fingerprint.componentKey);
        return record != null && record.fingerprint.equals(fingerprint) ? record.value : null;
    }

    /** Does not allow an old loader to repopulate records after invalidation or configuration change. */
    synchronized boolean put(Scan scan, Fingerprint fingerprint, V value) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(value, "value");
        if (!isCurrent(scan)) return false;
        records.put(fingerprint.componentKey, new Record<>(fingerprint, value));
        return true;
    }

    /** Call only after a complete successful scan; removed components no longer retain artwork. */
    synchronized boolean finishScan(Scan scan, Set<String> installedComponentKeys) {
        Objects.requireNonNull(installedComponentKeys, "installedComponentKeys");
        if (!isCurrent(scan)) return false;
        records.keySet().retainAll(installedComponentKeys);
        activeScan = null;
        return true;
    }

    /** Cancels the active scan as well as discarding every component belonging to this package. */
    synchronized void invalidatePackage(String packageName) {
        requireIdentity(packageName);
        activeScan = null;
        Iterator<Record<V>> iterator = records.values().iterator();
        while (iterator.hasNext()) {
            if (packageName.equals(iterator.next().fingerprint.packageName)) iterator.remove();
        }
    }

    private static String requireIdentity(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("An app needs an identity");
        return value;
    }
}
