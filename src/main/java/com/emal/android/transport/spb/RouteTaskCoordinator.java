package com.emal.android.transport.spb;

import com.emal.android.transport.spb.portal.Route;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Coordinates the lifecycle of the per-route asynchronous drawing tasks owned by
 * {@link VehicleTracker}.
 * <p/>
 * The class is deliberately free of any Android dependency so that the concurrency rules below
 * can be exercised by plain JVM unit tests (see {@code RouteTaskCoordinatorTest}). In production
 * every method is invoked from the main looper, but the state is still guarded so the tests can
 * hammer it from several threads.
 * <p/>
 * Per-route task rules:
 * <ul>
 *   <li><b>create</b>  &ndash; {@link #addRoute(Route)} marks a route as tracked.</li>
 *   <li><b>schedule</b>&ndash; {@link #beginTask(Route)} issues a fresh {@link Token} for the
 *       route's current drawing round.</li>
 *   <li><b>replace</b> &ndash; a second {@link #beginTask(Route)} call atomically supersedes the
 *       previous token, so the older task can no longer touch the map.</li>
 *   <li><b>cancel</b>  &ndash; {@link #removeRoute(Route)}, {@link #pause()} and {@link #clear()}
 *       drop the affected tokens.</li>
 *   <li><b>complete</b>&ndash; a finished task must consult {@link #shouldApply(Route, Token)} and
 *       only mutate the map when it returns {@code true}.</li>
 * </ul>
 */
public class RouteTaskCoordinator {

    /**
     * Opaque identity issued once per scheduling round. Equality is identity on purpose: only the
     * exact instance returned by the latest {@link #beginTask(Route)} is considered current.
     */
    public static final class Token {
    }

    private final Set<Route> trackedRoutes = new LinkedHashSet<Route>();
    private final Map<Route, Token> currentTokens = new HashMap<Route, Token>();
    private boolean paused;

    /**
     * Marks {@code route} as tracked.
     *
     * @return {@code true} if it was not tracked before.
     */
    public synchronized boolean addRoute(Route route) {
        return trackedRoutes.add(route);
    }

    /**
     * Stops tracking {@code route} and drops its current token so any in-flight task for it
     * becomes stale.
     *
     * @return {@code true} if it was tracked.
     */
    public synchronized boolean removeRoute(Route route) {
        currentTokens.remove(route);
        return trackedRoutes.remove(route);
    }

    public synchronized boolean isTracked(Route route) {
        return trackedRoutes.contains(route);
    }

    /**
     * @return an isolated snapshot of the currently tracked routes, safe to iterate while the
     *         coordinator keeps changing.
     */
    public synchronized Set<Route> trackedRoutes() {
        return new LinkedHashSet<Route>(trackedRoutes);
    }

    public synchronized boolean isEmpty() {
        return trackedRoutes.isEmpty();
    }

    /**
     * Issues the current token for {@code route}'s next drawing round, atomically replacing the
     * previous one. The token is only retained when the route is tracked, so a task scheduled for
     * an untracked route can never become current.
     */
    public synchronized Token beginTask(Route route) {
        Token token = new Token();
        if (trackedRoutes.contains(route)) {
            currentTokens.put(route, token);
        }
        return token;
    }

    /**
     * @return {@code true} only when the result produced by {@code token} may still update the map:
     *         tracking is active, the route is still tracked and {@code token} is the latest token
     *         issued for that route.
     */
    public synchronized boolean shouldApply(Route route, Token token) {
        if (paused) {
            return false;
        }
        if (!trackedRoutes.contains(route)) {
            return false;
        }
        return currentTokens.get(route) == token;
    }

    /**
     * Suspends tracking and invalidates every in-flight token so callbacks that finish after this
     * call become no-ops. Tracked membership is kept so {@link #resume()} can restart exactly the
     * routes that were selected.
     */
    public synchronized void pause() {
        paused = true;
        currentTokens.clear();
    }

    /** Re-enables tracking. Does not by itself schedule any task. */
    public synchronized void resume() {
        paused = false;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    /** Forgets all routes and tokens. */
    public synchronized void clear() {
        trackedRoutes.clear();
        currentTokens.clear();
    }
}
