package de.gabriel.ankunftsalarm;

import android.content.Context;
import android.content.SharedPreferences;

/** Single source of truth for the selected destination and alarm state. */
public final class AppState {
    private static final String FILE = "arrival_alarm_state";
    private static final String HAS_DESTINATION = "has_destination";
    private static final String LATITUDE_BITS = "latitude_bits";
    private static final String LONGITUDE_BITS = "longitude_bits";
    private static final String RADIUS_METERS = "radius_meters";
    private static final String MONITORING = "monitoring";
    private static final String SOUND_ENABLED = "sound_enabled";
    private static final String VIBRATION_ENABLED = "vibration_enabled";
    private static final String LOCATION_SOURCE_MODE = "location_source_mode";

    public static final int MIN_RADIUS_METERS = 100;
    public static final int MAX_RADIUS_METERS = 3000;
    public static final int DEFAULT_RADIUS_METERS = 500;
    public static final int LOCATION_SOURCE_AUTOMATIC = 0;
    public static final int LOCATION_SOURCE_GPS = 1;
    public static final int LOCATION_SOURCE_NETWORK = 2;

    private final SharedPreferences preferences;

    public AppState(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean hasDestination() {
        return preferences.getBoolean(HAS_DESTINATION, false);
    }

    public double latitude() {
        return Double.longBitsToDouble(preferences.getLong(LATITUDE_BITS, 0L));
    }

    public double longitude() {
        return Double.longBitsToDouble(preferences.getLong(LONGITUDE_BITS, 0L));
    }

    public int radiusMeters() {
        return clampRadius(preferences.getInt(RADIUS_METERS, DEFAULT_RADIUS_METERS));
    }

    public boolean isMonitoring() {
        return preferences.getBoolean(MONITORING, false);
    }

    public boolean isSoundEnabled() {
        return preferences.getBoolean(SOUND_ENABLED, true);
    }

    public boolean isVibrationEnabled() {
        return preferences.getBoolean(VIBRATION_ENABLED, true);
    }

    public int locationSourceMode() {
        return clampLocationSource(preferences.getInt(
                LOCATION_SOURCE_MODE,
                LOCATION_SOURCE_AUTOMATIC
        ));
    }

    public void saveDestination(double latitude, double longitude) {
        preferences.edit()
                .putBoolean(HAS_DESTINATION, true)
                .putLong(LATITUDE_BITS, Double.doubleToRawLongBits(latitude))
                .putLong(LONGITUDE_BITS, Double.doubleToRawLongBits(longitude))
                .putBoolean(MONITORING, false)
                .apply();
    }

    public void setRadiusMeters(int meters) {
        preferences.edit().putInt(RADIUS_METERS, clampRadius(meters)).apply();
    }

    public void setMonitoring(boolean monitoring) {
        preferences.edit().putBoolean(MONITORING, monitoring).apply();
    }

    public void setSoundEnabled(boolean enabled) {
        preferences.edit().putBoolean(SOUND_ENABLED, enabled).apply();
    }

    public void setVibrationEnabled(boolean enabled) {
        preferences.edit().putBoolean(VIBRATION_ENABLED, enabled).apply();
    }

    public void setLocationSourceMode(int mode) {
        preferences.edit()
                .putInt(LOCATION_SOURCE_MODE, clampLocationSource(mode))
                .apply();
    }

    public void clearDestination() {
        preferences.edit()
                .remove(HAS_DESTINATION)
                .remove(LATITUDE_BITS)
                .remove(LONGITUDE_BITS)
                .putBoolean(MONITORING, false)
                .apply();
    }

    private static int clampRadius(int meters) {
        return Math.max(MIN_RADIUS_METERS, Math.min(MAX_RADIUS_METERS, meters));
    }

    private static int clampLocationSource(int mode) {
        if (mode == LOCATION_SOURCE_GPS || mode == LOCATION_SOURCE_NETWORK) {
            return mode;
        }
        return LOCATION_SOURCE_AUTOMATIC;
    }
}
