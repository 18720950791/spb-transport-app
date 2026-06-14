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
 */
public class VehicleTracker {
    private static final String TAG = VehicleTracker.class.getName();
    private AsyncTask syncTypesTask;
    private Set<VehicleType> vehicleTypes;
    private Map<Route, AsyncTask> routeTaskMap;
    private VehicleSyncAdapter vehicleSyncAdapter;
    private final TimerScheduler scheduler;
    private TimerTask timerTask;

    private class MapUpdateTimerTask extends TimerTask {
        @Override
        public void run() {
            //TODO
            synchronized (VehicleTracker.this) {
                int syncTime = vehicleSyncAdapter.getSyncTime();
                Log.d(TAG, "START Timer Update " + Thread.currentThread().getName() + " with time " + syncTime);
                scheduleTasks();
                scheduler.schedule(this, syncTime);
            }
        }
    }

    /**
     * Seam over the periodic-callback mechanism. Lets the timer lifecycle be unit tested on a
     * plain JVM, where the Android Handler/Looper would otherwise throw "Stub!".
     */
    interface TimerScheduler {
        void schedule(Runnable task, long delayMillis);

        void unschedule(Runnable task);
    }

    private static final class HandlerScheduler implements TimerScheduler {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void schedule(Runnable task, long delayMillis) {
            handler.postDelayed(task, delayMillis);
        }

        @Override
        public void unschedule(Runnable task) {
            handler.removeCallbacks(task);
        }
    }

    public VehicleTracker(VehicleSyncAdapter vehicleSyncAdapter) {
        this(vehicleSyncAdapter, new HandlerScheduler());
    }

    VehicleTracker(VehicleSyncAdapter vehicleSyncAdapter, TimerScheduler scheduler) {
        this.vehicleSyncAdapter = vehicleSyncAdapter;
        this.scheduler = scheduler;
        this.vehicleTypes = Collections.synchronizedSet(new HashSet<VehicleType>());
        this.routeTaskMap = new ConcurrentHashMap<Route, AsyncTask>();
    }

    public synchronized void restart() {
        Log.d(TAG, "restart");
        vehicleSyncAdapter.setBBox();
        // A TimerTask is single-use: once cancelled it can never run again. Always remove the old
        // callback and start a fresh task so periodic refresh resumes after start/pause/restart.
        if (timerTask != null) {
            scheduler.unschedule(timerTask);
            timerTask.cancel();
        }
        timerTask = new MapUpdateTimerTask();
        scheduler.schedule(timerTask, 0);
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
        AsyncTask asyncTask = routeTaskMap.get(route);
        if (asyncTask == null) {
            routeTaskMap.put(route, new DrawVehicleTask(route, vehicleSyncAdapter));
        }
    }

    public ArrayList<Route> getTracked() {
        return new ArrayList<Route>(routeTaskMap.keySet());
    }

    public synchronized void pause() {
        Log.d(TAG, "pause tracking <<");
        if (timerTask != null) {
            scheduler.unschedule(timerTask);
            timerTask.cancel();
            timerTask = null;
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

    public synchronized void stop() {
        Log.d(TAG, "stop tracking <<");
        pause();
        for (Map.Entry<Route, AsyncTask> task : routeTaskMap.entrySet()) {
            Route key = task.getKey();
            vehicleSyncAdapter.removeMarkers(key);
        }
        routeTaskMap.clear();
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
            task = new DrawVehicleTask(route, vehicleSyncAdapter).execute();
            routeTaskMap.put(route, task);
        }
        Log.d(TAG, "scheduleTasks >>");
    }
}
