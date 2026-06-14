package com.emal.android.transport.spb.task;

import android.os.AsyncTask;
import android.util.Log;
import com.emal.android.transport.spb.VehicleSyncAdapter;
import com.emal.android.transport.spb.VehicleTracker;
import com.emal.android.transport.spb.VehicleType;
import com.emal.android.transport.spb.portal.*;
import com.emal.android.transport.spb.utils.DrawHelper;
import com.google.android.gms.maps.model.*;

import java.util.*;

/**
 * Fetches vehicle positions for a single route and draws markers on the map.
 *
 * <p>Each task captures the {@link VehicleTracker} generation at construction time.
 * Before writing results back to the map in {@link #onPostExecute}, it verifies:
 * <ol>
 *   <li>The task has not been cancelled ({@link #isCancelled()}).</li>
 *   <li>The tracker generation has not advanced (i.e. no pause/stop/restart happened
 *       since this task was created).</li>
 * </ol>
 * If either check fails, the callback is skipped entirely — preventing stale or
 * cancelled tasks from updating the map overlay.
 *
 * @author alexey.emelyanenko@gmail.com
 * @since: 1.5
 */
public class DrawVehicleTask extends AsyncTask<Object, Void, List<Vehicle>> {
    private static final String TAG = DrawVehicleTask.class.getName();
    private Route route;
    private VehicleSyncAdapter vehicleSyncAdapter;
    private final VehicleTracker tracker;
    private final long taskGeneration;

    public DrawVehicleTask(Route route, VehicleSyncAdapter vehicleSyncAdapter, VehicleTracker tracker) {
        this.route = route;
        this.vehicleSyncAdapter = vehicleSyncAdapter;
        this.tracker = tracker;
        this.taskGeneration = tracker.getGeneration();
    }

    /**
     * Returns the generation captured at construction time.
     * Visible for testing.
     */
    long getTaskGeneration() {
        return taskGeneration;
    }

    /**
     * Checks whether this task is stale — either cancelled or superseded by a
     * tracker state change (pause, stop, restart, route replacement).
     * Visible for testing.
     */
    boolean isStale() {
        return isCancelled() || tracker.getGeneration() != taskGeneration;
    }

    @Override
    protected void onPreExecute() {
        if (isStale()) {
            return;
        }
        vehicleSyncAdapter.beforeSync(false);
    }

    @Override
    protected List<Vehicle> doInBackground(Object... params) {
        List<Vehicle> list = null;
        try {
            Log.d(TAG, "Get vehicles fro route: " + route);
            VehicleCollection routeData = vehicleSyncAdapter.getPortalClient().getRouteData(route.getId());
            list = routeData.getList();
            Log.d(TAG, "Found vehicles size: " + list.size());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    @Override
    protected void onPostExecute(List<Vehicle> vehicles) {
        if (isStale()) {
            Log.d(TAG, "onPostExecute skipped (stale): cancelled=" + isCancelled()
                    + ", gen=" + taskGeneration + ", trackerGen=" + tracker.getGeneration()
                    + ", route=" + route);
            vehicleSyncAdapter.afterSync(false);
            return;
        }

        if (vehicles == null) {
            vehicleSyncAdapter.afterSync(false);
            return;
        }
        Log.d(TAG, "Found new vehicles size: " + vehicles.size());
        List<Marker[]> routeMarkers = new ArrayList<Marker[]>();

        for (Vehicle v : vehicles) {
            Double latitude = v.getGeometry().getLatitude();
            Double longtitude = v.getGeometry().getLongtitude();

            LatLng homePoint = new LatLng(latitude, longtitude);

            VehicleProps properties = v.getProperties();

            VehicleType transportType = route.getTransportType();
            int direction = properties.getDirection();
            if (transportType.isUpsideDown()) {
                direction += 180;
            }

            BitmapDescriptor vehicleTypeBitmap = DrawHelper.getVechicleTypeBitmapDescriptor(route.getTransportType(), vehicleSyncAdapter.getScaleFactor());
            MarkerOptions vehicleMarker = new MarkerOptions()
                    .position(homePoint)
                    .anchor(0.5f, 0.5f)
                    .icon(vehicleTypeBitmap)
                    .rotation(direction)
                    .infoWindowAnchor((float) Math.sin(Math.toRadians(direction)) * (-0.5f) + 0.5f, (float) Math.cos(Math.toRadians(direction)) * (-0.5f) + 0.5f)
                    .title(properties.getDisplayValue());

            BitmapDescriptor vehicleNumber = DrawHelper.getVechicleNumberBitmapDescriptor(route.getRouteNumber(), vehicleSyncAdapter.getScaleFactor());
            MarkerOptions numberMarker = new MarkerOptions()
                    .position(homePoint)
                    .anchor(0.5f, 0.5f)
                    .icon(vehicleNumber);

            String vehId = v.getId();
            Log.d(TAG, "Add new marker for vehicle: " + vehId);
            Marker vechicleTypeM = vehicleSyncAdapter.addMarker(vehicleMarker);
            Marker vehicleTypeM = vehicleSyncAdapter.addMarker(numberMarker);
            routeMarkers.add(new Marker[]{vechicleTypeM, vehicleTypeM});
        }

        vehicleSyncAdapter.updateMarkers(route, routeMarkers);
        vehicleSyncAdapter.afterSync(true);
    }
}
