package org.onebusaway.android.util;

import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.elements.ObaRegion;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaRouteElement;
import org.onebusaway.android.io.elements.ObaStop;
import org.onebusaway.android.io.elements.ObaStopElement;
import org.onebusaway.android.provider.ObaContract;
import org.onebusaway.android.ui.ArrivalInfo;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.text.TextUtils;

import com.google.common.collect.Lists;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by azizmb9494 on 2/20/16.
 */
public class DBUtil {
    public static void addStopToDB(ObaStop stop) {
        String name = UIUtils.formatDisplayText(stop.getName());

        // Update the database
        ContentValues values = new ContentValues();
        values.put(ObaContract.Stops.CODE, stop.getStopCode());
        values.put(ObaContract.Stops.NAME, name);
        values.put(ObaContract.Stops.DIRECTION, stop.getDirection());
        values.put(ObaContract.Stops.LATITUDE, stop.getLatitude());
        values.put(ObaContract.Stops.LONGITUDE, stop.getLongitude());
        if (Application.get().getCurrentRegion() != null) {
            values.put(ObaContract.Stops.REGION_ID, Application.get().getCurrentRegion().getId());
        }
        values.put(ObaContract.Stops.PARENT, stop.getParent());
        values.put(ObaContract.Stops.PLATFORM_CODE, stop.getPlatformCode());
        ObaContract.Stops.insertOrUpdate(stop.getId(), values, true);
    }

    public static List<ObaStop> queryStopsFromDB(Context ctx, Location center, double latSpan, double lonSpan) {
        ContentResolver cr = ctx.getContentResolver();
        final String[] PROJECTION = {
            ObaContract.Stops._ID,
            ObaContract.Stops.LATITUDE,
            ObaContract.Stops.LONGITUDE,
            ObaContract.Stops.DIRECTION,
            ObaContract.Stops.NAME,
            ObaContract.Stops.CODE,
            ObaContract.Stops.PARENT,
            ObaContract.Stops.PLATFORM_CODE,
        };

        NumberFormat formatter = new DecimalFormat("###.######");

        double latitudeMin = center.getLatitude() - latSpan / 2;
        double latitudeMax = center.getLatitude() + latSpan / 2;
        double longitudeMin = center.getLongitude() - lonSpan / 2;
        double longitudeMax = center.getLongitude() + lonSpan / 2;

        String selection =
            ObaContract.Stops.LATITUDE + ">=? AND " +
            ObaContract.Stops.LATITUDE + "<=? AND " +
            ObaContract.Stops.LONGITUDE + ">=? AND " +
            ObaContract.Stops.LONGITUDE + "<=?";
        String[] selectionArgs = new String[]{
            formatter.format(latitudeMin),
            formatter.format(latitudeMax),
            formatter.format(longitudeMin),
            formatter.format(longitudeMax),
        };

        List<ObaStop> stops = new ArrayList<>();

        Cursor c = cr.query(ObaContract.Stops.CONTENT_URI, PROJECTION, selection, selectionArgs, null);
        if (c != null) {
            try {
                while(c.moveToNext()) {
                    String stopId = c.getString(0);
                    ObaStopElement stop = new ObaStopElement(
                        stopId,
                        c.getDouble(1),
                        c.getDouble(2),
                        c.getString(3),
                        c.getString(4),
                        c.getString(5),
                        c.getString(6),
                        c.getString(7)
                    );
                    stop.setRouteIds(ObaContract.StopRouteFilters.get(ctx, stopId).toArray(new String[0]));
                    stops.add(stop);
                }
                return stops;

            } finally {
                c.close();
            }
        }
        return stops;
    }

    public static void addRouteToDB(Context ctx, ArrivalInfo arrivalInfo){
        ObaRegion region = Application.get().getCurrentRegion();
        long regionId;

        if (region == null) {
            regionId = -1;
        } else {
            regionId = region.getId();
        }

        ContentValues routeValues = new ContentValues();

        String shortName = arrivalInfo.getInfo().getShortName();
        String longName = arrivalInfo.getInfo().getRouteLongName();

        if (TextUtils.isEmpty(longName)) {
            longName = UIUtils.formatDisplayText(arrivalInfo.getInfo().getHeadsign());
        }

        routeValues.put(ObaContract.Routes.SHORTNAME, shortName);
        routeValues.put(ObaContract.Routes.LONGNAME, longName);
        routeValues.put(ObaContract.Routes.REGION_ID, regionId);

        ObaContract.Routes.insertOrUpdate(ctx, arrivalInfo.getInfo().getRouteId(), routeValues, true);
    }

    public static void addRouteToDB(Context ctx, ObaRoute route){
        ObaRegion region = Application.get().getCurrentRegion();
        long regionId;

        if (region == null) {
            regionId = -1;
        } else {
            regionId = region.getId();
        }

        ContentValues routeValues = new ContentValues();

        String shortName = route.getShortName();
        String longName = route.getLongName();

        if (TextUtils.isEmpty(shortName)) {
            shortName = longName;
        }
        if (TextUtils.isEmpty(longName) || shortName.equals(longName)) {
            longName = route.getDescription();
        }

        routeValues.put(ObaContract.Routes.SHORTNAME, shortName);
        routeValues.put(ObaContract.Routes.LONGNAME, longName);
        routeValues.put(ObaContract.Routes.URL, route.getUrl());
        routeValues.put(ObaContract.Routes.REGION_ID, regionId);

        ObaContract.Routes.insertOrUpdate(ctx, route.getId(), routeValues, true);
    }

    public static List<ObaRoute> queryRoutesFromDB(Context ctx, Iterable<ObaStop> stops) {
        Set<String> routeIDs = new HashSet<>();

        for (ObaStop stop : stops) {
            routeIDs.addAll(Arrays.asList(stop.getRouteIds()));
        }

        return queryRoutesFromDB(ctx, routeIDs);
    }
    public static List<ObaRoute> queryRoutesFromDB(Context ctx, Set<String> routeIDs) {
        ContentResolver cr = ctx.getContentResolver();

        final String[] PROJECTION = {
            ObaContract.Routes._ID,
            ObaContract.Routes.SHORTNAME,
            ObaContract.Routes.LONGNAME,
        };

        String selectionItem = ObaContract.Routes._ID + "=?";
        List<String> selectionList = Collections.nCopies(routeIDs.size(), selectionItem);
        String selection = String.join(" OR ", selectionList);
        String[] selectionArgs = routeIDs.toArray(new String[]{});

        List<ObaRoute> routes = new ArrayList<>();

        for (String routeId : routeIDs) {

            Cursor c = cr.query(ObaContract.Routes.CONTENT_URI, PROJECTION, selection, selectionArgs, null);
            if (c != null) {
                try {
                    while(c.moveToNext()) {
                        ObaRoute route = new ObaRouteElement(
                            routeId,
                            c.getString(1),
                            c.getString(2)
                        );
                        routes.add(route);
                    }
                } finally {
                    c.close();
                }
            }
        }

        return routes;
    }

    public static void addStopsAndRoutesToDB(Context ctx, List<ObaStop> stops, List<ObaRoute> routes) {
        for (ObaRoute route : routes) {
            addRouteToDB(ctx, route);
        }

        for (ObaStop stop : stops) {
            addStopToDB(stop);
            ObaContract.StopRouteFilters.setIfStopMissing(ctx, stop.getId(), Arrays.asList(stop.getRouteIds()));
        }
    }
}
