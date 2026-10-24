package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Stable section membership: the library must show the same order as Home, not alphabetise it. */
final class LibrarySections {
    private LibrarySections() { }

    static List<String> home(Collection<String> installed, List<String> order) {
        Set<String> available = new HashSet<>(installed);
        Set<String> result = new LinkedHashSet<>();
        for (String pkg : order) if (available.contains(pkg)) result.add(pkg);
        return new ArrayList<>(result);
    }

    static List<String> others(List<String> installedOrder, List<String> home) {
        Set<String> excluded = new HashSet<>(home);
        Set<String> result = new LinkedHashSet<>();
        for (String pkg : installedOrder) if (!excluded.contains(pkg)) result.add(pkg);
        return new ArrayList<>(result);
    }

    static int verticalTarget(int position, int count, int columns) {
        if (count <= 0) return -1;
        return Math.min(Math.max(0, position) % Math.max(1, columns), count - 1);
    }
}
