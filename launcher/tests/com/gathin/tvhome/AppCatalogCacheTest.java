package com.gathin.tvhome;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Run with javac/java; intentionally independent of Android and Gradle test dependencies. */
public final class AppCatalogCacheTest {
    public static void main(String[] args) {
        reuseRequiresWholeFingerprint();
        packageInvalidationRejectsInFlightLoader();
        configurationRoundTripCannotReviveOldLoader();
        supersededScanCannotPruneCurrentRecords();
        finishedScanPrunesRemovedComponents();
        System.out.println("AppCatalogCacheTest passed");
    }

    private static void reuseRequiresWholeFingerprint() {
        AppCatalogCache<Object> cache = new AppCatalogCache<>();
        AppCatalogCache.Fingerprint first = fingerprint("video", "video/TV", 1, 10);
        Object artwork = new Object();
        AppCatalogCache.Scan scan = cache.beginScan("en-320dpi");
        check(cache.put(scan, first, artwork), "Current scan should accept a loaded record");
        check(cache.finishScan(scan, keys("video/TV")), "Current scan should finish");
        scan = cache.beginScan("en-320dpi");
        check(cache.get(scan, fingerprint("video", "video/TV", 1, 10)) == artwork,
                "Unchanged app must reuse the exact loaded record");
        check(cache.get(scan, fingerprint("video", "video/TV", 2, 10)) == null,
                "Version changes must miss");
        check(cache.get(scan, fingerprint("video", "video/TV", 1, 11)) == null,
                "Update time changes must miss even when version code is unchanged");
        check(cache.get(scan, fingerprint("video", "video/Other", 1, 10)) == null,
                "Components of the same package must not share a record");
        check(cache.get(scan, fingerprint("other", "video/TV", 1, 10)) == null,
                "Package identity must match too");
    }

    private static void packageInvalidationRejectsInFlightLoader() {
        AppCatalogCache<String> cache = new AppCatalogCache<>();
        AppCatalogCache.Scan scan = cache.beginScan("en");
        AppCatalogCache.Fingerprint one = fingerprint("video", "video/TV", 1, 10);
        AppCatalogCache.Fingerprint two = fingerprint("video", "video/Other", 1, 10);
        AppCatalogCache.Fingerprint other = fingerprint("music", "music/TV", 1, 10);
        cache.put(scan, one, "one");
        cache.put(scan, two, "two");
        cache.put(scan, other, "music");
        cache.invalidatePackage("video");
        check(!cache.isCurrent(scan), "Package change must cancel in-flight work");
        check(!cache.put(scan, one, "stale"), "Old loader must not undo invalidation");
        scan = cache.beginScan("en");
        check(cache.get(scan, one) == null && cache.get(scan, two) == null,
                "Invalidation must discard every component of the package");
        check("music".equals(cache.get(scan, other)), "Unrelated package should remain cached");
    }

    private static void configurationRoundTripCannotReviveOldLoader() {
        AppCatalogCache<String> cache = new AppCatalogCache<>();
        AppCatalogCache.Fingerprint app = fingerprint("video", "video/TV", 1, 10);
        AppCatalogCache.Scan oldEnglish = cache.beginScan("en-320dpi");
        cache.put(oldEnglish, app, "English");
        AppCatalogCache.Scan german = cache.beginScan("de-320dpi");
        check(cache.get(german, app) == null, "Configuration change must discard old labels/artwork");
        cache.put(german, app, "Deutsch");
        AppCatalogCache.Scan newEnglish = cache.beginScan("en-320dpi");
        check(!cache.put(oldEnglish, app, "old English"), "A-B-A must not revive old A token");
        check(!cache.put(german, app, "old German"), "Superseded configuration must not write");
        check(cache.get(newEnglish, app) == null, "Round trip needs a current-configuration record");
        check(cache.put(newEnglish, app, "fresh English"), "Current configuration should accept data");
    }

    private static void supersededScanCannotPruneCurrentRecords() {
        AppCatalogCache<String> cache = new AppCatalogCache<>();
        AppCatalogCache.Fingerprint app = fingerprint("video", "video/TV", 1, 10);
        AppCatalogCache.Scan old = cache.beginScan("en");
        AppCatalogCache.Scan current = cache.beginScan("en");
        cache.put(current, app, "video");
        check(!cache.finishScan(old, Collections.emptySet()), "Old completion must not prune new work");
        check("video".equals(cache.get(current, app)), "New record must survive stale completion");
        check(!cache.put(old, app, "stale"), "Same-configuration superseded scan cannot write");
    }

    private static void finishedScanPrunesRemovedComponents() {
        AppCatalogCache<String> cache = new AppCatalogCache<>();
        AppCatalogCache.Fingerprint kept = fingerprint("video", "video/TV", 1, 10);
        AppCatalogCache.Fingerprint removed = fingerprint("music", "music/TV", 1, 10);
        AppCatalogCache.Scan scan = cache.beginScan("en");
        cache.put(scan, kept, "video");
        cache.put(scan, removed, "music");
        cache.finishScan(scan, keys("video/TV"));
        check(!cache.isCurrent(scan), "Successful completion must close the scan");
        check(!cache.put(scan, removed, "late"), "Finished loader must not resurrect removed component");
        scan = cache.beginScan("en");
        check("video".equals(cache.get(scan, kept)), "Installed app should remain cached");
        check(cache.get(scan, removed) == null, "Removed app must release its record");
        cache.finishScan(scan, Collections.emptySet());
        scan = cache.beginScan("en");
        check(cache.get(scan, kept) == null, "Empty successful catalog must release all records");
    }

    private static AppCatalogCache.Fingerprint fingerprint(String pkg, String component, long version, long time) {
        return new AppCatalogCache.Fingerprint(pkg, component, version, time);
    }

    private static Set<String> keys(String... components) {
        return new HashSet<>(Arrays.asList(components));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
