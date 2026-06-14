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
 * User: alexey.emelyanenko@gmail.com
 * Date: 5/18/13 5:06 AM
 * <p/>
 * Drives the periodic refresh of vehicle positions. The lifecycle of each per-route drawing task
 * is delegated to {@link RouteTaskCoordinator}, which is the single source of truth for whether a
 * finished {@link DrawVehicleTask} is still allowed to update the map. {@code runningTasks} only
 * keeps the live {@link AsyncTask} handles so a superseded round can be cancelled best-effort.
 */
public class VehicleTracker {
    private static final String TAG = VehicleTracker.class.getName();
    private AsyncTask syncTypesTask;
    private final Set<VehicleType> vehicleTypes;
    private final Map<Route, AsyncTask> runningTasks;
    private final RouteTaskCoordinator coordinator;
    private final VehicleSyncAdapter vehicleSyncAdapter;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TimerTask timerTask;

    private class MapUpdateTimerTask extends TimerTask {
        @Override
        public void run() {
            synchronized (VehicleTracker.this) {
                if (timerTask != this || coordinator.isPaused()) {
                    Log.d(TAG, "Skip timer tick: superseded or paused");
                    return;
                }
                int syncTime = vehicleSyncAdapter.getSyncTime();
                Log.d(TAG, "START Timer Update " + Thread.currentThread().getName() + " with time " + syncTime);
                scheduleTasks();
                mHandler.postDelayed(this, syncTime);
            }
        }
    }

    public VehicleTracker(VehicleSyncAdapter vehicleSyncAdapter) {
        this.vehicleSyncAdapter = vehicleSyncAdapter;
        this.vehicleTypes = Collections.synchronizedSet(new HashSet<VehicleType>());
        this.runningTasks = new ConcurrentHashMap<Route, AsyncTask>();
        this.coordinator = new RouteTaskCoordinator();
    }

    public synchronized void restart() {
        Log.d(TAG, "restart");
        coordinator.resume();
        vehicleSyncAdapter.setBBox();
        if (timerTask != null) {
            mHandler.removeCallbacks(timerTask);
            timerTask.cancel();
        }
        timerTask = new MapUpdateTimerTask();
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

    public synchronized void add(Route route) {
        coordinator.addRoute(route);
    }

    /**
     * Stops tracking a single route: cancels its running task best-effort, drops it from the
     * coordinator so any late callback is ignored, and removes its markers from the map.
     */
    public synchronized void remove(Route route) {
        AsyncTask task = runningTasks.remove(route);
        if (task != null && !AsyncTask.Status.FINISHED.equals(task.getStatus())) {
            task.cancel(true);
        }
        coordinator.removeRoute(route);
        vehicleSyncAdapter.removeMarkers(route);
    }

    public ArrayList<Route> getTracked() {
        return new ArrayList<Route>(coordinator.trackedRoutes());
    }

    public synchronized void resume() {
        restart();
    }

    public synchronized void pause() {
        Log.d(TAG, "pause tracking <<");
        if (timerTask != null) {
            mHandler.removeCallbacks(timerTask);
            timerTask.cancel();
        }

        if (syncTypesTask != null && !AsyncTask.Status.FINISHED.equals(syncTypesTask.getStatus())) {
            syncTypesTask.cancel(true);
        }

        Log.d(TAG, "stopTrackAllRoutes <<");
        for (Map.Entry<Route, AsyncTask> entry : runningTasks.entrySet()) {
            AsyncTask value = entry.getValue();
            if (value != null && !AsyncTask.Status.FINISHED.equals(value.getStatus())) {
                value.cancel(true);
            }
        }
        runningTasks.clear();
        // Invalidate every in-flight token so a callback that finishes after this point is a no-op.
        coordinator.pause();
        vehicleSyncAdapter.clearOverlay();
        Log.d(TAG, "pause tracking >>");
    }

    public synchronized void stop() {
        Log.d(TAG, "stop tracking <<");
        pause();
        for (Route route : coordinator.trackedRoutes()) {
            vehicleSyncAdapter.removeMarkers(route);
        }
        coordinator.clear();
        runningTasks.clear();
        Log.d(TAG, "stop tracking >>");
    }

    private synchronized void scheduleTasks() {
        Log.d(TAG, "scheduleTasks <<");
        if (coordinator.isPaused()) {
            Log.d(TAG, "scheduleTasks skipped: paused");
            return;
        }
        if (syncTypesTask != null && !AsyncTask.Status.FINISHED.equals(syncTypesTask.getStatus())) {
            Log.d(TAG, "Reschedule vehicleTypes");
            syncTypesTask.cancel(true);
        }

        Set<Route> tracked = coordinator.trackedRoutes();
        if (!vehicleTypes.isEmpty() && tracked.isEmpty()) {
            Log.d(TAG, "Scheduling typed layout for types: " + vehicleTypes);
            syncTypesTask = new SyncVehiclePositionTask(vehicleSyncAdapter, vehicleTypes).execute();
        }

        for (Route route : tracked) {
            Log.d(TAG, "Scheduling route: " + route);
            AsyncTask prev = runningTasks.get(route);
            if (prev != null && !AsyncTask.Status.FINISHED.equals(prev.getStatus())) {
                prev.cancel(true);
            }
            // Issuing a fresh token supersedes the previous round for this route.
            RouteTaskCoordinator.Token token = coordinator.beginTask(route);
            AsyncTask task = new DrawVehicleTask(route, vehicleSyncAdapter, coordinator, token).execute();
            runningTasks.put(route, task);
        }
        Log.d(TAG, "scheduleTasks >>");
    }
}
