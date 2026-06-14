package com.emal.android.transport.spb;

import com.emal.android.transport.spb.portal.PortalClient;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@link VehicleTracker} timer lifecycle: every effective (re)start must post a brand
 * new task and drop the previous callback, and pause/restart/start must be repeatable and idempotent
 * without producing duplicate refreshes or NPEs.
 *
 * <p>Runs on a plain JVM by injecting a {@link RecordingScheduler} in place of the Android
 * {@code Handler}; {@code android.util.Log} is shadowed by a test-only no-op (see
 * {@code src/test/java/android/util/Log.java}).
 */
public class VehicleTrackerTest {

    /** Captures scheduling interactions so the timer lifecycle can be asserted without Android. */
    private static final class RecordingScheduler implements VehicleTracker.TimerScheduler {
        final List<Runnable> scheduled = new ArrayList<Runnable>();
        final List<Long> delays = new ArrayList<Long>();
        final List<Runnable> unscheduled = new ArrayList<Runnable>();

        @Override
        public void schedule(Runnable task, long delayMillis) {
            scheduled.add(task);
            delays.add(delayMillis);
        }

        @Override
        public void unschedule(Runnable task) {
            unscheduled.add(task);
        }
    }

    private VehicleSyncAdapter adapter;
    private PortalClient portalClient;
    private RecordingScheduler scheduler;
    private VehicleTracker tracker;

    @Before
    public void setUp() {
        adapter = mock(VehicleSyncAdapter.class);
        portalClient = mock(PortalClient.class);
        when(adapter.getPortalClient()).thenReturn(portalClient);
        when(adapter.getSyncTime()).thenReturn(5000);
        scheduler = new RecordingScheduler();
        tracker = new VehicleTracker(adapter, scheduler);
    }

    @Test
    public void firstStart_schedulesSingleFreshTaskImmediately() {
        tracker.start();

        assertEquals("exactly one task should be scheduled", 1, scheduler.scheduled.size());
        assertNotNull("the scheduled task must not be null", scheduler.scheduled.get(0));
        assertEquals("first refresh should fire immediately", Long.valueOf(0L), scheduler.delays.get(0));
        assertTrue("first start must not remove a non-existent callback", scheduler.unscheduled.isEmpty());
        verify(adapter).setBBox();
        verify(portalClient).reset();
    }

    @Test
    public void repeatStart_usesNewTaskAndRemovesPrevious() {
        tracker.start();
        Runnable first = scheduler.scheduled.get(0);

        tracker.start();

        assertEquals("each start should post a fresh task", 2, scheduler.scheduled.size());
        Runnable second = scheduler.scheduled.get(1);
        assertNotSame("the second start must not reuse the cancelled task", first, second);
        assertEquals("the old callback must be removed exactly once", 1, scheduler.unscheduled.size());
        assertTrue("the previous task must be unscheduled", scheduler.unscheduled.contains(first));
        assertEquals("restart always reschedules immediately", Long.valueOf(0L), scheduler.delays.get(1));
    }

    @Test
    public void pause_removesCallbackAndIsIdempotent() {
        tracker.start();
        Runnable task = scheduler.scheduled.get(0);

        tracker.pause();
        assertTrue("pause must remove the active callback", scheduler.unscheduled.contains(task));
        int unscheduledAfterFirstPause = scheduler.unscheduled.size();

        // Second pause has no active task: must be null-safe and must not remove anything again.
        tracker.pause();
        assertEquals("repeated pause must be idempotent", unscheduledAfterFirstPause, scheduler.unscheduled.size());
    }

    @Test
    public void pauseBeforeStart_isNullSafe() {
        // No task has ever been scheduled; pause must not touch the scheduler nor throw.
        tracker.pause();

        assertTrue(scheduler.unscheduled.isEmpty());
        assertTrue(scheduler.scheduled.isEmpty());
    }

    @Test
    public void startAfterPause_resumesWithFreshTask() {
        tracker.start();
        Runnable beforePause = scheduler.scheduled.get(0);
        tracker.pause();

        tracker.start();

        assertEquals("refresh must resume after pause", 2, scheduler.scheduled.size());
        Runnable afterPause = scheduler.scheduled.get(1);
        assertNotNull(afterPause);
        assertNotSame("resumed refresh must use a new task", beforePause, afterPause);
        assertEquals(Long.valueOf(0L), scheduler.delays.get(1));
    }

    @Test
    public void consecutiveRestarts_eachUsesNewTaskAndRemovesPrevious() {
        tracker.restart();
        tracker.restart();
        tracker.restart();

        assertEquals("each restart should post a fresh task", 3, scheduler.scheduled.size());
        Runnable t1 = scheduler.scheduled.get(0);
        Runnable t2 = scheduler.scheduled.get(1);
        Runnable t3 = scheduler.scheduled.get(2);
        assertNotSame(t1, t2);
        assertNotSame(t2, t3);
        assertNotSame(t1, t3);

        assertEquals("only the two superseded tasks should be unscheduled", 2, scheduler.unscheduled.size());
        assertTrue(scheduler.unscheduled.contains(t1));
        assertTrue(scheduler.unscheduled.contains(t2));
        assertFalse("the live task must remain scheduled", scheduler.unscheduled.contains(t3));
    }
}
