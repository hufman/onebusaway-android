/*
 * Copyright (C) 2014 University of South Florida (sjbarbeau@gmail.com)
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
package org.onebusaway.android.map.googlemapsv2;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.Projection;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.onebusaway.android.BuildConfig;
import org.onebusaway.android.R;
import org.onebusaway.android.app.Application;
import org.onebusaway.android.io.elements.ObaReferences;
import org.onebusaway.android.io.elements.ObaRoute;
import org.onebusaway.android.io.elements.ObaStop;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.BounceInterpolator;
import android.view.animation.Interpolator;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;

public class StopOverlay implements MarkerListeners {

    private static final String TAG = "StopOverlay";

    private GoogleMap mMap;

    private MarkerData mMarkerData;

    private final Activity mActivity;

    private static final String NORTH = "N";

    private static final String NORTH_WEST = "NW";

    private static final String WEST = "W";

    private static final String SOUTH_WEST = "SW";

    private static final String SOUTH = "S";

    private static final String SOUTH_EAST = "SE";

    private static final String EAST = "E";

    private static final String NORTH_EAST = "NE";

    private static final String NO_DIRECTION = "null";

    private static final Map<String, Integer> directionToIndexMap = new HashMap<>();

    static {
        directionToIndexMap.put(NORTH, 0);
        directionToIndexMap.put(NORTH_WEST, 1);
        directionToIndexMap.put(WEST, 2);
        directionToIndexMap.put(SOUTH_WEST, 3);
        directionToIndexMap.put(SOUTH, 4);
        directionToIndexMap.put(SOUTH_EAST, 5);
        directionToIndexMap.put(EAST, 6);
        directionToIndexMap.put(NORTH_EAST, 7);
        directionToIndexMap.put(NO_DIRECTION, 8);
    }

    private static final int NUM_DIRECTIONS = 9; // 8 directions + undirected mStops

    private static Bitmap general_stop_dot;
    private static final Bitmap[] general_stop_icons = new Bitmap[NUM_DIRECTIONS];
    private static final Bitmap[] general_stop_icons_large = new Bitmap[NUM_DIRECTIONS];

    private static final Bitmap[] general_stop_icons_focused = new Bitmap[NUM_DIRECTIONS];
    private static final Bitmap[] transit_mode_icons = new Bitmap[ObaRoute.NUM_TYPES];

    private static final float FOCUS_ICON_SCALE = 1.5f;

    private static final float ICON_LARGE_ZOOM_LEVEL = 17f;

    private static final float ICON_ZOOM_LEVEL = 16f;

    private static final float STATION_LABEL_ZOOM_LEVEL = 14f;

    private static int mPx; // Bus stop icon size

    // Bus icon arrow attributes - by default assume we're not going to add a direction arrow
    private static float mArrowWidthPx = 0;

    private static float mArrowHeightPx = 0;

    private static float mBuffer = 0;  // Add this to the icon size to get the Bitmap size

    private static float mPercentOffset = 0.5f;
    // % offset to position the stop icon, so the selection marker hits the middle of the circle

    private static Paint mArrowPaintStroke;
    // Stroke color used for outline of directional arrows on stops

    OnFocusChangedListener mOnFocusChangedListener;

    @Override
    public boolean markerClicked(Marker marker) {
        long startTime = Long.MAX_VALUE, endTime = Long.MAX_VALUE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            startTime = SystemClock.elapsedRealtimeNanos();
        }

        ObaStop stop = mMarkerData.getStopFromMarker(marker);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            endTime = SystemClock.elapsedRealtimeNanos();
            Log.d(TAG, "Stop HashMap read time: " + TimeUnit.MILLISECONDS
                    .convert(endTime - startTime, TimeUnit.NANOSECONDS) + "ms");
        }

        if (stop == null) {
            // The marker isn't a stop that is contained in this StopOverlay - return unhandled
            return false;
        }

        if (BuildConfig.DEBUG) {
            // Show the stop_id in a toast for debug purposes
            Toast.makeText(mActivity, stop.getId(), Toast.LENGTH_SHORT).show();
        }

        doFocusChange(stop);

        return true;
    }

    @Override
    public void removeMarkerClicked(LatLng location) {
        Log.d(TAG, "Map clicked");
        removeFocus(location);
    }


    public interface OnFocusChangedListener {

        /**
         * Called when a stop on the map is clicked (i.e., tapped), which sets focus to a stop,
         * or when the user taps on an area away from the map for the first time after a stop
         * is already selected, which removes focus.  Clearly the focused stop can also be triggered
         * programmatically via a call to setFocus() with a stop of null - in that case, because
         * the user did not touch the map, location will be null.
         *
         * @param stop     the ObaStop that obtained focus, or null if no stop is in focus
         * @param routes   a HashMap of all route display names that serve this stop - key is
         *                 routeId
         * @param location the user touch location on the map, or null if the focus was changed
         *                 programmatically without the user tapping on the map
         */
        void onFocusChanged(ObaStop stop, HashMap<String, ObaRoute> routes, Location location);
    }

    public StopOverlay(Activity activity, GoogleMap map) {
        mActivity = activity;
        mMap = map;
        loadIcons();
    }

    public void setOnFocusChangeListener(OnFocusChangedListener onFocusChangedListener) {
        mOnFocusChangedListener = onFocusChangedListener;
    }

    public synchronized void populateStops(List<ObaStop> stops, ObaReferences refs) {
        populate(stops, refs.getRoutes());
    }

    public synchronized void populateStops(List<ObaStop> stops, List<ObaRoute> routes) {
        populate(stops, routes);
    }

    public synchronized void redrawStops() {
        Log.i(TAG, "Redrawing stops at zoom " + mMap.getCameraPosition().zoom);
        if (mMarkerData == null) return;
        for (Marker existingMarker : mMarkerData.mStopMarkers.values()) {
            ObaStop existingStop = mMarkerData.mStops.get(existingMarker);
            if (existingStop != null) {
                mMarkerData.updateMarkerIcon(existingStop, existingMarker);
                Marker existingModeMarker = mMarkerData.mStopModeMarkers.get(existingStop.getId());
                if (existingModeMarker != null) {
                    mMarkerData.updateMarkerModeIcon(existingStop, existingModeMarker);
                }
                Marker existingLabel = mMarkerData.mStopLabels.get(existingStop.getId());
                if (existingLabel != null) {
                    mMarkerData.updateMarkerLabel(existingLabel);
                }
            }
        }
    }

    private void populate(List<ObaStop> stops, List<ObaRoute> routes) {
        // Make sure that the MarkerData has been initialized
        setupMarkerData();
        mMarkerData.populate(stops, routes);
    }

    public synchronized int size() {
        if (mMarkerData != null) {
            return mMarkerData.size();
        } else {
            return 0;
        }
    }

    /**
     * Clears any stop markers from the map
     *
     * @param clearFocusedStop true to clear the currently focused stop, false to leave it on map
     */
    public synchronized void clear(boolean clearFocusedStop) {
        if (mMarkerData != null) {
            mMarkerData.clear(clearFocusedStop);
        }
    }

    /**
     * Cache the BitmapDescriptors that hold the images used for icons
     */
    private static final void loadIcons() {
        // Initialize variables used for all marker icons
        Resources r = Application.get().getResources();
        mPx = r.getDimensionPixelSize(R.dimen.map_stop_shadow_size_6);
        mArrowWidthPx = mPx / 2f; // half the stop icon size
        mArrowHeightPx = mPx / 3f; // 1/3 the stop icon size
        float arrowSpacingReductionPx = mPx / 10f;
        mBuffer = mArrowHeightPx - arrowSpacingReductionPx;

        // Set offset used to position the image for markers (see getX/YPercentOffsetForDirection())
        // This allows the current selection marker to land on the middle of the stop marker circle
        mPercentOffset = (mBuffer / (mPx + mBuffer)) * 0.5f;

        mArrowPaintStroke = new Paint();
        mArrowPaintStroke.setColor(Color.WHITE);
        mArrowPaintStroke.setStyle(Paint.Style.STROKE);
        mArrowPaintStroke.setStrokeWidth(1.0f);
        mArrowPaintStroke.setAntiAlias(true);

        String[] directions = {NORTH, NORTH_WEST, WEST, SOUTH_WEST, SOUTH, SOUTH_EAST, EAST, NORTH_EAST, NO_DIRECTION};
        for (int i = 0; i < directions.length; i++) {
            general_stop_icons[i] = createStopIcon(directions[i], false);
            general_stop_icons_focused[i] = createStopIcon(directions[i], true);
        }
        // Scale the focused icons to be larger than the normal icons
        for (int i = 0; i < NUM_DIRECTIONS; i++) {
            Bitmap bmp = general_stop_icons[i];
            general_stop_icons_large[i] = Bitmap.createScaledBitmap(bmp,
                    (int) (bmp.getWidth() * FOCUS_ICON_SCALE),
                    (int) (bmp.getHeight() * FOCUS_ICON_SCALE), true);
            Bitmap focused = general_stop_icons_focused[i];
            general_stop_icons_focused[i] = Bitmap.createScaledBitmap(focused,
                    (int) (focused.getWidth() * FOCUS_ICON_SCALE),
                    (int) (focused.getHeight() * FOCUS_ICON_SCALE), true);
        }
        general_stop_dot = createGeneralStopDot();
        transit_mode_icons[ObaRoute.TYPE_TRAM] = createModeIcon(R.drawable.ic_tram);
        transit_mode_icons[ObaRoute.TYPE_SUBWAY] = createModeIcon(R.drawable.ic_subway);
        transit_mode_icons[ObaRoute.TYPE_RAIL] = createModeIcon(R.drawable.ic_train);
        transit_mode_icons[ObaRoute.TYPE_BUS] = createModeIcon(R.drawable.ic_bus);
        transit_mode_icons[ObaRoute.TYPE_FERRY] = createModeIcon(R.drawable.ic_ferry);
    }

    private static Bitmap createGeneralStopDot() throws NullPointerException {

        Resources r = Application.get().getResources();
        Context context = Application.get();

        Bitmap bm;
        Canvas c;
        Drawable shape;

        int dotSize = (int)(mPx / 2.5);
        bm = Bitmap.createBitmap(dotSize, dotSize, Bitmap.Config.ARGB_8888);
        c = new Canvas(bm);
        shape = ContextCompat.getDrawable(context, R.drawable.map_stop_dot);
        if (shape != null) {
            shape.setBounds(0, 0, bm.getWidth(), bm.getHeight());
            shape.draw(c);
        }

        return bm;
    }

    /**
     * Creates a bus stop icon with the given direction arrow, or without a direction arrow if
     * the direction is NO_DIRECTION
     *
     * @param direction Bus stop direction, obtained from ObaStop.getDirection() and defined in
     *                  constants in this class, or NO_DIRECTION if the stop icon shouldn't have a
     *                  direction arrow
     * @return a bus stop icon bitmap with the arrow pointing the given direction, or with no arrow
     * if direction is NO_DIRECTION
     */
    private static Bitmap createStopIcon(String direction) throws NullPointerException {
        return createStopIcon(direction, false);
    }

    /**
     * Creates a bus stop icon with the given direction arrow, or without a direction arrow if
     * the direction is NO_DIRECTION
     *
     * @param direction Bus stop direction, obtained from ObaStop.getDirection() and defined in
     *                  constants in this class, or NO_DIRECTION if the stop icon shouldn't have a
     *                  direction arrow
     * @param selected  true to use the selected icon style, false for normal icon style
     * @return a bus stop icon bitmap with the arrow pointing the given direction, or with no arrow
     * if direction is NO_DIRECTION
     */
    private static Bitmap createStopIcon(String direction, boolean selected) throws NullPointerException {
        if (direction == null) {
            throw new IllegalArgumentException(direction);
        }

        Resources r = Application.get().getResources();
        Context context = Application.get();

        Float directionAngle = null;  // 0-360 degrees
        Bitmap bm;
        Canvas c;
        Drawable shape;
        Float rotationX = null, rotationY = null;  // Point around which to rotate the arrow

        Paint arrowPaintFill = new Paint();
        arrowPaintFill.setStyle(Paint.Style.FILL);
        arrowPaintFill.setAntiAlias(true);

        if (direction.equals(NO_DIRECTION)) {
            // Don't draw the arrow
            bm = Bitmap.createBitmap(mPx, mPx, Bitmap.Config.ARGB_8888);
            c = new Canvas(bm);
            shape = ContextCompat.getDrawable(context, selected ? R.drawable.selected_map_stop_icon : R.drawable.map_stop_icon);
            shape.setBounds(0, 0, bm.getWidth(), bm.getHeight());
        } else if (direction.equals(NORTH)) {
            directionAngle = 0f;
            bm = Bitmap.createBitmap(mPx, (int) (mPx + mBuffer), Bitmap.Config.ARGB_8888);
            c = new Canvas(bm);
            shape = ContextCompat.getDrawable(context, selected ? R.drawable.selected_map_stop_icon : R.drawable.map_stop_icon);
            shape.setBounds(0, (int) mBuffer, mPx, bm.getHeight());
            // Shade with darkest color at tip of arrow
            arrowPaintFill.setShader(
                    new LinearGradient(bm.getWidth() / 2, 0, bm.getWidth() / 2, mArrowHeightPx,
                            r.getColor(R.color.theme_primary), r.getColor(R.color.theme_accent),
                            Shader.TileMode.MIRROR));
            // For NORTH, no rotation occurs - use center of image anyway so we have some value
            rotationX = bm.getWidth() / 2f;
            rotationY = bm.getHeight() / 2f;
        } else if (direction.equals(NORTH_WEST)) {
            directionAngle = 315f;  // Arrow is drawn N, rotate 315 degrees
            bm = Bitmap.createBitmap((int) (mPx + mBuffer),
                    (int) (mPx + mBuffer), Bitmap.Config.ARGB_8888);
            c = new Canvas(bm);
            shape = ContextCompat.getDrawable(context, selected ? R.drawable.selected_map_stop_icon : R.drawable.map_stop_icon);
            shape.setBounds((int) mBuffer, (int) mBuffer, bm.getWidth(), bm.getHeight());
            // Shade with darkest color at tip of arrow
            arrowPaintFill.setShader(
                    new LinearGradient(0, 0, mBuffer, mBuffer,
                            r.getColor(R.color.theme_primary), r.getColor(R.color.theme_accent),
                            Shader.TileMode.MIRROR));
            // Rotate around below coordinates (trial and error)
            rotationX = mPx / 2f + mBuffer / 2f;
            rotationY = bm.getHeight() / 2f - mBuffer / 2f;
        } else if (direction.equals(WEST)) {
            directionAngle = 0f;  // Arrow is drawn pointing West, so no rotation
            bm = Bitmap.createBitmap((int) (mPx + mBuffer), mPx, Bitmap.Config.ARGB_8888);
            c = new Canvas(bm);
            shape = ContextCompat.getDrawable(context, selected ? R.drawable.selected_map_stop_icon : R.drawable.map_stop_icon);
            shape.setBounds((int) mBuffer, 0, bm.getWidth(), bm.getHeight());
            arrowPaintFill.setShader(
                    new LinearGradient(0, bm.getHeight() / 2, mArrowHeightPx, bm.getHeight() / 2,
                            r.getColor(R.color.theme_primary), r.getColor(R.color.theme_accent),
                            Shader.TileMode.MIRROR));
            // For WEST
            rotationX = bm.getHeight() / 2f;
            rotationY = bm.getHeight() / 2f;
        } else if (direction.equals(SOUTH_WEST)) {
            directionAngle = 225f;  // Arrow is drawn N, rotate 225 degrees
            bm = Bitmap.createBitmap((int) (mPx + mBuffer),
                    (int) (mPx + mBuffer), Bitmap.Config.ARGB_8888);
            c = new Canvas(bm);
            shape = ContextCompat.getDrawable(context, selected ? R.drawable.selected_map_stop_icon : R.drawable.map_stop_icon);
            shape.setBounds((int) mBuffer, 0, bm.getWidth(), mPx);
            arrowPaintFill.setShader(
                    new LinearGradient(0, bm.getHeight(), mBuffer, bm.getHeight() - mBuffer,
                            r.getColor(R.color.theme_primary), r.getColor(R.color.theme_accent),
                            Shader.TileMode.MIRROR));
            // Rotate around below coordinates (trial and error)
            rotationX = bm.getWidth() / 2f - mBuffer / 4f;
            rotationY = mPx / 2f + mBuffer / 4f;
        } else if (direction.equals(SOUTH)) {
            directionAngle = 180f;  // Arrow is drawn N, rotate 180 degrees
            bm = Bitmap.createBitmap(mPx, (int) (mPx + mBuffer), Bitmap.Config.ARGB_8888);
            c = new Canvas(bm);
            shape = ContextCompat.getDrawable(context, selected ? R.drawable.selected_map_stop_icon : R.drawable.map_stop_icon);
            shape.setBounds(0, 0, bm.getWidth(), (int) (bm.getHeight() - mBuffer));
            arrowPaintFill.setShader(
                    new LinearGradient(bm.getWidth() / 2, bm.getHeight(), bm.getWidth() / 2,
                            bm.getHeight() - mArrowHeightPx,
                            r.getColor(R.color.theme_primary), r.getColor(R.color.theme_accent),
                            Shader.TileMode.MIRROR));
            rotationX = bm.getWidth() / 2f;
            rotationY = bm.getHeight() / 2f;
        } else if (direction.equals(SOUTH_EAST)) {
            directionAngle = 135f;  // Arrow is drawn N, rotate 135 degrees
            bm = Bitmap.createBitmap((int) (mPx + mBuffer),
                    (int) (mPx + mBuffer), Bitmap.Config.ARGB_8888);
            c = new Canvas(bm);
            shape = ContextCompat.getDrawable(context, selected ? R.drawable.selected_map_stop_icon : R.drawable.map_stop_icon);
            shape.setBounds(0, 0, mPx, mPx);
            arrowPaintFill.setShader(
                    new LinearGradient(bm.getWidth(), bm.getHeight(), bm.getWidth() - mBuffer,
                            bm.getHeight() - mBuffer,
                            r.getColor(R.color.theme_primary), r.getColor(R.color.theme_accent),
                            Shader.TileMode.MIRROR));
            // Rotate around below coordinates (trial and error)
            rotationX = (mPx + mBuffer / 2) / 2f;
            rotationY = bm.getHeight() / 2f;
        } else if (direction.equals(EAST)) {
            directionAngle = 180f;  // Arrow is drawn pointing West, so rotate 180
            bm = Bitmap.createBitmap((int) (mPx + mBuffer), mPx, Bitmap.Config.ARGB_8888);
            c = new Canvas(bm);
            shape = ContextCompat.getDrawable(context, selected ? R.drawable.selected_map_stop_icon : R.drawable.map_stop_icon);
            shape.setBounds(0, 0, mPx, bm.getHeight());
            arrowPaintFill.setShader(
                    new LinearGradient(bm.getWidth(), bm.getHeight() / 2,
                            bm.getWidth() - mArrowHeightPx, bm.getHeight() / 2,
                            r.getColor(R.color.theme_primary), r.getColor(R.color.theme_accent),
                            Shader.TileMode.MIRROR));
            rotationX = bm.getWidth() / 2f;
            rotationY = bm.getHeight() / 2f;
        } else if (direction.equals(NORTH_EAST)) {
            directionAngle = 45f;  // Arrow is drawn pointing N, so rotate 45 degrees
            bm = Bitmap.createBitmap((int) (mPx + mBuffer),
                    (int) (mPx + mBuffer), Bitmap.Config.ARGB_8888);
            c = new Canvas(bm);
            shape = ContextCompat.getDrawable(context, selected ? R.drawable.selected_map_stop_icon : R.drawable.map_stop_icon);
            shape.setBounds(0, (int) mBuffer, mPx, bm.getHeight());
            // Shade with darkest color at tip of arrow
            arrowPaintFill.setShader(
                    new LinearGradient(bm.getWidth(), 0, bm.getWidth() - mBuffer, mBuffer,
                            r.getColor(R.color.theme_primary), r.getColor(R.color.theme_accent),
                            Shader.TileMode.MIRROR));
            // Rotate around middle of circle
            rotationX = (float) mPx / 2;
            rotationY = bm.getHeight() - (float) mPx / 2;
        } else {
            throw new IllegalArgumentException(direction);
        }

        shape.draw(c);

        if (direction.equals(NO_DIRECTION)) {
            // Everything after this point is for drawing the arrow image, so return the bitmap as-is for no arrow
            return bm;
        }

        /**
         * Draw the arrow - all dimensions should be relative to px so the arrow is drawn the same
         * size for all orientations
         */
        // Height of the cutout in the bottom of the triangle that makes it an arrow (0=triangle)
        final float CUTOUT_HEIGHT = mPx / 12;
        Path path = new Path();
        float x1 = 0, y1 = 0;  // Tip of arrow
        float x2 = 0, y2 = 0;  // lower left
        float x3 = 0, y3 = 0; // cutout in arrow bottom
        float x4 = 0, y4 = 0; // lower right

        if (direction.equals(NORTH) || direction.equals(SOUTH) ||
                direction.equals(NORTH_EAST) || direction.equals(SOUTH_EAST) ||
                direction.equals(NORTH_WEST) || direction.equals(SOUTH_WEST)) {
            // Arrow is drawn pointing NORTH
            // Tip of arrow
            x1 = mPx / 2;
            y1 = 0;

            // lower left
            x2 = (mPx / 2) - (mArrowWidthPx / 2);
            y2 = mArrowHeightPx;

            // cutout in arrow bottom
            x3 = mPx / 2;
            y3 = mArrowHeightPx - CUTOUT_HEIGHT;

            // lower right
            x4 = (mPx / 2) + (mArrowWidthPx / 2);
            y4 = mArrowHeightPx;
        } else if (direction.equals(EAST) || direction.equals(WEST)) {
            // Arrow is drawn pointing WEST
            // Tip of arrow
            x1 = 0;
            y1 = mPx / 2;

            // lower left
            x2 = mArrowHeightPx;
            y2 = (mPx / 2) - (mArrowWidthPx / 2);

            // cutout in arrow bottom
            x3 = mArrowHeightPx - CUTOUT_HEIGHT;
            y3 = mPx / 2;

            // lower right
            x4 = mArrowHeightPx;
            y4 = (mPx / 2) + (mArrowWidthPx / 2);
        }

        path.setFillType(Path.FillType.EVEN_ODD);
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.lineTo(x4, y4);
        path.lineTo(x1, y1);
        path.close();

        // Rotate arrow around (rotationX, rotationY) point
        Matrix matrix = new Matrix();
        matrix.postRotate(directionAngle, rotationX, rotationY);
        path.transform(matrix);

        c.drawPath(path, arrowPaintFill);
        c.drawPath(path, mArrowPaintStroke);

        return bm;
    }

    private static Rect fitRectInsideRect(Rect src, Rect dest) {
        if (src.width() > dest.width()) {
            float dx = ((float)dest.width() / src.width());
            int height = (int)(src.height() * dx);
            src.left = dest.left;
            src.right = dest.right;
            src.top = Math.max(0, dest.centerY() - height / 2);
            src.bottom = src.top + height;
        }
        if (src.height() > dest.height()) {
            float dy = (float)dest.height() / src.height();
            int width = (int)(src.width() * dy);
            src.top = dest.top;
            src.bottom = dest.bottom;
            src.left = Math.max(0, dest.centerX() - width / 2);
            src.right = src.left + width;
        }

        return src;
    }

    private static Bitmap createModeIcon(int iconId) {
        Resources r = Application.get().getResources();
        Context context = Application.get();

        Bitmap bm;
        Canvas c;
        Drawable icon;

        int iconSize = (int)(mPx * FOCUS_ICON_SCALE);

        bm = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);
        c = new Canvas(bm);
        icon = ContextCompat.getDrawable(context, iconId);

        if (icon != null) {
            Rect iconBounds = new Rect(0, 0, iconSize, iconSize);
            iconBounds.inset(8, 8);
            Rect drawableBounds = new Rect(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
            icon.setBounds(fitRectInsideRect(drawableBounds, iconBounds));
            icon.draw(c);
        }

        return bm;
    }

    /**
     * Gets the % X offset used for the bus stop icon, for the given direction
     *
     * @param direction Bus stop direction, obtained from ObaStop.getDirection() and defined in
     *                  constants in this class
     * @return percent offset X for the bus stop icon that should be used for that direction
     */
    private static float getXPercentOffsetForDirection(String direction) {
        if (direction.equals(NORTH)) {
            // Middle of icon
            return 0.5f;
        } else if (direction.equals(NORTH_WEST)) {
            return 0.5f + mPercentOffset;
        } else if (direction.equals(WEST)) {
            return 0.5f + mPercentOffset;
        } else if (direction.equals(SOUTH_WEST)) {
            return 0.5f + mPercentOffset;
        } else if (direction.equals(SOUTH)) {
            // Middle of icon
            return 0.5f;
        } else if (direction.equals(SOUTH_EAST)) {
            return 0.5f - mPercentOffset;
        } else if (direction.equals(EAST)) {
            return 0.5f - mPercentOffset;
        } else if (direction.equals(NORTH_EAST)) {
            return 0.5f - mPercentOffset;
        } else if (direction.equals(NO_DIRECTION)) {
            // Middle of icon
            return 0.5f;
        } else {
            // Assume middle of icon
            return 0.5f;
        }
    }

    /**
     * Gets the % Y offset used for the bus stop icon, for the given direction
     *
     * @param direction Bus stop direction, obtained from ObaStop.getDirection() and defined in
     *                  constants in this class
     * @return percent offset Y for the bus stop icon that should be used for that direction
     */
    private static float getYPercentOffsetForDirection(String direction) {
        if (direction.equals(NORTH)) {
            return 0.5f + mPercentOffset;
        } else if (direction.equals(NORTH_WEST)) {
            return 0.5f + mPercentOffset;
        } else if (direction.equals(WEST)) {
            // Middle of icon
            return 0.5f;
        } else if (direction.equals(SOUTH_WEST)) {
            return 0.5f - mPercentOffset;
        } else if (direction.equals(SOUTH)) {
            return 0.5f - mPercentOffset;
        } else if (direction.equals(SOUTH_EAST)) {
            return 0.5f - mPercentOffset;
        } else if (direction.equals(EAST)) {
            // Middle of icon
            return 0.5f;
        } else if (direction.equals(NORTH_EAST)) {
            return 0.5f + mPercentOffset;
        } else if (direction.equals(NO_DIRECTION)) {
            // Middle of icon
            return 0.5f;
        } else {
            // Assume middle of icon
            return 0.5f;
        }
    }

    /**
     * Returns the BitMapDescriptor for a particular bus stop icon, based on the stop direction
     *
     * @param direction Bus stop direction, obtained from ObaStop.getDirection() and defined in
     *                  constants in this class
     * @return BitmapDescriptor for the bus stop icon that should be used for that direction
     */
    private static BitmapDescriptor getBitmapDescriptorForBusStopDirection(String direction) {
        return getBitmapDescriptorForStopDirection(general_stop_icons, direction);
    }

    @NonNull
    private static BitmapDescriptor getFocusedBitmapDescriptorForBusStopDirection(String direction) {
        return getBitmapDescriptorForStopDirection(general_stop_icons_focused, direction);
    }

    @NonNull
    private static BitmapDescriptor getBitmapDescriptorForStopDirection(Bitmap[] icons, String direction) {
        Integer index = directionToIndexMap.get(direction);
        if (index == null) {
            index = 8;
        }
        return BitmapDescriptorFactory.fromBitmap(icons[index]);
    }

    /**
     * Returns the currently focused stop, or null if no stop is in focus
     *
     * @return the currently focused stop, or null if no stop is in focus
     */
    public ObaStop getFocus() {
        if (mMarkerData != null) {
            return mMarkerData.getFocus();
        }

        return null;
    }

    public void setFocus(ObaStop stop) {
        doFocusChange(stop);
    }

    /**
     * Sets focus to a particular stop, or pass in null for the stop to clear the focus
     *
     * @param stop   ObaStop to focus on, or null to clear the focus
     * @param routes a list of all route display names that serve this stop
     */
    public void setFocus(ObaStop stop, List<ObaRoute> routes) {
        // Make sure that the MarkerData has been initialized
        setupMarkerData();

        if (stop == null) {
            // Clear the focus
            removeFocus(null);
            return;
        }

        /**
         * If mMarkerData exists before this method is called, the stop reference passed into this
         * method might not match any existing stop reference in our HashMaps, since this stop came
         * from an external REST API call - is this a problem???
         *
         * If so, we'll need to keep another HashMap mapping stopIds to ObaStops so we can pull out
         * an internal reference to an ObaStop object that has the same stopId as the ObaStop object
         * passed into this method.  Then, we would use that internal reference in place of the
         * ObaStop passed into this method.  We don't want to maintain Yet Another HashMap for
         * memory/performance reasons if we don't have to.  For now, I think we can get away with
         * a separate reference that doesn't match the internal HashMaps, since we don't need to
         * match the references.
         */

        /**
         * Make sure that this stop is added to the overlay.  If an intent/orientation change started
         * the map fragment to focus on a stop, no markers may exist on the map
         */
        if (!mMarkerData.containsStop(stop)) {
            ArrayList<ObaStop> l = new ArrayList<ObaStop>();
            l.add(stop);
            populateStops(l, routes);
        }

        // Add the focus marker to the map by setting focus to this stop
        doFocusChange(stop);
    }


    private void doFocusChange(ObaStop stop) {
        mMarkerData.setFocus(stop);
        HashMap<String, ObaRoute> routes = mMarkerData.getCachedRoutes();

        // Notify listener
        mOnFocusChangedListener.onFocusChanged(stop, routes, stop.getLocation());
    }

    /**
     * Removes the stop focus and notify listener
     *
     * @param latLng the location on the map where the user tapped if the focus change was
     *               triggered
     *               by the user tapping on the map, or null if the focus change was otherwise
     *               triggered programmatically.
     */
    private void removeFocus(LatLng latLng) {
        if (mMarkerData.getFocus() != null) {
            mMarkerData.removeFocus();
        }

        // Set map clicked location, if it exists
        Location location = null;
        if (latLng != null) {
            location = MapHelpV2.makeLocation(latLng);
        }
        // Notify focus changed every time the map is clicked away from a stop marker
        mOnFocusChangedListener.onFocusChanged(null, null, location);
    }

    private void setupMarkerData() {
        if (mMarkerData == null) {
            mMarkerData = new MarkerData();
        }
    }

    /**
     * Data structures to track what stops/markers are currently shown on the map
     */
    class MarkerData {

        /**
         * Stops-for-location REST API endpoint returns 100 markers per call by default
         * (see http://goo.gl/tzvrLb), so we'll support showing max results of around 2 calls
         * before
         * we completely clear the map and start over.  Note that this is a fuzzy max, since we
         * don't
         * want to clear the overlay in the middle of processing an API response and remove markers
         * in
         * the current view
         */
        private static final int FUZZY_MAX_MARKER_COUNT = 200;

        /**
         * A cached set of markers currently shown on the map, up to roughly
         * FUZZY_MAX_MARKER_COUNT in size.  This is needed to add/remove markers from the map.
         * StopId is the key.
         */
        private HashMap<String, Marker> mStopMarkers;

        /**
         * A cached set of mode markers currently shown on the map, up to roughly
         * FUZZY_MAX_MARKER_COUNT in size.  This is needed to add/remove markers from the map.
         * StopId is the key.
         */
        private HashMap<String, Marker> mStopModeMarkers;

        /**
         * A cached set of labels currently shown on the map, up to roughly
         * FUZZY_MAX_MARKER_COUNT in size.  This is needed to add/remove markers from the map.
         * StopId is the key.
         */
        private HashMap<String, Marker> mStopLabels;

        /**
         * A cached set of ObaStops that are currently shown on the map, up to roughly
         * FUZZY_MAX_MARKER_COUNT in size.  Since onMarkerClick() provides a marker, we need a
         * mapping of that marker to the ObaStop.
         * Marker that represents an ObaStop is the key.
         */
        private HashMap<Marker, ObaStop> mStops;

        /**
         * A cached set of ObaRoutes that serve the currently cached ObaStops.  This is
         * needed to retrieve the route display names that serve a particular stop.
         * RouteId is the key.
         */
        private HashMap<String, ObaRoute> mStopRoutes;

        /**
         * Marker and stop used to indicate which bus stop has focus (i.e., was last
         * clicked/tapped). The marker reference points to the stop marker itself.
         */
        private Marker mCurrentFocusMarker;
        private Marker mCurrentFocusModeMarker;

        private ObaStop mCurrentFocusStop;

        /**
         * Keep a copy of ObaRoute references for stops have have had focus, so we can reconstruct
         * the mStopRoutes HashMap after clearing the cache
         */
        private List<ObaRoute> mFocusedRoutes;

        MarkerData() {
            mStopMarkers = new HashMap<String, Marker>();
            mStopModeMarkers = new HashMap<String, Marker>();
            mStopLabels = new HashMap<String, Marker>();
            mStops = new HashMap<Marker, ObaStop>();
            mStopRoutes = new HashMap<String, ObaRoute>();
            mFocusedRoutes = new LinkedList<ObaRoute>();
        }

        synchronized void populate(List<ObaStop> stops, List<ObaRoute> routes) {
            int count = 0;

            if (mStopMarkers.size() >= FUZZY_MAX_MARKER_COUNT) {
                // We've exceed our max, so clear the current marker cache and start over
                Log.d(TAG, "Exceed max marker cache of " + FUZZY_MAX_MARKER_COUNT
                        + ", clearing cache");
                removeMarkersFromMap();
                mStopMarkers.clear();
                mStopModeMarkers.clear();
                mStopLabels.clear();
                mStops.clear();

                // Make sure the currently focused stop still exists on the map
                if (mCurrentFocusStop != null && mFocusedRoutes != null) {
                    addMarkerToMap(mCurrentFocusStop, mFocusedRoutes);
                    count++;
                }
            }

            for (ObaStop stop : stops) {
                Marker existingMarker = mStopMarkers.get(stop.getId());

                if (existingMarker == null) {
                    addMarkerToMap(stop, routes);
                    count++;
                } else if (existingMarker != mCurrentFocusMarker) {
                    updateMarkerIcon(stop, existingMarker);
                }

                Marker existingModeMarker = mStopModeMarkers.get(stop.getId());
                if (existingModeMarker != null) {
                    updateMarkerModeIcon(stop, existingModeMarker);
                }

                Marker existingLabel = mStopLabels.get(stop.getId());
                if (existingLabel != null) {
                    updateMarkerLabel(existingLabel);
                }
            }

            Log.d(TAG, "Added " + count + " markers, total markers = " + mStopMarkers.size());
        }

        /**
         * Places a marker on the map for this stop, and adds it to our marker HashMap
         *
         * @param stop   ObaStop that should be shown on the map
         * @param routes A list of ObaRoutes that serve this stop
         */
        private synchronized void addMarkerToMap(ObaStop stop, List<ObaRoute> routes) {
            for (ObaRoute route : routes) {
                // ObaRoutes may have already been added for other stops, so check before adding
                if (!mStopRoutes.containsKey(route.getId())) {
                    mStopRoutes.put(route.getId(), route);
                }
            }

            float ZINDEX_RANGE = 0.1f;
            float ZINDEX_CIRCLE = 1.0f;
            float ZINDEX_MODE = 1.2f;
            float ZINDEX_STATION_RAISE = 0.5f;
            float ZINDEX_PLATFORM_LABEL = 0.7f;
            float ZINDEX_STATION_LABEL = 0.1f;

            // Stations are lifted above stops
            float zIndexStop = ZINDEX_RANGE / Math.abs(stop.getId().hashCode());
            boolean isStation = stop.getParent() != null && stop.getParent().isEmpty();
            if (isStation) {
                zIndexStop += ZINDEX_STATION_RAISE;
            }

            // Determine icon within synchronized block to prevent race condition with focus changes
            BitmapDescriptor icon = getMarkerIcon(stop);

            Marker m = mMap.addMarker(new MarkerOptions()
                    .position(MapHelpV2.makeLatLng(stop.getLocation()))
                    .icon(icon)
                    .flat(true)
                    .zIndex(ZINDEX_CIRCLE + zIndexStop)
                    .anchor(getXPercentOffsetForDirection(stop.getDirection()),
                            getYPercentOffsetForDirection(stop.getDirection()))
            );
            mStopMarkers.put(stop.getId(), m);
            mStops.put(m, stop);

            BitmapDescriptor iconMode = getMarkerModeIcon(stop);
            Marker mMode = mMap.addMarker(new MarkerOptions()
                    .position(MapHelpV2.makeLatLng(stop.getLocation()))
                    .icon(iconMode)
                    .zIndex(ZINDEX_MODE + zIndexStop)
                    .anchor(0.5f, 0.5f)
            );
            mStopModeMarkers.put(stop.getId(), mMode);
            mStops.put(mMode, stop);

            if (stop.getPlatformCode() != null && !stop.getPlatformCode().isEmpty()) {
                Marker label = mMap.addMarker(new MarkerOptions()
                    .position(MapHelpV2.makeLatLng(stop.getLocation()))
                    .icon(getMarkerPlatformLabel(stop.getPlatformCode()))
                    .zIndex(ZINDEX_PLATFORM_LABEL + zIndexStop)
                    .anchor(0.5f, 1f)
                );
                mStopLabels.put(stop.getId(), label);
                mStops.put(label, stop);
            } else if (stop.getParent() == null || stop.getParent().isEmpty()) {
                Marker label = mMap.addMarker(new MarkerOptions()
                        .position(MapHelpV2.makeLatLng(stop.getLocation()))
                        .icon(getMarkerStationLabel(stop.getName()))
                        .zIndex(ZINDEX_STATION_LABEL + zIndexStop)
                        .anchor(0.5f, 1f)
                );
                mStopLabels.put(stop.getId(), label);
                mStops.put(label, stop);
            }
        }

        private void updateMarkerIcon(ObaStop stop, Marker m) {
            m.setIcon(getMarkerIcon(stop));
            m.setAnchor(getXPercentOffsetForDirection(stop.getDirection()),
                        getYPercentOffsetForDirection(stop.getDirection()));
        }

        private void updateMarkerModeIcon(ObaStop stop, Marker m) {
            boolean isFocused = mCurrentFocusStop != null && stop.getId().equals(mCurrentFocusStop.getId());
            if (isFocused) {
                m.setVisible(false);
            } else {
                m.setVisible(mMap.getCameraPosition().zoom > ICON_LARGE_ZOOM_LEVEL);
            }
        }

        private void updateMarkerLabel(Marker m) {
            if (m.getZIndex() < 0.7f) {
                // Station label
                m.setVisible(mMap.getCameraPosition().zoom > STATION_LABEL_ZOOM_LEVEL);
            } else {
                m.setVisible(mMap.getCameraPosition().zoom > ICON_ZOOM_LEVEL);
            }
        }

        private BitmapDescriptor getMarkerIcon(ObaStop stop) {
            boolean isFocused = mCurrentFocusStop != null && stop.getId().equals(mCurrentFocusStop.getId());
            Bitmap[] icons;

            if (mMap.getCameraPosition().zoom > ICON_LARGE_ZOOM_LEVEL) {
                icons = isFocused ? general_stop_icons_focused : general_stop_icons_large;
                return getBitmapDescriptorForStopDirection(icons, stop.getDirection());
            } else if (mMap.getCameraPosition().zoom > ICON_ZOOM_LEVEL) {
                icons = isFocused ? general_stop_icons_focused : general_stop_icons;
                return getBitmapDescriptorForStopDirection(icons, stop.getDirection());
            } else if (stop.getParent() != null && stop.getParent().isEmpty()) {
                // Station, never show small dot
                icons = isFocused ? general_stop_icons_focused : general_stop_icons;
                return getBitmapDescriptorForStopDirection(icons, stop.getDirection());
            } else if (isFocused) {
                return getBitmapDescriptorForStopDirection(general_stop_icons_focused, stop.getDirection());
            } else {
                return BitmapDescriptorFactory.fromBitmap(general_stop_dot);
            }
        }

        private BitmapDescriptor getMarkerModeIcon(ObaStop stop) {
            List<ObaRoute> routes = new ArrayList<>(stop.getRouteIds().length);
            for (String routeId : stop.getRouteIds()) {
                if (mStopRoutes.containsKey(routeId)) {
                    routes.add(mStopRoutes.get(routeId));
                }
            }
            Set<Integer> routeTypes = getRouteTypes(routes);
            if (routeTypes.contains(ObaRoute.TYPE_RAIL)) {
                return BitmapDescriptorFactory.fromBitmap(transit_mode_icons[ObaRoute.TYPE_RAIL]);
            } else if (routeTypes.contains(ObaRoute.TYPE_SUBWAY)) {
                return BitmapDescriptorFactory.fromBitmap(transit_mode_icons[ObaRoute.TYPE_SUBWAY]);
            } else if (routeTypes.contains(ObaRoute.TYPE_TRAM)) {
                return BitmapDescriptorFactory.fromBitmap(transit_mode_icons[ObaRoute.TYPE_TRAM]);
            } else if (routeTypes.contains(ObaRoute.TYPE_BUS)) {
                return BitmapDescriptorFactory.fromBitmap(transit_mode_icons[ObaRoute.TYPE_BUS]);
            } else if (routeTypes.contains(ObaRoute.TYPE_FERRY)) {
                return BitmapDescriptorFactory.fromBitmap(transit_mode_icons[ObaRoute.TYPE_FERRY]);
            } else {
                return null;
            }
        }

        private Set<Integer> getRouteTypes(List<ObaRoute> routes) {
            Set<Integer> routeTypes = new HashSet<>(ObaRoute.NUM_TYPES);
            for (ObaRoute route : routes) {
                routeTypes.add(route.getType());
            }

            return routeTypes;
        }

        private BitmapDescriptor getMarkerPlatformLabel(String platformCode)
        {
            final float scale = Application.get().getResources().getDisplayMetrics().density;
            int size = (int)(24 * scale + 0.5f);

            Bitmap bitmap = Bitmap.createBitmap(size, size * 2, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            TextView labelView = new TextView(Application.get());
            labelView.setText(platformCode);
            labelView.layout(0, 0, size, size);
            labelView.setGravity(Gravity.CENTER);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            labelView.setTypeface(Typeface.DEFAULT_BOLD);
            labelView.setBackground(AppCompatResources.getDrawable(Application.get(), R.drawable.border_platform_info));

            canvas.save();
            labelView.draw(canvas);
            canvas.restore();

            return BitmapDescriptorFactory.fromBitmap(bitmap);
        }

        private BitmapDescriptor getMarkerStationLabel(String name)
        {
            TextView labelView = new TextView(Application.get());
            labelView.setText(name);
            labelView.setGravity(Gravity.CENTER);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            labelView.setTypeface(Typeface.DEFAULT_BOLD);
            float width = labelView.getPaint().measureText(name);
            labelView.layout(0, 0, (int)width, 50);

            Bitmap bitmap = Bitmap.createBitmap((int)width, 50 * 2, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            canvas.save();
            labelView.draw(canvas);
            canvas.restore();

            return BitmapDescriptorFactory.fromBitmap(bitmap);
        }

        synchronized ObaStop getStopFromMarker(Marker marker) {
            return mStops.get(marker);
        }

        /**
         * Returns true if this overlay contains the provided ObaStop
         *
         * @param stop ObaStop to check for
         * @return true if this overlay contains the provided ObaStop, false if it does not
         */
        synchronized boolean containsStop(ObaStop stop) {
            if (stop != null) {
                return containsStop(stop.getId());
            } else {
                return false;
            }
        }

        /**
         * Returns true if this overlay contains the provided stopId
         *
         * @param stopId stopId to check for
         * @return true if this overlay contains the provided stopId, false if it does not
         */
        synchronized boolean containsStop(String stopId) {
            if (mStopMarkers != null) {
                return mStopMarkers.containsKey(stopId);
            } else {
                return false;
            }
        }

        /**
         * Gets the ObaRoute objects that have been cached
         *
         * @return a copy of the HashMap containing the ObaRoutes that have been cached, with the
         * routeId as key
         */
        synchronized HashMap<String, ObaRoute> getCachedRoutes() {
            return new HashMap<String, ObaRoute>(mStopRoutes);
        }

        /**
         * Sets the current focus to a particular stop
         *
         * @param stop ObaStop that should have focus
         */
        synchronized void setFocus(ObaStop stop) {
            if (stop == null) {
                removeFocus();
                return;
            }

            if (mCurrentFocusMarker != null && mCurrentFocusStop != null) {
                // Get the current marker from cache in case the old reference is stale
                ObaStop previousFocusStop = mCurrentFocusStop;
                Marker currentMarker = mStopMarkers.get(previousFocusStop.getId());
                Marker currentModeMarker = mStopModeMarkers.get(previousFocusStop.getId());

                // Restore previous marker icon
                mCurrentFocusStop = null;
                if (currentMarker != null) {
                    updateMarkerIcon(previousFocusStop, currentMarker);
                }
                if (currentModeMarker != null) {
                    updateMarkerModeIcon(previousFocusStop, currentModeMarker);
                }
            }
            mCurrentFocusStop = stop;
            mCurrentFocusMarker = mStopMarkers.get(stop.getId());
            mCurrentFocusModeMarker = mStopModeMarkers.get(stop.getId());

            // Check if the marker exists in our cache before proceeding
            if (mCurrentFocusMarker == null) {
                mCurrentFocusStop = null;
                return;
            }

            // Save a copy of ObaRoute references for this stop, so we have them when clearing cache
            mFocusedRoutes.clear();
            String[] routeIds = stop.getRouteIds();
            for (int i = 0; i < routeIds.length; i++) {
                ObaRoute route = mStopRoutes.get(routeIds[i]);
                if (route != null) {
                    mFocusedRoutes.add(route);
                }
            }


            updateMarkerIcon(stop, mCurrentFocusMarker);
            updateMarkerModeIcon(stop, mCurrentFocusModeMarker);
        }

        /**
         * Give the marker a slight bounce effect
         *
         * @param marker marker to animate
         */
        private void animateMarker(final Marker marker) {
            final Handler handler = new Handler();

            final long startTime = SystemClock.uptimeMillis();
            final long duration = 300; // ms

            Projection proj = mMap.getProjection();
            final LatLng markerLatLng = marker.getPosition();
            Point startPoint = proj.toScreenLocation(markerLatLng);
            startPoint.offset(0, -10);
            final LatLng startLatLng = proj.fromScreenLocation(startPoint);

            final Interpolator interpolator = new BounceInterpolator();

            handler.post(new Runnable() {
                @Override
                public void run() {
                    long elapsed = SystemClock.uptimeMillis() - startTime;
                    float t = interpolator.getInterpolation((float) elapsed / duration);
                    double lng = t * markerLatLng.longitude + (1 - t) * startLatLng.longitude;
                    double lat = t * markerLatLng.latitude + (1 - t) * startLatLng.latitude;
                    marker.setPosition(new LatLng(lat, lng));

                    if (t < 1.0) {
                        // Post again 16ms later (60fps)
                        handler.postDelayed(this, 16);
                    }
                }
            });
        }

        /**
         * Returns the last focused stop, or null if no stop is in focus
         *
         * @return last focused stop, or null if no stop is in focus
         */
        ObaStop getFocus() {
            return mCurrentFocusStop;
        }

        /**
         * Remove focus of a stop on the map
         */
        synchronized void removeFocus() {
            if (mCurrentFocusMarker != null && mCurrentFocusStop != null) {
                // Get the current marker from cache in case the old reference is stale
                Marker currentMarker = mStopMarkers.get(mCurrentFocusStop.getId());
                if (currentMarker != null) {
                    currentMarker.setIcon(getBitmapDescriptorForBusStopDirection(
                            mCurrentFocusStop.getDirection()));
                }
                mCurrentFocusMarker = null;
            }
            mFocusedRoutes.clear();
            mCurrentFocusStop = null;
        }

        private void removeMarkersFromMap() {
            for (Map.Entry<String, Marker> entry : mStopMarkers.entrySet()) {
                entry.getValue().remove();
            }
            for (Map.Entry<String, Marker> entry : mStopModeMarkers.entrySet()) {
                entry.getValue().remove();
            }
            for (Map.Entry<String, Marker> entry : mStopLabels.entrySet()) {
                entry.getValue().remove();
            }
        }

        /**
         * Clears any stop markers from the map
         *
         * @param clearFocusedStop true to clear the currently focused stop, false to leave it on map
         */
        synchronized void clear(boolean clearFocusedStop) {
            if (mStopMarkers != null) {
                // Clear all markers from the map
                removeMarkersFromMap();

                // Clear the data structures
                mStopMarkers.clear();
                mStopModeMarkers.clear();
                mStopLabels.clear();
            }
            if (mStops != null) {
                mStops.clear();
            }
            if (mStopRoutes != null) {
                mStopRoutes.clear();
            }
            if (clearFocusedStop) {
                removeFocus();
            } else {
                // Make sure the currently focused stop still exists on the map
                if (mCurrentFocusStop != null && mFocusedRoutes != null) {
                    addMarkerToMap(mCurrentFocusStop, mFocusedRoutes);
                }
            }
        }

        synchronized int size() {
            return mStopMarkers.size();
        }
    }

//    @Override
//    public boolean onTrackballEvent(MotionEvent event, MapView view) {
//        final int action = event.getAction();
//        OverlayItem next = null;
//        //Log.d(TAG, "MotionEvent: " + event);
//
//        if (action == MotionEvent.ACTION_MOVE) {
//            final float xDiff = event.getX();
//            final float yDiff = event.getY();
//            // Up
//            if (yDiff <= -1) {
//                next = findNext(getFocus(), true, true);
//            }
//            // Down
//            else if (yDiff >= 1) {
//                next = findNext(getFocus(), true, false);
//            }
//            // Right
//            else if (xDiff >= 1) {
//                next = findNext(getFocus(), false, true);
//            }
//            // Left
//            else if (xDiff <= -1) {
//                next = findNext(getFocus(), false, false);
//            }
//            if (next != null) {
//                setFocus(next);
//                view.postInvalidate();
//            }
//        } else if (action == MotionEvent.ACTION_UP) {
//            final OverlayItem focus = getFocus();
//            if (focus != null) {
//                ArrivalsListActivity.start(mActivity, ((StopOverlayItem) focus).getStop());
//            }
//        }
//        return true;
//    }

//    @Override
//    public boolean onKeyDown(int keyCode, KeyEvent event, MapView view) {
//        //Log.d(TAG, "KeyEvent: " + event);
//        OverlayItem next = null;
//        switch (keyCode) {
//            case KeyEvent.KEYCODE_DPAD_UP:
//                next = findNext(getFocus(), true, true);
//                break;
//            case KeyEvent.KEYCODE_DPAD_DOWN:
//                next = findNext(getFocus(), true, false);
//                break;
//            case KeyEvent.KEYCODE_DPAD_RIGHT:
//                next = findNext(getFocus(), false, true);
//                break;
//            case KeyEvent.KEYCODE_DPAD_LEFT:
//                next = findNext(getFocus(), false, false);
//                break;
//            case KeyEvent.KEYCODE_DPAD_CENTER:
//                final OverlayItem focus = getFocus();
//                if (focus != null) {
//                    ArrivalsListActivity.start(mActivity, ((StopOverlayItem) focus).getStop());
//                }
//                break;
//            default:
//                return false;
//        }
//        if (next != null) {
//            setFocus(next);
//            view.postInvalidate();
//        }
//        return true;
//    }

//    boolean setFocusById(String id) {
//        final int size = size();
//        for (int i = 0; i < size; ++i) {
//            StopOverlayItem item = (StopOverlayItem) getItem(i);
//            if (id.equals(item.getStop().getId())) {
//                setFocus(item);
//                return true;
//            }
//        }
//        return false;
//    }
//
//    String getFocusedId() {
//        final OverlayItem focus = getFocus();
//        if (focus != null) {
//            return ((StopOverlayItem) focus).getStop().getId();
//        }
//        return null;
//    }

//    @Override
//    protected boolean onTap(int index) {
//        final OverlayItem item = getItem(index);
//        if (item.equals(getFocus())) {
//            ObaStop stop = mStops.get(index);
//            ArrivalsListActivity.start(mActivity, stop);
//        } else {
//            setFocus(item);
//            // fix odd behavior where previously selected item is not re-highlighted
//            setLastFocusedIndex(-1);
//        }
//        return true;
//    }

    // The find next routines find the closest item along the specified axis.

//    OverlayItem findNext(OverlayItem initial, boolean lat, boolean positive) {
//        if (initial == null) {
//            return null;
//        }
//        final int size = size();
//        final GeoPoint initialPoint = initial.getPoint();
//        OverlayItem min = initial;
//        int minDist = Integer.MAX_VALUE;
//
//        for (int i = 0; i < size; ++i) {
//            OverlayItem item = getItem(i);
//            GeoPoint point = item.getPoint();
//            final int distX = point.getLongitudeE6() - initialPoint.getLongitudeE6();
//            final int distY = point.getLatitudeE6() - initialPoint.getLatitudeE6();
//
//            // We have to eliminate anything that's going in the wrong direction,
//            // or doesn't change in the correct axis (including the initial point)
//            if (lat) {
//                if (positive) {
//                    // Distance must be positive.
//                    if (distY <= 0) {
//                        continue;
//                    }
//                }
//                // Distance must to be negative.
//                else if (distY >= 0) {
//                    continue;
//                }
//            } else {
//                if (positive) {
//                    // Distance must be positive
//                    if (distX <= 0) {
//                        continue;
//                    }
//                }
//                // Distance must be negative
//                else if (distX >= 0) {
//                    continue;
//                }
//            }
//
//            final int distSq = distX * distX + distY * distY;
//
//            if (distSq < minDist) {
//                min = item;
//                minDist = distSq;
//            }
//        }
//        return min;
//    }
}
