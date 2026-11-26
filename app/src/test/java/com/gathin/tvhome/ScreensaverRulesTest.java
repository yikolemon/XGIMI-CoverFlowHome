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

    @Test public void chooserClampsAtBothEndsWithoutWrapping() {
        assertEquals(0, ScreensaverRules.chooserIndex(0, -1, 3));
        assertEquals(2, ScreensaverRules.chooserIndex(2, 1, 3));
        assertEquals(1, ScreensaverRules.chooserIndex(0, 1, 3));
        assertEquals(1, ScreensaverRules.chooserIndex(2, -1, 3));
    }

    @Test public void emptyAndSingleVideoChoosersStayAtZero() {
        assertEquals(0, ScreensaverRules.chooserIndex(0, 1, 0));
        assertEquals(0, ScreensaverRules.chooserIndex(0, -1, 1));
        assertEquals(0, ScreensaverRules.chooserIndex(0, 1, 1));
    }

    @Test public void chooserArithmeticDoesNotOverflow() {
        assertEquals(19, ScreensaverRules.chooserIndex(19, Integer.MAX_VALUE, 20));
        assertEquals(0, ScreensaverRules.chooserIndex(1, Integer.MIN_VALUE, 20));
    }

    @Test public void confirmingPlayingVideoDoesNotRestartButFailuresCanRetry() {
        assertFalse(ScreensaverRules.shouldRestartVideo(1, 1, true));
        assertTrue(ScreensaverRules.shouldRestartVideo(2, 1, true));
        assertTrue(ScreensaverRules.shouldRestartVideo(1, 1, false));
    }

    @Test public void playerVolumeClampsPercent() {
        assertEquals(1f, ScreensaverRules.playerVolume(100), 0.0001f);
        assertEquals(0.4f, ScreensaverRules.playerVolume(40), 0.0001f);
        assertEquals(0f, ScreensaverRules.playerVolume(0), 0.0001f);
        assertEquals(1f, ScreensaverRules.playerVolume(150), 0.0001f);
        assertEquals(0f, ScreensaverRules.playerVolume(-20), 0.0001f);
        assertEquals(90, ScreensaverRules.adjustVolumePercent(100, -10));
        assertEquals(0, ScreensaverRules.adjustVolumePercent(0, -10));
        assertEquals(100, ScreensaverRules.adjustVolumePercent(100, 10));
    }
}
