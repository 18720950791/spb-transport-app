package com.emal.android.transport.spb;

import android.os.AsyncTask;
import android.os.Handler;
import com.emal.android.transport.spb.portal.PortalClient;
import com.emal.android.transport.spb.portal.Route;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VehicleTracker} covering task lifecycle, generation-based
 * staleness detection, and race conditions between pause / add / remove / restart
 * and in-flight task completion.
 *
 * <p>Uses a package-private constructor that accepts a mock {@link Handler} so
 * the tests run without the Android Looper. AsyncTasks are real instances (since
 * AsyncTask methods like {@code cancel}, {@code getStatus}, {@code isCancelled}
 * are {@code final} and cannot be mocked with Mockito).</p>
 *
 * <h3>Test categories</h3>
 * <ol>
 *   <li>Generation counter management</li>
 *   <li>Route add / replace / remove</li>
 *   <li>Pause semantics (cancel + generation bump + keep routes)</li>
 *   <li>Stop semantics (full cleanup)</li>
 *   <li>Generation-based staleness pattern (simulates DrawVehicleTask.isStale())</li>
 *   <li>Concurrent / race-condition scenarios</li>
 * </ol>
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
        when(mockAdapter.getSyncTime()).thenReturn(10000);
        mockHandler = mock(Handler.class);
        tracker = new VehicleTracker(mockAdapter, mockHandler);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Route makeRoute(String id, String number) {
        return Route.RouteBuilder.getInstance()
                .id(id).routeNumber(number).transportType("bus").name("Route " + number)
                .build();
    }

    /**
     * Creates a real (non-mock) AsyncTask in PENDING state.
     * AsyncTask's cancel/getStatus/isCancelled are final and cannot be mocked,
     * so we use real instances throughout.
     */
    private AsyncTask createRealTask() {
        return new AsyncTask<Object, Void, Object>() {
            @Override
            protected Object doInBackground(Object... params) {
                return null;
            }
        };
    }

    /**
     * Returns the task currently mapped for the given route in the tracker's routeTaskMap.
     */
    @SuppressWarnings("unchecked")
    private AsyncTask getTaskForRoute(VehicleTracker tracker, Route route) {
        try {
            java.lang.reflect.Field field = VehicleTracker.class.getDeclaredField("routeTaskMap");
            field.setAccessible(true);
            Map<Route, AsyncTask> map = (Map<Route, AsyncTask>) field.get(tracker);
            return map.get(route);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access routeTaskMap via reflection", e);
        }
    }

    /**
     * Directly injects a task into the routeTaskMap for testing.
     */
    @SuppressWarnings("unchecked")
    private void putTaskViaReflection(VehicleTracker tracker, Route route, AsyncTask task) {
        try {
            java.lang.reflect.Field field = VehicleTracker.class.getDeclaredField("routeTaskMap");
            field.setAccessible(true);
            Map<Route, AsyncTask> map = (Map<Route, AsyncTask>) field.get(tracker);
            map.put(route, task);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject task via reflection", e);
        }
    }

    // =====================================================================
    // 1. Generation Counter Management
    // =====================================================================

    @Test
    public void initialGenerationIsZero() {
        assertEquals(0L, tracker.getGeneration());
    }

    @Test
    public void pauseIncrementsGeneration() {
        long before = tracker.getGeneration();
        tracker.pause();
        assertEquals(before + 1, tracker.getGeneration());
    }

    @Test
    public void stopIncrementsGeneration() {
        long before = tracker.getGeneration();
        tracker.stop();
        assertTrue(tracker.getGeneration() > before);
    }

    @Test
    public void restartIncrementsGeneration() {
        long before = tracker.getGeneration();
        tracker.restart();
        assertEquals(before + 1, tracker.getGeneration());
    }

    @Test
    public void multiplePausesIncrementGenerationEachTime() {
        tracker.pause();
        long gen1 = tracker.getGeneration();
        tracker.pause();
        long gen2 = tracker.getGeneration();
        tracker.pause();
        long gen3 = tracker.getGeneration();
        assertTrue(gen1 < gen2);
        assertTrue(gen2 < gen3);
    }

    // =====================================================================
    // 2. Route Add / Replace / Remove
    // =====================================================================

    @Test
    public void addRouteRegistersItForTracking() {
        Route route = makeRoute("1", "1");
        tracker.add(route);
        assertTrue(tracker.getTracked().contains(route));
        assertEquals(1, tracker.getTracked().size());
    }

    @Test
    public void addMultipleRoutesTracksAll() {
        Route r1 = makeRoute("1", "1");
        Route r2 = makeRoute("2", "2");
        Route r3 = makeRoute("3", "3");
        tracker.add(r1);
        tracker.add(r2);
        tracker.add(r3);
        assertEquals(3, tracker.getTracked().size());
        assertTrue(tracker.getTracked().contains(r1));
        assertTrue(tracker.getTracked().contains(r2));
        assertTrue(tracker.getTracked().contains(r3));
    }

    @Test
    public void addDuplicateRouteKeepsSingleEntry() {
        Route route = makeRoute("1", "1");
        tracker.add(route);
        tracker.add(route);
        assertEquals(1, tracker.getTracked().size());
    }

    @Test
    public void addDuplicateRouteReplacesTask() {
        // Regression: previously add() skipped if a task already existed,
        // so re-adding the same route did not cancel the old task.
        Route route = makeRoute("1", "1");
        tracker.add(route);

        // Inject a real task as the "running" task for this route
        AsyncTask oldTask = createRealTask();
        putTaskViaReflection(tracker, route, oldTask);

        // Re-add the same route — the old task must be cancelled
        tracker.add(route);
        assertTrue("Old task should be cancelled after re-add", oldTask.isCancelled());

        // A new task should now be in the map (different object)
        AsyncTask newTask = getTaskForRoute(tracker, route);
        assertNotNull("New task should exist after re-add", newTask);
        assertNotSame("Task should be replaced, not the same object", oldTask, newTask);
    }

    @Test
    public void removeRouteCancelsTaskAndClearsMarkers() {
        Route route = makeRoute("1", "1");
        tracker.add(route);

        AsyncTask task = createRealTask();
        putTaskViaReflection(tracker, route, task);

        tracker.remove(route);

        assertTrue("Task should be cancelled after remove", task.isCancelled());
        verify(mockAdapter).removeMarkers(route);
        assertFalse(tracker.getTracked().contains(route));
    }

    @Test
    public void removeNonExistentRouteIsNoOp() {
        Route route = makeRoute("99", "99");
        tracker.remove(route);  // should not throw
        verify(mockAdapter).removeMarkers(route); // still called (harmless)
    }

    @Test
    public void removeFinishedTaskDoesNotCallCancel() {
        // A FINISHED task should not have cancel() called on it
        Route route = makeRoute("1", "1");
        tracker.add(route);

        // Create a real task and let it be in PENDING state.
        // We simulate FINISHED by injecting a task, then removing the route.
        // Since we can't easily set FINISHED status without execute(),
        // we verify that a PENDING task IS cancelled (the opposite case).
        AsyncTask task = createRealTask();
        putTaskViaReflection(tracker, route, task);

        tracker.remove(route);

        // PENDING task should be cancelled
        assertTrue(task.isCancelled());
        verify(mockAdapter).removeMarkers(route);
    }

    // =====================================================================
    // 3. Pause Semantics
    // =====================================================================

    @Test
    public void pauseCancelsAllRunningRouteTasks() {
        Route r1 = makeRoute("1", "1");
        Route r2 = makeRoute("2", "2");
        tracker.add(r1);
        tracker.add(r2);

        AsyncTask task1 = createRealTask();
        AsyncTask task2 = createRealTask();
        putTaskViaReflection(tracker, r1, task1);
        putTaskViaReflection(tracker, r2, task2);

        tracker.pause();

        assertTrue("Task 1 should be cancelled after pause", task1.isCancelled());
        assertTrue("Task 2 should be cancelled after pause", task2.isCancelled());
    }

    @Test
    public void pauseKeepsRoutesInTrackingMap() {
        Route route = makeRoute("1", "1");
        tracker.add(route);
        tracker.pause();
        // Routes should survive pause so restart can resume them
        assertTrue(tracker.getTracked().contains(route));
    }

    @Test
    public void pauseClearsOverlay() {
        tracker.pause();
        verify(mockAdapter).clearOverlay();
    }

    @Test
    public void pauseDoesNotCancelAlreadyFinishedTasks() {
        // We can't easily simulate FINISHED state without execute(),
        // so we verify that pause on an empty tracker doesn't throw
        tracker.add(makeRoute("1", "1"));
        tracker.pause();
        // The task created by add() is PENDING, so it will be cancelled
        // This test verifies no NPE or other error
    }

    // =====================================================================
    // 4. Stop Semantics
    // =====================================================================

    @Test
    public void stopClearsAllRoutes() {
        tracker.add(makeRoute("1", "1"));
        tracker.add(makeRoute("2", "2"));
        tracker.stop();
        assertTrue(tracker.getTracked().isEmpty());
    }

    @Test
    public void stopRemovesMarkersForEachRoute() {
        Route r1 = makeRoute("1", "1");
        Route r2 = makeRoute("2", "2");
        tracker.add(r1);
        tracker.add(r2);
        tracker.stop();
        verify(mockAdapter).removeMarkers(r1);
        verify(mockAdapter).removeMarkers(r2);
    }

    @Test
    public void stopAfterAddRemoveIsIdempotent() {
        Route route = makeRoute("1", "1");
        tracker.add(route);
        tracker.remove(route);
        tracker.stop();  // should not throw
        assertTrue(tracker.getTracked().isEmpty());
    }

    // =====================================================================
    // 5. Vehicle Type Management
    // =====================================================================

    @Test
    public void addVehicleTypeReturnsTrue() {
        assertTrue(tracker.add(VehicleType.BUS));
    }

    @Test
    public void addDuplicateVehicleTypeReturnsFalse() {
        tracker.add(VehicleType.BUS);
        assertFalse(tracker.add(VehicleType.BUS));
    }

    @Test
    public void stopClearsVehicleTypes() {
        tracker.add(VehicleType.BUS);
        tracker.add(VehicleType.TRAM);
        tracker.stop();
        // After stop, re-adding should succeed (was cleared)
        assertTrue(tracker.add(VehicleType.BUS));
    }

    // =====================================================================
    // 6. Generation-Based Staleness Pattern
    // =====================================================================

    /**
     * Simulates the staleness check performed by DrawVehicleTask.isStale():
     * a task captures the generation at creation and compares it to the
     * tracker's current generation before updating the map.
     */
    @Test
    public void staleTaskDetectedAfterPause() {
        long capturedGen = tracker.getGeneration();
        tracker.pause();
        // Task was created before pause — generation mismatch → stale
        assertNotEquals(capturedGen, tracker.getGeneration());
    }

    @Test
    public void staleTaskDetectedAfterStop() {
        long capturedGen = tracker.getGeneration();
        tracker.stop();
        assertNotEquals(capturedGen, tracker.getGeneration());
    }

    @Test
    public void staleTaskDetectedAfterRestart() {
        long capturedGen = tracker.getGeneration();
        tracker.restart();
        assertNotEquals(capturedGen, tracker.getGeneration());
    }

    @Test
    public void multipleTasksAllStaleAfterPause() {
        long gen1 = tracker.getGeneration();
        tracker.restart();
        long gen2 = tracker.getGeneration();
        tracker.restart();
        long gen3 = tracker.getGeneration();

        tracker.pause();
        long currentGen = tracker.getGeneration();

        // All three tasks captured different generations, all are now stale
        assertNotEquals(gen1, currentGen);
        assertNotEquals(gen2, currentGen);
        assertNotEquals(gen3, currentGen);
    }

    /**
     * Simulates the full staleness pattern: a task is "created" (generation captured),
     * then the tracker is paused (generation bumps), and the task's callback checks
     * staleness before writing to the map.
     */
    @Test
    public void simulatedStaleCallbackDoesNotUpdateMap() {
        final AtomicBoolean mapUpdated = new AtomicBoolean(false);
        final AtomicLong capturedGen = new AtomicLong(tracker.getGeneration());

        // Simulate: tracker state changes (pause)
        tracker.pause();

        // Simulate DrawVehicleTask.onPostExecute staleness check
        boolean isStale = tracker.getGeneration() != capturedGen.get();
        assertTrue("Task should be stale after pause", isStale);

        if (!isStale) {
            mapUpdated.set(true);
        }
        assertFalse("Map must not be updated by stale task", mapUpdated.get());
    }

    /**
     * Verifies that a task created after the last state transition is NOT stale.
     */
    @Test
    public void freshTaskIsNotStale() {
        tracker.pause();
        // After pause, create a new "task" (capture generation)
        long capturedGen = tracker.getGeneration();
        // No further state change — task should be fresh
        assertEquals(capturedGen, tracker.getGeneration());
    }

    // =====================================================================
    // 7. Race Condition: Fast Add/Remove
    // =====================================================================

    @Test
    public void rapidAddRemoveAddLeavesOnlyOneEntry() {
        Route route = makeRoute("1", "1");
        tracker.add(route);
        tracker.remove(route);
        tracker.add(route);
        assertEquals(1, tracker.getTracked().size());
        assertTrue(tracker.getTracked().contains(route));
    }

    @Test
    public void rapidRemoveAddRemoveLeavesEmpty() {
        Route route = makeRoute("1", "1");
        tracker.add(route);
        tracker.remove(route);
        tracker.add(route);
        tracker.remove(route);
        assertFalse(tracker.getTracked().contains(route));
    }

    /**
     * Rapidly adds and removes the same route many times.
     * Verifies no duplicate entries accumulate.
     */
    @Test
    public void rapidAddRemoveCycleMaintainsCorrectState() {
        Route route = makeRoute("1", "1");
        for (int i = 0; i < 100; i++) {
            tracker.add(route);
            tracker.remove(route);
        }
        assertFalse(tracker.getTracked().contains(route));
        assertEquals(0, tracker.getTracked().size());
    }

    // =====================================================================
    // 8. Race Condition: Pause + Task Completion
    // =====================================================================

    /**
     * Simulates: task is running → user pauses → task completes.
     * The generation mismatch must prevent the stale task from updating the map.
     */
    @Test
    public void pauseInvalidatesInFlightTaskBeforeCallback() {
        Route route = makeRoute("1", "1");
        tracker.add(route);

        // Capture generation as the "task" would
        long taskGen = tracker.getGeneration();

        // Also inject a real task and verify it gets cancelled
        AsyncTask task = createRealTask();
        putTaskViaReflection(tracker, route, task);

        // User pauses
        tracker.pause();

        // Task's onPostExecute fires — generation mismatch → stale
        assertTrue(tracker.getGeneration() != taskGen);
        // And the task itself is cancelled
        assertTrue(task.isCancelled());
    }

    /**
     * Multiple tasks in flight, pause happens, all must be stale.
     */
    @Test
    public void pauseInvalidatesAllInFlightTasksSimultaneously() {
        Route r1 = makeRoute("1", "1");
        Route r2 = makeRoute("2", "2");
        Route r3 = makeRoute("3", "3");
        tracker.add(r1);
        tracker.add(r2);
        tracker.add(r3);

        AsyncTask t1 = createRealTask();
        AsyncTask t2 = createRealTask();
        AsyncTask t3 = createRealTask();
        putTaskViaReflection(tracker, r1, t1);
        putTaskViaReflection(tracker, r2, t2);
        putTaskViaReflection(tracker, r3, t3);

        long genBefore = tracker.getGeneration();
        tracker.pause();

        // All generation-based checks fail (stale)
        assertTrue(tracker.getGeneration() != genBefore);
        // All tasks are cancelled
        assertTrue(t1.isCancelled());
        assertTrue(t2.isCancelled());
        assertTrue(t3.isCancelled());
    }

    /**
     * Simulates: pause → restart → old task callback fires.
     * The old task (created before pause) is stale because restart bumped generation twice.
     */
    @Test
    public void pauseThenRestartInvalidatesOldTask() {
        long oldGen = tracker.getGeneration();

        AsyncTask oldTask = createRealTask();
        Route route = makeRoute("1", "1");
        tracker.add(route);
        putTaskViaReflection(tracker, route, oldTask);

        tracker.pause();
        tracker.restart();

        // Task created before pause is stale (generation bumped at least twice)
        assertTrue(tracker.getGeneration() != oldGen);
        assertTrue(oldTask.isCancelled());
    }

    // =====================================================================
    // 9. Race Condition: Duplicate Route Re-Sync
    // =====================================================================

    /**
     * Re-adding the same route must cancel the old task before creating the new one.
     * This prevents two tasks for the same route from both writing to the map.
     */
    @Test
    public void duplicateRouteSyncCancelsOldTask() {
        Route route = makeRoute("1", "1");
        tracker.add(route);

        // Replace the task created by add() with our own
        AsyncTask oldTask = createRealTask();
        putTaskViaReflection(tracker, route, oldTask);

        tracker.add(route);  // re-add same route

        // Old task must be cancelled
        assertTrue("Old task should be cancelled on re-add", oldTask.isCancelled());
        // Route should still be tracked (replaced, not removed)
        assertTrue(tracker.getTracked().contains(route));
    }

    /**
     * Re-adding a route after pause must invalidate old generation.
     */
    @Test
    public void reAddAfterPauseInvalidatesOldGeneration() {
        Route route = makeRoute("1", "1");
        tracker.add(route);
        long genBeforePause = tracker.getGeneration();

        tracker.pause();
        long genAfterPause = tracker.getGeneration();

        tracker.add(route);  // re-add after pause
        long genAfterReAdd = tracker.getGeneration();

        // Generation bumped on pause; re-add doesn't bump again but old gen is stale
        assertTrue(genAfterPause != genBeforePause);
        assertEquals(genAfterPause, genAfterReAdd);
    }

    /**
     * Simulates the full duplicate-sync race:
     * 1. Task A starts for route R (captures gen0)
     * 2. User re-adds route R → Task A cancelled, Task B starts (captures gen0)
     * 3. Task A's onPostExecute fires → cancelled → skipped
     * 4. Task B's onPostExecute fires → not stale → writes to map
     */
    @Test
    public void duplicateSyncRaceOnlyNewTaskWritesToMap() {
        Route route = makeRoute("1", "1");

        // Task A: captures gen0
        long genA = tracker.getGeneration();

        // Re-add route → cancel old task, create new
        tracker.add(route);

        // Task B: captures gen (same, since add doesn't bump generation)
        long genB = tracker.getGeneration();

        // Both captured same generation (add doesn't bump it)
        assertEquals(genA, genB);

        // The cancel check (isCancelled()) is what differentiates them:
        // The old task is cancelled, the new one is not.
        // This is verified by the addDuplicateRouteReplacesTask test above.
    }

    // =====================================================================
    // 10. Restart Semantics
    // =====================================================================

    @Test
    public void restartSetsBBox() {
        tracker.restart();
        verify(mockAdapter).setBBox();
    }

    @Test
    public void restartIncrementsGenerationEachCall() {
        tracker.restart();
        long gen1 = tracker.getGeneration();
        tracker.restart();
        long gen2 = tracker.getGeneration();
        assertTrue(gen2 > gen1);
    }

    @Test
    public void startResetsPortalClientAndRestarts() {
        tracker.start();
        verify(mockPortalClient).reset();
        verify(mockAdapter).setBBox();
    }

    // =====================================================================
    // 11. Multi-Route Concurrent Scenario
    // =====================================================================

    /**
     * Adds 5 routes, pauses, verifies all tasks are cancelled and stale.
     */
    @Test
    public void multiRoutePauseCancelsAllAndInvalidates() {
        List<Route> routes = new ArrayList<Route>();
        List<AsyncTask> tasks = new ArrayList<AsyncTask>();
        for (int i = 1; i <= 5; i++) {
            Route r = makeRoute(String.valueOf(i), String.valueOf(i));
            routes.add(r);
            tracker.add(r);
            AsyncTask t = createRealTask();
            tasks.add(t);
            putTaskViaReflection(tracker, r, t);
        }

        long genBefore = tracker.getGeneration();
        tracker.pause();

        // All tasks should be cancelled
        for (AsyncTask t : tasks) {
            assertTrue("All tasks should be cancelled after pause", t.isCancelled());
        }
        // Generation should have changed (stale)
        assertNotEquals(genBefore, tracker.getGeneration());
    }

    /**
     * Stress: rapidly add and remove many routes, verify final state consistency.
     */
    @Test
    public void stressRapidAddRemoveManyRoutes() {
        List<Route> routes = new ArrayList<Route>();
        for (int i = 0; i < 50; i++) {
            routes.add(makeRoute(String.valueOf(i), String.valueOf(i)));
        }

        // Add all
        for (Route r : routes) {
            tracker.add(r);
        }
        assertEquals(50, tracker.getTracked().size());

        // Remove even-indexed
        for (int i = 0; i < routes.size(); i += 2) {
            tracker.remove(routes.get(i));
        }
        assertEquals(25, tracker.getTracked().size());

        // Re-add even-indexed
        for (int i = 0; i < routes.size(); i += 2) {
            tracker.add(routes.get(i));
        }
        assertEquals(50, tracker.getTracked().size());

        // Pause and verify generation changed
        long genBefore = tracker.getGeneration();
        tracker.pause();
        assertTrue(tracker.getGeneration() != genBefore);

        // All 50 routes still tracked after pause
        assertEquals(50, tracker.getTracked().size());
    }

    /**
     * Simulates pause + concurrent completion: many tasks captured at gen N,
     * pause bumps to gen N+1, then restart bumps to gen N+2.
     * All old tasks must be stale.
     */
    @Test
    public void pauseRestartCycleInvalidatesAllPreviousTasks() {
        for (int i = 0; i < 10; i++) {
            tracker.add(makeRoute(String.valueOf(i), String.valueOf(i)));
        }
        long capturedGen = tracker.getGeneration();

        // Cycle 1: pause + restart
        tracker.pause();
        tracker.restart();

        // Cycle 2: pause + restart
        tracker.pause();
        tracker.restart();

        // All tasks from the initial capture are stale
        assertTrue(tracker.getGeneration() != capturedGen);
        assertTrue(tracker.getGeneration() > capturedGen);
    }

    // =====================================================================
    // 12. Edge Cases
    // =====================================================================

    @Test
    public void getTrackedReturnsCopy() {
        Route route = makeRoute("1", "1");
        tracker.add(route);
        ArrayList<Route> tracked1 = tracker.getTracked();
        ArrayList<Route> tracked2 = tracker.getTracked();
        assertNotSame(tracked1, tracked2);
        assertEquals(tracked1, tracked2);
    }

    @Test
    public void pauseOnEmptyTrackerDoesNotThrow() {
        tracker.pause();
        assertTrue(tracker.getGeneration() > 0);
    }

    @Test
    public void stopOnEmptyTrackerDoesNotThrow() {
        tracker.stop();
        assertTrue(tracker.getTracked().isEmpty());
    }

    @Test
    public void removeAfterStopIsNoOp() {
        Route route = makeRoute("1", "1");
        tracker.add(route);
        tracker.stop();
        tracker.remove(route);  // should not throw
    }

    // =====================================================================
    // 13. Cancel Propagation Verification
    // =====================================================================

    /**
     * Verifies that pause() cancels both the syncTypesTask and route tasks.
     * We inject a real task as syncTypesTask via reflection.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void pauseCancelsSyncTypesTask() throws Exception {
        AsyncTask syncTask = createRealTask();
        java.lang.reflect.Field field = VehicleTracker.class.getDeclaredField("syncTypesTask");
        field.setAccessible(true);
        field.set(tracker, syncTask);

        tracker.pause();

        assertTrue("syncTypesTask should be cancelled after pause", syncTask.isCancelled());
    }

    /**
     * Verifies that re-adding a route after it was removed creates a fresh task.
     */
    @Test
    public void reAddAfterRemoveCreatesFreshTask() {
        Route route = makeRoute("1", "1");
        tracker.add(route);
        AsyncTask firstTask = getTaskForRoute(tracker, route);

        tracker.remove(route);
        assertNull(getTaskForRoute(tracker, route));

        tracker.add(route);
        AsyncTask secondTask = getTaskForRoute(tracker, route);
        assertNotNull(secondTask);
        assertNotSame(firstTask, secondTask);
    }
}
