package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;

/** Visual rows and canonical Home indices are separate from header/recent adapter positions. */
final class SectionNavigation {
    final int columns, recentCount, gridCount;
    final int recentHeader, recentStart, allAppsHeader, gridStart, footer;
    private final List<List<Integer>> rows = new ArrayList<>();

    SectionNavigation(int columns, int recentCount, int gridCount) {
        this.columns = columns;
        this.recentCount = recentCount;
        this.gridCount = gridCount;
        recentHeader = recentCount > 0 ? columns : -1;
        recentStart = recentCount > 0 ? recentHeader + 1 : -1;
        allAppsHeader = recentCount > 0 ? recentStart + recentCount : columns;
        gridStart = allAppsHeader + 1;
        footer = gridStart + gridCount;
        addRow(0, columns);
        if (recentCount > 0) addRow(recentStart, recentCount);
        for (int i = 0; i < gridCount; i += columns) addRow(gridStart + i, Math.min(columns, gridCount - i));
        addRow(footer, 1);
    }

    private void addRow(int start, int count) {
        List<Integer> row = new ArrayList<>();
        for (int i = 0; i < count; i++) row.add(start + i);
        rows.add(row);
    }

    int homeIndex(int adapterPosition) {
        if (adapterPosition >= 0 && adapterPosition < columns) return adapterPosition;
        if (adapterPosition >= gridStart && adapterPosition < footer) return columns + adapterPosition - gridStart;
        return -1;
    }

    int adapterPosition(int homeIndex) {
        if (homeIndex < 0 || homeIndex >= columns + gridCount) return -1;
        return homeIndex < columns ? homeIndex : gridStart + homeIndex - columns;
    }

    int horizontal(int position, int direction) {
        for (List<Integer> row : rows) {
            int column = row.indexOf(position);
            if (column >= 0) return row.get(Math.max(0, Math.min(column + direction, row.size() - 1)));
        }
        return nearestFocusable(position);
    }

    int vertical(int position, int direction) {
        for (int i = 0; i < rows.size(); i++) {
            int column = rows.get(i).indexOf(position);
            if (column < 0) continue;
            int targetRow = i + direction;
            if (targetRow < 0) return -1; // Dock Up can move into the native header controls.
            if (targetRow >= rows.size()) return position;
            List<Integer> row = rows.get(targetRow);
            return row.get(Math.min(column, row.size() - 1));
        }
        return nearestFocusable(position);
    }

    int nearestFocusable(int position) {
        int closest = 0;
        int distance = Integer.MAX_VALUE;
        for (List<Integer> row : rows) for (int candidate : row) {
            int delta = Math.abs(position - candidate);
            if (delta < distance) { closest = candidate; distance = delta; }
        }
        return closest;
    }
}
