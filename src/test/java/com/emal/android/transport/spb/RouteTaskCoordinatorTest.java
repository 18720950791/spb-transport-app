package com.emal.android.transport.spb;

import com.emal.android.transport.spb.portal.Route;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Concurrency tests for {@link RouteTaskCoordinator}, which owns the task-lifecycle rules that the
 * bug fix relies on. These exercise the three race scenarios called out by the bug report:
 * rapid add/remove, pause happening while a task completes, and the same route being synced twice.
 */
public class RouteTaskCoordinatorTest {

    private static Route route(String id) {
        // equals()/hashCode() of Route only use id + routeNumber, so transportType can stay null,
        // which keeps these tests free of any Android dependency.
        return Route.RouteBuilder.getInstance().id(id).routeNumber(id).build();
    }

    // ---- single-threaded lifecycle rules -------------------------------------------------

    @Test
    public void replacedTokenIsNoLongerCurrent() {
        RouteTaskCoordinator coordinator = new RouteTaskCoordinator();
        Route r = route("1");
        coordinator.addRoute(r);

        RouteTaskCoordinator.Token first = coordinator.beginTask(r);
        RouteTaskCoordinator.Token second = coordinator.beginTask(r);

        assertFalse("superseded task must not update the map", coordinator.shouldApply(r, first));
        assertTrue("the latest scheduled task is the current one", coordinator.shouldApply(r, second));
    }

    @Test
    public void pauseInvalidatesInFlightToken() {
        RouteTaskCoordinator coordinator = new RouteTaskCoordinator();
        Route r = route("1");
        coordinator.addRoute(r);
        RouteTaskCoordinator.Token token = coordinator.beginTask(r);
        assertTrue(coordinator.shouldApply(r, token));

        coordinator.pause();

        assertTrue(coordinator.isPaused());
        assertFalse("a task that finishes after pause() must be ignored", coordinator.shouldApply(r, token));
    }

    @Test
    public void removedRouteResultIsRejected() {
        RouteTaskCoordinator coordinator = new RouteTaskCoordinator();
        Route r = route("1");
        coordinator.addRoute(r);
        RouteTaskCoordinator.Token token = coordinator.beginTask(r);

        coordinator.removeRoute(r);

        assertFalse("a removed route must not be redrawn", coordinator.shouldApply(r, token));
    }

    @Test
    public void untrackedRouteNeverApplies() {
        RouteTaskCoordinator coordinator = new RouteTaskCoordinator();
        Route r = route("1");
        // No addRoute(r): the route was never part of the current tracking set.
        RouteTaskCoordinator.Token token = coordinator.beginTask(r);

        assertFalse("a route outside the tracking set must not update the map",
                coordinator.shouldApply(r, token));
    }

    @Test
    public void resumeReenablesScheduling() {
        RouteTaskCoordinator coordinator = new RouteTaskCoordinator();
        Route r = route("1");
        coordinator.addRoute(r);
        coordinator.pause();
        assertTrue(coordinator.isPaused());

        coordinator.resume();
        RouteTaskCoordinator.Token token = coordinator.beginTask(r);

        assertFalse(coordinator.isPaused());
        assertTrue("after resume only the freshly scheduled route applies", coordinator.shouldApply(r, token));
    }

    @Test
    public void clearForgetsEverything() {
        RouteTaskCoordinator coordinator = new RouteTaskCoordinator();
        Route r = route("1");
        coordinator.addRoute(r);
        RouteTaskCoordinator.Token token = coordinator.beginTask(r);

        coordinator.clear();

        assertTrue(coordinator.trackedRoutes().isEmpty());
        assertFalse(coordinator.shouldApply(r, token));
    }

    // ---- race scenario: rapid add / remove -----------------------------------------------

    @Test
    public void rapidAddRemoveKeepsStateConsistent() throws Exception {
        final RouteTaskCoordinator coordinator = new RouteTaskCoordinator();
        final int threads = 8;
        final int iterations = 5000;
        final Route shared = route("shared");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicBoolean inconsistent = new AtomicBoolean(false);

        for (int t = 0; t < threads; t++) {
            final Route owned = route("owned-" + t);
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < iterations; i++) {
                        // Hammer a route this thread alone owns and a route every thread fights over.
                        coordinator.addRoute(owned);
                        coordinator.addRoute(shared);
                        // Invariant that must always hold regardless of interleaving.
                        if (coordinator.isTracked(owned) != coordinator.trackedRoutes().contains(owned)) {
                            inconsistent.set(true);
                        }
                        coordinator.removeRoute(shared);
                        coordinator.removeRoute(owned);
                    }
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue("threads did not finish in time", pool.awaitTermination(30, TimeUnit.SECONDS));

        assertFalse("isTracked() and trackedRoutes() disagreed during concurrent add/remove",
                inconsistent.get());
        // Every thread's final operation removed both routes it touched.
        assertFalse(coordinator.isTracked(shared));
        for (int t = 0; t < threads; t++) {
            assertFalse(coordinator.isTracked(route("owned-" + t)));
        }
    }

    // ---- race scenario: pause while a task completes --------------------------------------

    @Test
    public void pauseRacingCompletionNeverAppliesAfterPause() throws Exception {
        final RouteTaskCoordinator coordinator = new RouteTaskCoordinator();
        final Route r = route("1");
        coordinator.addRoute(r);

        final int trials = 2000;
        for (int i = 0; i < trials; i++) {
            coordinator.resume();
            final RouteTaskCoordinator.Token token = coordinator.beginTask(r);
            assertTrue("sanity: a freshly scheduled task is current", coordinator.shouldApply(r, token));

            // One thread pauses while the main thread races to apply the in-flight result.
            Thread pauser = new Thread(new Runnable() {
                @Override
                public void run() {
                    coordinator.pause();
                }
            });
            pauser.start();
            // Simulate the completion callback repeatedly trying to apply during the pause window.
            for (int spin = 0; spin < 50; spin++) {
                coordinator.shouldApply(r, token);
            }
            pauser.join();

            // Once paused, the result produced before the pause must never be applied.
            assertFalse("task completed concurrently with pause() but was still applied",
                    coordinator.shouldApply(r, token));
        }
    }

    // ---- race scenario: duplicate sync of the same route ---------------------------------

    @Test
    public void duplicateRouteSyncKeepsExactlyOneCurrentToken() throws Exception {
        final RouteTaskCoordinator coordinator = new RouteTaskCoordinator();
        final Route r = route("1");
        coordinator.addRoute(r);

        final int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final List<RouteTaskCoordinator.Token> issued = new CopyOnWriteArrayList<RouteTaskCoordinator.Token>();

        for (int t = 0; t < threads; t++) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // Each thread kicks off another sync round for the same route.
                    issued.add(coordinator.beginTask(r));
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        int current = 0;
        for (RouteTaskCoordinator.Token token : issued) {
            if (coordinator.shouldApply(r, token)) {
                current++;
            }
        }
        assertEquals("exactly one overlapping sync round may draw the route", 1, current);
    }
}
