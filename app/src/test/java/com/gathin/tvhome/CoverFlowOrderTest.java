package com.gathin.tvhome;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class CoverFlowOrderTest {
    @Test public void savedOrderSurvivesNewAndRemovedShortcuts() {
        assertEquals(Arrays.asList("c", "a", "new"), CoverFlowOrder.arrange(
                Arrays.asList("a", "c", "new"), Arrays.asList("c", "removed", "a")));
    }

    @Test public void duplicatesAndStaleSavedKeysDoNotCreateTiles() {
        assertEquals(Arrays.asList("b", "a"), CoverFlowOrder.arrange(
                Arrays.asList("a", "b", "a"), Arrays.asList("b", "b", "missing")));
    }

    @Test public void adjacentMovesDoNotMutateOriginalOrder() {
        List<String> original = Arrays.asList("a", "b", "c");
        assertEquals(Arrays.asList("b", "a", "c"), CoverFlowOrder.move(original, "b", -1));
        assertEquals(Arrays.asList("a", "c", "b"), CoverFlowOrder.move(original, "b", 1));
        assertEquals(Arrays.asList("a", "b", "c"), original);
    }

    @Test public void boundariesUnknownAppsAndEmptyListsAreSafe() {
        List<String> original = Arrays.asList("a", "b");
        assertEquals(original, CoverFlowOrder.move(original, "a", -1));
        assertEquals(original, CoverFlowOrder.move(original, "b", 1));
        assertEquals(original, CoverFlowOrder.move(original, "missing", 1));
        assertEquals(original, CoverFlowOrder.move(original, "b", 0));
        assertTrue(CoverFlowOrder.move(Collections.emptyList(), "a", 1).isEmpty());
    }
}
