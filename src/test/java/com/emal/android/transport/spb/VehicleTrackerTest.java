package com.emal.android.transport.spb;

import android.os.Handler;
import com.emal.android.transport.spb.portal.PortalClient;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for VehicleTracker lifecycle: start, pause, restart, stop.
 * Covers the fix for the bug where restart() reused a cancelled TimerTask
 * and pause() had a null-pointer risk on timerTask.
 */
public class VehicleTrackerTest {

    private VehicleSyncAdapter mockAdapter;
    private PortalClient mockPortalClient;
    private Handler mockHandler;
    private VehicleTracker tracker;

    @Before
    public void setUp() {
        mockAdapter = mock(VehicleSyncAdapter.class);
        mockPortalClient = mock(PortalClient.class);
        when(mockAdapter.getPortalClient()).thenReturn(mockPortalClient);
        when(mockAdapter.getSyncTime()).thenReturn(30000);
        mockHandler = mock(Handler.class);
        tracker = new VehicleTracker(mockAdapter, mockHandler);
    }

    // ---------------------------------------------------------------
    // 1. First start
    // ---------------------------------------------------------------

    @Test
    public void testFirstStart() {
        tracker.start();

        verify(mockAdapter).setBBox();
        verify(mockPortalClient).reset();

        // Exactly one timer task should be posted with delay=0
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockHandler).postDelayed(captor.capture(), eq(0L));
        assertNotNull("A new TimerTask must be posted", captor.getValue());

        // removeCallbacks should NOT be called on first start (no prior task)
        verify(mockHandler, never()).removeCallbacks(any(Runnable.class));
    }

    // ---------------------------------------------------------------
    // 2. Repeated start (idempotent - no duplicate refresh)
    // ---------------------------------------------------------------

    @Test
    public void testRepeatedStart() {
        tracker.start();

        Runnable firstTask = captureLastPostedRunnable();
        reset(mockHandler);

        tracker.start();

        // Old callback must be removed before posting new one
        verify(mockHandler).removeCallbacks(eq(firstTask));

        Runnable secondTask = captureLastPostedRunnable();
        assertNotNull("A new TimerTask must be posted on repeated start", secondTask);
        assertNotSame("Each start must create a fresh TimerTask", firstTask, secondTask);
    }

    // ---------------------------------------------------------------
    // 3. Pause
    // ---------------------------------------------------------------

    @Test
    public void testPause() {
        tracker.start();
        Runnable task = captureLastPostedRunnable();
        reset(mockHandler);

        tracker.pause();

        // pause must remove the scheduled callback
        verify(mockHandler).removeCallbacks(eq(task));
        verify(mockAdapter).clearOverlay();
    }

    @Test
    public void testPauseBeforeStartNoException() {
        // Calling pause() with no prior start must not throw NPE
        tracker.pause();
        verify(mockAdapter).clearOverlay();
        verify(mockHandler, never()).removeCallbacks(any(Runnable.class));
    }

    @Test
    public void testDoublePauseIdempotent() {
        tracker.start();
        tracker.pause();
        reset(mockHandler);

        // Second pause should be a safe no-op for the timer
        tracker.pause();
        verify(mockHandler, never()).removeCallbacks(any(Runnable.class));
    }

    // ---------------------------------------------------------------
    // 4. Start after pause (the core regression scenario)
    // ---------------------------------------------------------------

    @Test
    public void testStartAfterPause() {
        // start -> pause -> start must keep refreshing
        tracker.start();
        Runnable firstTask = captureLastPostedRunnable();
        tracker.pause();
        reset(mockHandler);

        tracker.start();

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockHandler).postDelayed(captor.capture(), eq(0L));
        Runnable newTask = captor.getValue();
        assertNotNull("A new TimerTask must be created after pause", newTask);
        assertNotSame("Must be a fresh TimerTask, not the cancelled one", firstTask, newTask);
    }

    // ---------------------------------------------------------------
    // 5. Consecutive restart
    // ---------------------------------------------------------------

    @Test
    public void testConsecutiveRestart() {
        tracker.start();
        Runnable t1 = captureLastPostedRunnable();
        reset(mockHandler);

        tracker.restart();
        verify(mockHandler).removeCallbacks(eq(t1));
        Runnable t2 = captureLastPostedRunnable();
        assertNotSame(t1, t2);
        reset(mockHandler);

        tracker.restart();
        verify(mockHandler).removeCallbacks(eq(t2));
        Runnable t3 = captureLastPostedRunnable();
        assertNotSame(t2, t3);
        reset(mockHandler);

        tracker.restart();
        verify(mockHandler).removeCallbacks(eq(t3));
        Runnable t4 = captureLastPostedRunnable();
        assertNotSame(t3, t4);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Captures the last Runnable passed to {@code handler.postDelayed()}.
     * Assumes exactly one postDelayed call since last reset.
     */
    private Runnable captureLastPostedRunnable() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(mockHandler, atLeastOnce()).postDelayed(captor.capture(), anyLong());
        java.util.List<Runnable> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }
}
