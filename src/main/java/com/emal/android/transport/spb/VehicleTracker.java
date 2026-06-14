package com.emal.android.transport.spb;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.emal.android.transport.spb.portal.Route;
import com.emal.android.transport.spb.task.DrawVehicleTask;
import com.emal.android.transport.spb.task.SyncVehiclePositionTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates periodic vehicle position fetching and map rendering for tracked routes.
 *
 * <h3>Task lifecycle rules</h3>
 * <ul>
 *   <li>Each route maps to exactly one active {@link DrawVehicleTask} in {@code routeTaskMap}.</li>
 *   <li>Adding a route that already has a task cancels the old task first, then creates a new one.</li>
 *   <li>{@link #pause()} and {@link #stop()} bump an internal <em>generation</em> counter.
 *       Any task that was created under a previous generation is considered <em>stale</em>
 *       and will skip its map-update callback.</li>
 *   <li>{@link #remove(Route)} cancels the task for a single route and removes its markers.</li>
 *   <li>{@link #restart()} bumps the generation so that any in-flight tasks from before the
 *       restart are invalidated, then starts a fresh timer cycle for the current route set.</li>
 * </ul>
 *
 * User: alexey.emelyanenko@gmail.com
 * Date: 5/18/13 5:06 AM
 */
public class VehicleTracker {
    private static final String TAG = VehicleTracker.class.getName();
    private AsyncTask syncTypesTask;
    private Set<VehicleType> vehicleTypes;
    private Map<Route, AsyncTask> routeTaskMap;
    private VehicleSyncAdapter vehicleSyncAdapter;
    private Handler mHandler;
    private TimerTask timerTask;

    /**
     * Monotonically increasing counter bumped on every state transition that should
     * invalidate in-flight tasks (pause, stop, restart). A {@link DrawVehicleTask}
     * captures the generation at creation time and checks it before updating the map.
     */
    private volatile long generation;

    private class MapUpdateTimerTask extends TimerTask {
        @Override
        public void run() {
            synchronized (VehicleTracker.this) {
                int syncTime = vehicleSyncAdapter.getSyncTime();
                Log.d(TAG, "START Timer Update " + Thread.currentThread().getName() + " with time " + syncTime);
                scheduleTasks();
                mHandler.postDelayed(this, syncTime);
            }
        }
    }

    public VehicleTracker(VehicleSyncAdapter vehicleSyncAdapter) {
        this(vehicleSyncAdapter, new Handler(Looper.getMainLooper()));
    }

    /**
     * Package-private constructor for testing: accepts an injectable {@link Handler}
     * so that tests can supply a mock without requiring the Android Looper.
     */
    VehicleTracker(VehicleSyncAdapter vehicleSyncAdapter, Handler handler) {
        this.vehicleSyncAdapter = vehicleSyncAdapter;
        this.mHandler = handler;
        this.vehicleTypes = Collections.synchronizedSet(new HashSet<VehicleType>());
        this.routeTaskMap = new ConcurrentHashMap<Route, AsyncTask>();
        this.generation = 0L;
    }

    /**
     * Returns the current generation counter. Tasks compare their captured generation
     * against this value to detect staleness.
     */
    public long getGeneration() {
        return generation;
    }

    public synchronized void restart() {
        Log.d(TAG, "restart");
        generation++;
        vehicleSyncAdapter.setBBox();
        if (timerTask != null) {
            timerTask.cancel();
        } else {
            timerTask = new MapUpdateTimerTask();
        }
        mHandler.removeCallbacks(timerTask);
        mHandler.postDelayed(timerTask, 0);
    }

    public synchronized void start() {
        Log.d(TAG, "start");
        vehicleSyncAdapter.getPortalClient().reset();
        restart();
    }

    public boolean add(VehicleType vehicleType) {
        return vehicleTypes.add(vehicleType);
    }

    /**
     * Adds or replaces a tracked route.
     * <p>
     * If a task already exists for this route it is cancelled before a new one is created,
     * ensuring that the old task's callback cannot overwrite the new task's results.
     */
    public synchronized void add(Route route) {
        AsyncTask oldTask = routeTaskMap.get(route);
        if (oldTask != null && !AsyncTask.Status.FINISHED.equals(oldTask.getStatus())) {
            Log.d(TAG, "Cancelling previous task for route: " + route);
            oldTask.cancel(true);
        }
        DrawVehicleTask newTask = new DrawVehicleTask(route, vehicleSyncAdapter, this);
        routeTaskMap.put(route, newTask);
    }

    /**
     * Removes a single route from tracking.
     * Cancels its in-flight task (if any) and removes its markers from the map.
     *
     * @param route the route to stop tracking
     */
    public synchronized void remove(Route route) {
        AsyncTask task = routeTaskMap.remove(route);
        if (task != null && !AsyncTask.Status.FINISHED.equals(task.getStatus())) {
            Log.d(TAG, "Cancelling task for removed route: " + route);
            task.cancel(true);
        }
        vehicleSyncAdapter.removeMarkers(route);
    }

    public ArrayList<Route> getTracked() {
        return new ArrayList<Route>(routeTaskMap.keySet());
    }

    /**
     * Pauses tracking: cancels the timer and all in-flight tasks, bumps the generation
     * counter so that any already-scheduled callbacks are invalidated.
     * Route entries are kept in {@code routeTaskMap} so that {@link #restart()} can
     * resume the same set of routes.
     */
    public synchronized void pause() {
        Log.d(TAG, "pause tracking <<");
        generation++;
        mHandler.removeCallbacks(timerTask);
        if (timerTask != null) {
            timerTask.cancel();
        }

        if (syncTypesTask != null && !AsyncTask.Status.FINISHED.equals(syncTypesTask.getStatus())) {
            syncTypesTask.cancel(true);
        }
        vehicleSyncAdapter.clearOverlay();

        Log.d(TAG, "stopTrackAllRoutes <<");
        for (Map.Entry<Route, AsyncTask> task : routeTaskMap.entrySet()) {
            Route key = task.getKey();
            AsyncTask value = task.getValue();
            if (value != null && !AsyncTask.Status.FINISHED.equals(value.getStatus())) {
                value.cancel(true);
            }
        }
        Log.d(TAG, "pause tracking >>");
    }

    /**
     * Fully stops tracking: pauses, removes all markers, clears route and type state.
     */
    public synchronized void stop() {
        Log.d(TAG, "stop tracking <<");
        pause();
        for (Map.Entry<Route, AsyncTask> task : routeTaskMap.entrySet()) {
            Route key = task.getKey();
            vehicleSyncAdapter.removeMarkers(key);
        }
        routeTaskMap.clear();
        vehicleTypes.clear();
        Log.d(TAG, "stop tracking >>");
    }

    private synchronized void scheduleTasks() {
        Log.d(TAG, "scheduleTasks <<");
        if (syncTypesTask != null && !AsyncTask.Status.FINISHED.equals(syncTypesTask.getStatus())) {
            Log.d(TAG, "Reschedule vehicleTypes");
            syncTypesTask.cancel(true);
        }
        if (!vehicleTypes.isEmpty() && routeTaskMap.isEmpty()) {
            Log.d(TAG, "Scheduling typed layout for types: " + vehicleTypes);
            syncTypesTask = new SyncVehiclePositionTask(vehicleSyncAdapter, vehicleTypes).execute();
        }

        for (Route route : routeTaskMap.keySet()) {
            Log.d(TAG, "Scheduling route: " + route);
            AsyncTask task = routeTaskMap.get(route);
            if (task != null && !AsyncTask.Status.FINISHED.equals(task.getStatus())) {
                task.cancel(true);
            }
            task = new DrawVehicleTask(route, vehicleSyncAdapter, this).execute();
            routeTaskMap.put(route, task);
        }
        Log.d(TAG, "scheduleTasks >>");
    }
}
