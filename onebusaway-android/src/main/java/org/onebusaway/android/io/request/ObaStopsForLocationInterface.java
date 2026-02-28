package org.onebusaway.android.io.request;

import org.onebusaway.android.io.elements.ObaStop;

public interface ObaStopsForLocationInterface {

    /**
     * @return The list of stops.
     */
    public ObaStop[] getStops();

    /**
     * @return The status code (one of the ObaApi.OBA_ constants)
     */
    public int getCode();

    /**
     * @return Whether the request is out of range of the coverage area.
     */
    public boolean getOutOfRange();

    /**
     * @return Whether the results exceeded the limits of the response.
     */
    public boolean getLimitExceeded();

}
