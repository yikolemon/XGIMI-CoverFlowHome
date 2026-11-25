package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreensaverRulesTest {
    @Test public void threeUpPressesWithinWindowTriggerOnce() {
        ScreensaverRules.UpSequence sequence = new ScreensaverRules.UpSequence();
        assertFalse(sequence.press(0));
        assertFalse(sequence.press(1000));
        assertTrue(sequence.press(2000));
        assertFalse(sequence.press(2100));
    }

    @Test public void expiredOrResetSequenceDoesNotTrigger() {
        ScreensaverRules.UpSequence sequence = new ScreensaverRules.UpSequence();
        sequence.press(0);
        sequence.press(10);
        assertFalse(sequence.press(2001));
        sequence.reset();
        assertFalse(sequence.press(2010));
        assertFalse(sequence.press(2020));
        assertTrue(sequence.press(2030));
    }

    @Test public void backwardsTimeRestartsUpSequence() {
        ScreensaverRules.UpSequence sequence = new ScreensaverRules.UpSequence();
        sequence.press(100);
        sequence.press(110);
        assertFalse(sequence.press(50));
    }

    @Test public void doubleBackIncludesBoundaryAndResetsAfterExit() {
        ScreensaverRules.DoubleBack back = new ScreensaverRules.DoubleBack();
        assertFalse(back.press(0));
        assertTrue(back.press(600));
        assertFalse(back.press(601));
        assertFalse(back.press(1202));
        assertTrue(back.press(1203));
    }

    @Test public void doubleBackResetAndClockRollbackDoNotExit() {
        ScreensaverRules.DoubleBack back = new ScreensaverRules.DoubleBack();
        back.press(100);
        back.reset();
        assertFalse(back.press(110));
        assertFalse(back.press(50));
    }

    @Test public void idleTimerOnlyRunsWithVisibleHomeAndVideos() {
        assertEquals(-1, ScreensaverRules.idleDelay(0, 100, 10, false, true));
        assertEquals(-1, ScreensaverRules.idleDelay(0, 100, 10, true, false));
        assertEquals(-1, ScreensaverRules.idleDelay(0, 100, 0, true, true));
        assertEquals(-1, ScreensaverRules.idleDelay(0, 100, -1, true, true));
    }

    @Test public void idleTimerRespectsInputAndClampsOverdueTime() {
        assertEquals(599900, ScreensaverRules.idleDelay(0, 100, 10, true, true));
        assertEquals(600000, ScreensaverRules.idleDelay(100, 100, 10, true, true));
        assertEquals(0, ScreensaverRules.idleDelay(0, 600000, 10, true, true));
        assertEquals(0, ScreensaverRules.idleDelay(0, 700000, 10, true, true));
        assertEquals(3600000, ScreensaverRules.idleDelay(200, 100, Integer.MAX_VALUE, true, true));
    }

    @Test public void videoExtensionsRejectHiddenAndPathNames() {
        assertTrue(ScreensaverRules.videoName("睡眠.MP4"));
        assertTrue(ScreensaverRules.videoName("nature.webm"));
        assertFalse(ScreensaverRules.videoName("../a.mp4"));
        assertFalse(ScreensaverRules.videoName("a\\b.mp4"));
        assertFalse(ScreensaverRules.videoName(".hidden.mp4"));
        assertFalse(ScreensaverRules.videoName("a.mp4.exe"));
        assertFalse(ScreensaverRules.videoName(null));
    }

    @Test public void importBudgetIncludesExactBoundaries() {
        assertTrue(ScreensaverRules.withinBudget(ScreensaverRules.MAX_VIDEO_BYTES,
                ScreensaverRules.MAX_BATCH_BYTES, ScreensaverRules.RESERVED_BYTES));
        assertFalse(ScreensaverRules.withinBudget(ScreensaverRules.MAX_VIDEO_BYTES + 1,
                ScreensaverRules.MAX_BATCH_BYTES, ScreensaverRules.RESERVED_BYTES));
        assertFalse(ScreensaverRules.withinBudget(1, ScreensaverRules.MAX_BATCH_BYTES + 1, ScreensaverRules.RESERVED_BYTES));
        assertFalse(ScreensaverRules.withinBudget(1, 1, ScreensaverRules.RESERVED_BYTES - 1));
        assertFalse(ScreensaverRules.withinBudget(0, 0, ScreensaverRules.RESERVED_BYTES));
        assertFalse(ScreensaverRules.withinBudget(10, 9, ScreensaverRules.RESERVED_BYTES));
    }

    @Test public void importedNamesAreBoundedAndCannotEscapeDirectory() {
        assertEquals("a_b_c.mp4", ScreensaverRules.importName("a/b\\c.mp4"));
        assertEquals("sleep_video.mp4", ScreensaverRules.importName("sleep\nvideo.mp4"));
        String longName = new String(new char[180]).replace('\0', 'a') + ".MP4";
        String safe = ScreensaverRules.importName(longName);
        assertTrue(safe.length() <= 100);
        assertTrue(safe.endsWith(".MP4"));
        assertTrue(ScreensaverRules.videoName(safe));
        String chinese = ScreensaverRules.importName(new String(new char[100]).replace('\0', '雨') + ".mp4");
        assertTrue(chinese.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 250);
    }

    @Test(expected = IllegalArgumentException.class) public void missingImportNameIsRejected() {
        ScreensaverRules.importName(null);
    }

    @Test(expected = IllegalArgumentException.class) public void longExtensionlessImportIsRejected() {
        ScreensaverRules.importName(new String(new char[180]).replace('\0', 'a'));
    }
}
