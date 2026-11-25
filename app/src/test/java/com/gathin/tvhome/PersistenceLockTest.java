package com.gathin.tvhome;

import android.content.Context;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertTrue;

/** Guard the shared class-monitor contract without relying on JVM Android file stubs. */
public class PersistenceLockTest {
    @Test public void shortcutReadsAndWritesUseTheSameClassMonitor() throws Exception {
        assertClassMonitor(LibraryApps.class.getDeclaredMethod("load", Context.class));
        assertClassMonitor(LibraryApps.class.getDeclaredMethod("loadRemoved", Context.class));
        assertClassMonitor(LibraryApps.class.getDeclaredMethod("save", Context.class, String.class, boolean.class));
    }

    @Test public void orderReadsAndWritesUseTheSameClassMonitor() throws Exception {
        assertClassMonitor(Order.class.getDeclaredMethod("load", File.class));
        assertClassMonitor(Order.class.getDeclaredMethod("save", File.class, Order.class));
    }

    private static void assertClassMonitor(Method method) {
        assertTrue(method.getName(), Modifier.isStatic(method.getModifiers()));
        assertTrue(method.getName(), Modifier.isSynchronized(method.getModifiers()));
    }
}
