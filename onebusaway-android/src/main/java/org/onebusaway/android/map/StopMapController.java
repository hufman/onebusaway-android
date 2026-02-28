/*
 * Copyright (C) 2011-2014 Paul Watts (paulcwatts@gmail.com),
 * University of South Florida (sjbarbeau@gmail.com), and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.android.map;

import com.google.android.gms.common.api.GoogleApiClient;

import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.ObaApi;
import org.onebusaway.android.io.elements.ObaReferences;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaStop;
import org.onebusaway.android.io.elements.ObaStopsForLocationCacheResponse;
import org.onebusaway.android.io.request.ObaStopsForLocationInterface;
import org.onebusaway.android.io.request.ObaStopsForLocationRequest;
import org.onebusaway.android.io.request.ObaStopsForLocationResponse;
import org.onebusaway.android.map.googlemapsv2.BaseMapFragment;
import org.onebusaway.android.provider.ObaContract;
import org.onebusaway.android.util.DBUtil;
import org.onebusaway.android.util.RegionUtils;

import android.location.Location;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import androidx.loader.app.LoaderManager;
import androidx.loader.content.AsyncTaskLoader;
import androidx.loader.content.Loader;

final class StopsRequest {

    private final Location mCenter;

    private final double mLatSpan;

    private final double mLonSpan;

    private final double mZoomLevel;

    StopsRequest(MapModeController.ObaMapView view) {
        mCenter = view.getMapCenterAsLocation();
        mLatSpan = view.getLatitudeSpanInDecDegrees();
        mLonSpan = view.getLongitudeSpanInDecDegrees();
        mZoomLevel = view.getZoomLevelAsFloat();
    }

    Location getCenter() {
        return mCenter;
    }

    double getLatSpan() {
        return mLatSpan;
    }

    double getLonSpan() {
        return mLonSpan;
    }

    double getZoomLevel() {
        return mZoomLevel;
    }
}

final class StopsResponse {

    private final StopsRequest mRequest;

    private final ObaStopsForLocationInterface mResponse;

    StopsResponse(StopsRequest req, ObaStopsForLocationInterface response) {
        mRequest = req;
        mResponse = response;
    }

    StopsRequest getRequest() {
        return mRequest;
    }

    ObaStopsForLocationInterface getResponse() {
        return mResponse;
    }

    /**
     * Returns true if newReq also fulfills response.
     */
    boolean fulfills(StopsRequest newReq) {
        if (mRequest.getCenter() == null) {
            //Log.d(TAG, "No center");
            return false;
        }
        if (!mRequest.getCenter().equals(newReq.getCenter())) {
            //Log.d(TAG, "Center not the same");
            return false;
        }
        if (mResponse != null) {
            if ((newReq.getZoomLevel() > mRequest.getZoomLevel()) &&
                    mResponse.getLimitExceeded()) {
                //Log.d(TAG, "Zooming in -- limit exceeded");
                return false;
            } else if (newReq.getZoomLevel() < mRequest.getZoomLevel()) {
                //Log.d(TAG, "Zooming out");
                return false;
            }
        }
        return true;

        // Otherwise:

        // If the new request's is zoomed in and the current
        // response has limitExceeded, then no.

        // If the new request's lat/lon span is contained
        // entirely within the old one:
        //  Then the new request is fulfilled IFF the old request's
        //  limitExceeded == false.

        // If the new request's lat/lon span is not contained
        // entirely within the old one (fuzzy match)
        //  FALSE
    }
}

