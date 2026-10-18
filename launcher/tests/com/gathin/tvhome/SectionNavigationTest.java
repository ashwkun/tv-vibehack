package com.gathin.tvhome;

public final class SectionNavigationTest {
    private static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError("Expected " + expected + " but got " + actual);
    }

    public static void main(String[] args) {
        SectionNavigation sections = new SectionNavigation(4, 2, 9);
        equal(sections.recentStart + 1, sections.vertical(3, 1));
        equal(sections.gridStart + 1, sections.vertical(sections.recentStart + 1, 1));
        equal(sections.gridStart + 8, sections.vertical(sections.gridStart + 7, 1));
        equal(sections.footer, sections.vertical(sections.gridStart + 8, 1));
        equal(sections.gridStart, sections.horizontal(sections.gridStart, -1));
        equal(-1, sections.vertical(0, -1));
        equal(-1, sections.homeIndex(sections.recentHeader));
        equal(-1, sections.homeIndex(sections.recentStart));
        equal(-1, sections.homeIndex(sections.allAppsHeader));
        equal(-1, sections.homeIndex(sections.footer));
        for (int i = 0; i < 13; i++) equal(i, sections.homeIndex(sections.adapterPosition(i)));
        SectionNavigation empty = new SectionNavigation(4, 0, 0);
        equal(-1, empty.recentHeader);
        equal(-1, empty.recentStart);
        equal(4, empty.allAppsHeader);
        equal(5, empty.footer);
        equal(empty.footer, empty.vertical(3, 1));
        equal(0, empty.vertical(empty.footer, -1));
        if (empty.nearestFocusable(empty.allAppsHeader) == empty.allAppsHeader) {
            throw new AssertionError("Section header became a focus target");
        }
        SectionNavigation noRecent = new SectionNavigation(4, 0, 5);
        equal(4, noRecent.allAppsHeader);
        equal(noRecent.gridStart + 3, noRecent.vertical(3, 1));
        SectionNavigation fullRecent = new SectionNavigation(4, 4, 5);
        equal(4, fullRecent.recentHeader);
        equal(9, fullRecent.allAppsHeader);
        equal(fullRecent.recentStart + 3, fullRecent.vertical(3, 1));
        for (int i = 0; i < 9; i++) equal(i, fullRecent.homeIndex(fullRecent.adapterPosition(i)));
        System.out.println("SectionNavigationTest passed");
    }
}
