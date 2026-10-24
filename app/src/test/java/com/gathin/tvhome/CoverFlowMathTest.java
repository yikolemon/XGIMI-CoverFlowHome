package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class CoverFlowMathTest {
    @Test public void selectionWrapsInBothDirections() {
        assertEquals(3, CoverFlowMath.index(-1, 4));
        assertEquals(0, CoverFlowMath.index(4, 4));
        assertEquals(0, CoverFlowMath.index(0, 0));
    }

    @Test public void neighboursRemainOnBothSidesOfFirstApp() {
        assertEquals(-1f, CoverFlowMath.distance(3, 0, 4), 0.001f);
        assertEquals(1f, CoverFlowMath.distance(1, 0, 4), 0.001f);
        assertEquals(0f, CoverFlowMath.distance(0, 4, 4), 0.001f);
    }

    @Test public void wrapAnimationKeepsSelectedCardContinuous() {
        assertEquals(-0.5f, CoverFlowMath.distance(3, -0.5f, 4), 0.001f);
        assertEquals(0.5f, CoverFlowMath.distance(0, -0.5f, 4), 0.001f);
        assertEquals(0f, CoverFlowMath.distance(0, 7, 1), 0.001f);
    }

    @Test public void centreIsFrontFacingAndLargest() {
        assertEquals(0f, CoverFlowMath.rotation(0), 0.001f);
        assertEquals(1f, CoverFlowMath.scale(0), 0.001f);
        assertTrue(CoverFlowMath.rotation(-1) > 0);
        assertTrue(CoverFlowMath.rotation(1) < 0);
        assertTrue(CoverFlowMath.scale(1) < CoverFlowMath.scale(0));
        assertTrue(CoverFlowMath.scale(2) < CoverFlowMath.scale(1));
    }

    @Test public void offsetsAreSymmetricAndBounded() {
        assertEquals(-CoverFlowMath.offset(1, 200), CoverFlowMath.offset(-1, 200), 0.001f);
        assertEquals(0f, CoverFlowMath.offset(0, 200), 0.001f);
        assertTrue(CoverFlowMath.scale(100) > 0);
        assertEquals(16f, Math.abs(CoverFlowMath.rotation(100)), 0.001f);
    }

    @Test public void fourFavouritesFillFiveSymmetricSlotsWithoutAddingAnotherApp() {
        int[] expected = {2, 3, 0, 1, 2};
        for (int offset = -2; offset <= 2; offset++)
            assertEquals(expected[offset + 2], CoverFlowMath.index(offset, 4));
    }

    @Test public void symmetryWorksForTwoAndManyApps() {
        for (int count : new int[]{2, 3, 4, 5, 128}) {
            for (int offset = 1; offset <= 2; offset++) {
                assertEquals(-CoverFlowMath.offset(offset, 200),
                        CoverFlowMath.offset(-offset, 200), 0.001f);
                assertTrue(CoverFlowMath.index(-offset, count) >= 0);
                assertTrue(CoverFlowMath.index(offset, count) < count);
            }
        }
    }
}
