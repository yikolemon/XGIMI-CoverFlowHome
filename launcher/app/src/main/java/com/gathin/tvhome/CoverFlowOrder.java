package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Package order for the carousel, separate from classic grid component ordering. */
final class CoverFlowOrder {
    private CoverFlowOrder() { }

    static List<String> arrange(List<String> available, List<String> saved) {
        Set<String> remaining = new LinkedHashSet<>(available);
        List<String> result = new ArrayList<>();
        for (String pkg : saved) if (remaining.remove(pkg)) result.add(pkg);
        result.addAll(remaining);
        return result;
    }

    static List<String> move(List<String> current, String pkg, int direction) {
        List<String> result = new ArrayList<>(current);
        int index = result.indexOf(pkg);
        if (index < 0 || direction == 0) return result;
        int target = index + Integer.signum(direction);
        if (target >= 0 && target < result.size()) Collections.swap(result, index, target);
        return result;
    }
}