public class StopMapController extends BaseMapController implements
        LoaderManager.LoaderCallbacks<StopsResponse>,
        Loader.OnLoadCompleteListener<StopsResponse> {

    private static final String TAG = "StopMapController";

    private static final int STOPS_LOADER = 5678;
    private static final int STOPS_CACHE_LOADER = 5679;

    // In lieu of using an actual LoaderManager, which isn't
    // available in SherlockMapActivity
    private Loader<StopsResponse> mLoader;

    // In lieu of using an actual LoaderManager
    private Loader<StopsResponse> mCacheLoader;

    private MapWatcher mMapWatcher;

    /**
     * GoogleApiClient being used for Location Services
     */
    GoogleApiClient mGoogleApiClient;

    private boolean mFirstStopLoad;

    public StopMapController(Callback callback) {
        super(callback);

        mFirstStopLoad = true;
    }

    @Override
    protected void createLoader() {
        mLoader = onCreateLoader(STOPS_LOADER, null);
        mLoader.registerListener(0, this);
        mLoader.startLoading();
        mCacheLoader = onCreateCacheLoader(STOPS_CACHE_LOADER, null);
        mCacheLoader.registerListener(0, this);
        mCacheLoader.startLoading();
    }

    @Override
    public String getMode() {
        return MapParams.MODE_STOP;
    }

    @Override
    public void onHidden(boolean hidden) {
        // No op for this controller
    }

    @Override
    public void onMapZoomChanged() {
        mCallback.redrawStops();
    }

    @Override
    public Loader<StopsResponse> onCreateLoader(int id, Bundle args) {
        StopsLoader loader = new StopsLoader(mCallback);
        StopsRequest req = new StopsRequest(mCallback.getMapView());
        loader.update(req);
        return loader;
    }

    public Loader<StopsResponse> onCreateCacheLoader(int id, Bundle args) {
        StopsCacheLoader loader = new StopsCacheLoader(mCallback);
        StopsRequest req = new StopsRequest(mCallback.getMapView());
        loader.update(req);
        return loader;
    }

    protected StopsLoader getLoader() {
        //Loader<ObaStopsForLocationResponse> l =
        //        mCallback.getLoaderManager().getLoader(STOPS_LOADER);
        //return (StopsLoader)l;
        return (StopsLoader) mLoader;
    }

    protected StopsCacheLoader getCacheLoader() {
        //Loader<ObaStopsForLocationResponse> l =
        //        mCallback.getLoaderManager().getLoader(STOPS_LOADER);
        //return (StopsLoader)l;
        return (StopsCacheLoader) mCacheLoader;
    }

    @Override
    protected void updateData() {
        StopsCacheLoader cacheLoader = getCacheLoader();
        if (cacheLoader != null) {
            StopsRequest req = new StopsRequest(mCallback.getMapView());
            cacheLoader.update(req);
        }
        StopsLoader loader = getLoader();
        if (loader != null) {
            StopsRequest req = new StopsRequest(mCallback.getMapView());
            loader.update(req);
        }

    }
    @Override
    public void onLoadFinished(Loader<StopsResponse> loader,
                               StopsResponse _response) {
        mCallback.showProgress(false);
        final ObaStopsForLocationInterface response = _response.getResponse();

        if (response == null) {
            // Initial install can generate a null response if all is still ok, so do nothing (#615)
            return;
        }

        if (response.getCode() != ObaApi.OBA_OK) {
            BaseMapFragment.showMapError((ObaStopsForLocationResponse)response);
            return;
        }

        if (response.getOutOfRange()) {
            mCallback.notifyOutOfRange();
            return;
        }

        //Workaround for https://github.com/OneBusAway/onebusaway-application-modules/issues/59
        //where outOfRange response element is false even if the location was out of range
        //We need to also make sure the list of stops is empty, otherwise we screen out valid responses
        //TODO - After above issue #59 is resolved, we should also only do this check on OBA server
        //versions below the version number in which this is fixed.
        Location myLocation = Application.getLastKnownLocation(mCallback.getActivity(),
                mGoogleApiClient);
        if (myLocation != null && Application.get().getCurrentRegion() != null) {
            boolean inRegion = true;  // Assume user is in region unless we detect otherwise
            try {
                inRegion = RegionUtils
                        .isLocationWithinRegion(myLocation, Application.get().getCurrentRegion());
            } catch (IllegalArgumentException e) {
                // Issue #69 - some devices are providing invalid lat/long coordinates
                Log.e(TAG, "Invalid latitude or longitude - lat = " + myLocation.getLatitude()
                        + ", long = " + myLocation.getLongitude());
            }

            if (!inRegion && Arrays.asList(response.getStops()).isEmpty()) {
                Log.d(TAG, "Device location is outside region range, notifying...");
                mCallback.notifyOutOfRange();
                return;
            }
        }

        List<ObaStop> stops = Arrays.asList(response.getStops());
        mCallback.showStops(stops, (ObaReferences)response);

        if (mFirstStopLoad) {
            mFirstStopLoad = false;

            ObaStop preselectedStop = determinePreselectedStop(_response.getRequest().getCenter(), stops);
            if (preselectedStop != null) {
                mCallback.setFocusStop(preselectedStop);
            }
        }
    }

    @Override
    public void onLoaderReset(Loader<StopsResponse> loader) {
        // Clear the overlay.
        mCallback.showStops(null, null);
    }

    // Remove when adding back LoaderManager help.
    @Override
    public void onLoadComplete(Loader<StopsResponse> loader,
                               StopsResponse response) {
        onLoadFinished(loader, response);
    }

    private ObaStop determinePreselectedStop(Location center, List<ObaStop> stops) {
        Map<ObaStop, Float> distanceFromCenter = new ArrayMap<>(stops.size());
        for (ObaStop stop : stops) {
            if (stop.getParent() == null || stop.getParent().isEmpty()) {
                distanceFromCenter.put(stop, center.distanceTo(stop.getLocation()) / 4);
            } else {
                distanceFromCenter.put(stop, center.distanceTo(stop.getLocation()));
            }
        }

        Collections.sort(stops, (a, b) -> distanceFromCenter.get(a).compareTo(distanceFromCenter.get(b)));

        for (ObaStop stop : stops) {
            for (String routeId : stop.getRouteIds()) {
                if (ObaContract.RouteHeadsignFavorites.isFavorite(routeId, stop.getId())) {
                    return stop;
                }
            }
        }

        return null;
    }

    //
    // Loader
    //
    private static class StopsLoader extends AsyncTaskLoader<StopsResponse> {

        private final Callback mFragment;

        private StopsRequest mRequest;

        private StopsResponse mResponse;

        public StopsLoader(Callback fragment) {
            super(fragment.getActivity());
            mFragment = fragment;
        }

        @Override
        public StopsResponse loadInBackground() {
            StopsRequest req = mRequest;
            if (Application.get().getCurrentRegion() == null &&
                    TextUtils.isEmpty(Application.get().getCustomApiUrl())) {
                //We don't have region info or manually entered API to know what server to contact
                Log.d(TAG, "Trying to load stops from server without " +
                            "OBA REST API endpoint, aborting...");
                return new StopsResponse(req, null);
            }

            //Make OBA REST API call to the server and return result
            ObaStopsForLocationResponse response =
                    new ObaStopsForLocationRequest.Builder(getContext(),
                            req.getCenter())
                            .setSpan(req.getLatSpan(), req.getLonSpan())
                            .build()
                            .call();

            DBUtil.addStopsAndRoutesToDB(
                Application.get().getApplicationContext(),
                Arrays.asList(response.getStops()),
                response.getRoutes()
            );

            return new StopsResponse(req, response);
        }

        @Override
        public void deliverResult(StopsResponse data) {
            mResponse = data;
            super.deliverResult(data);
        }

        @Override
        public void onStartLoading() {
            if (takeContentChanged()) {
                forceLoad();
            }
        }

        @Override
        public void onForceLoad() {
            mFragment.showProgress(true);
            super.onForceLoad();
        }

        public void update(StopsRequest req) {
            if (mResponse == null || !mResponse.fulfills(req)) {
                mRequest = req;
                onContentChanged();
            }
        }
    }

    //
    // Loader
    //
    private static class StopsCacheLoader extends AsyncTaskLoader<StopsResponse> {

        private final Callback mFragment;

        private StopsRequest mRequest;

        public StopsCacheLoader(Callback fragment) {
            super(fragment.getActivity());
            mFragment = fragment;
        }

        @Override
        public StopsResponse loadInBackground() {
            StopsRequest req = mRequest;
            if (Application.get().getCurrentRegion() == null &&
                    TextUtils.isEmpty(Application.get().getCustomApiUrl())) {
                //We don't have region info or manually entered API to know what server to contact
                Log.d(TAG, "Trying to load stops from server without " +
                        "OBA REST API endpoint, aborting...");
                return new StopsResponse(req, null);
            }

            List<ObaStop> stops = DBUtil.queryStopsFromDB(getContext(), req.getCenter(), req.getLatSpan(), req.getLonSpan());
            List<ObaRoute> routes = DBUtil.queryRoutesFromDB(getContext(), stops);
            Log.i(TAG, "Cached stop data: " + stops.toString());
            Log.i(TAG, "Cached route data: " + routes.toString());

            ObaStopsForLocationCacheResponse response = new ObaStopsForLocationCacheResponse(
                stops.toArray(new ObaStop[]{}),  routes.toArray(new ObaRoute[]{})
            );

            return new StopsResponse(req, response);
        }

        @Override
        public void deliverResult(StopsResponse data) {
            super.deliverResult(data);
        }

        @Override
        public void onStartLoading() {
            if (takeContentChanged()) {
                forceLoad();
            }
        }

        @Override
        public void onForceLoad() {
            mFragment.showProgress(true);
            super.onForceLoad();
        }

        public void update(StopsRequest req) {
            mRequest = req;
            onContentChanged();
        }
    }
}
