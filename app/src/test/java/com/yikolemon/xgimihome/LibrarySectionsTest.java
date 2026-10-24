package com.yikolemon.xgimihome;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class LibrarySectionsTest {
    @Test public void homeMatchesActualOrderNotAlphabeticOrder() {
        assertEquals(Arrays.asList("c", "a", "b"), LibrarySections.home(
                Arrays.asList("a", "b", "c", "other"), Arrays.asList("c", "a", "b")));
    }
    @Test public void missingAndDuplicatePackagesAreRemoved() {
        assertEquals(Arrays.asList("b", "a"), LibrarySections.home(
                Arrays.asList("a", "b"), Arrays.asList("gone", "b", "a", "b")));
    }
    @Test public void othersExcludeHomeWithoutChangingTheirOrder() {
        assertEquals(Arrays.asList("a", "c"), LibrarySections.others(
                Arrays.asList("a", "b", "c", "a"), Collections.singletonList("b")));
    }
    @Test public void emptyAndAllAddedSectionsAreSafe() {
        assertTrue(LibrarySections.home(Collections.emptyList(), Arrays.asList("a", "b")).isEmpty());
        assertTrue(LibrarySections.others(Arrays.asList("a", "b"), Arrays.asList("b", "a")).isEmpty());
    }
    @Test public void verticalNavigationClampsToShortSections() {
        assertEquals(-1, LibrarySections.verticalTarget(3, 0, 6));
        assertEquals(2, LibrarySections.verticalTarget(5, 3, 6));
        assertEquals(1, LibrarySections.verticalTarget(7, 12, 6));
        assertEquals(0, LibrarySections.verticalTarget(0, 1, 6));
    }
}
