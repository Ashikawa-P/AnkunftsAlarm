package de.gabriel.ankunftsalarm;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.Locale;

/** Foreground service that keeps location monitoring active while other apps are used. */
public final class LocationMonitorService extends Service implements LocationListener {
    public static final String ACTION_START =
            "de.gabriel.ankunftsalarm.action.START";
    public static final String ACTION_UPDATE =
            "de.gabriel.ankunftsalarm.action.UPDATE";
    public static final String ACTION_STOP =
            "de.gabriel.ankunftsalarm.action.STOP";
    public static final String ACTION_STATE =
            "de.gabriel.ankunftsalarm.action.STATE";

    public static final String EXTRA_MONITORING = "monitoring";
    public static final String EXTRA_DISTANCE_METERS = "distance_meters";
    public static final String EXTRA_LATITUDE = "latitude";
    public static final String EXTRA_LONGITUDE = "longitude";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_ARRIVAL = "arrival";

    private static final String TRACKING_CHANNEL = "tracking_v2";
    private static final String ARRIVAL_POPUP_CHANNEL = "arrival_popup_v3";
    private static final String ARRIVAL_SOUND_CHANNEL = "arrival_sound_v3";
    private static final String ARRIVAL_VIBRATION_CHANNEL = "arrival_vibration_v3";
    private static final String ARRIVAL_BOTH_CHANNEL = "arrival_both_v3";
    private static final int TRACKING_NOTIFICATION_ID = 4101;
    private static final int ARRIVAL_NOTIFICATION_ID = 4102;
    private static final long UPDATE_INTERVAL_MS = 3_000L;
    private static final float UPDATE_DISTANCE_METERS = 5f;
    private static final long MAX_ARRIVAL_FIX_AGE_NANOS = 20_000_000_000L;

    private NotificationManager notificationManager;
    private LocationManager locationManager;
    private AppState state;
    private final ArrivalConfirmation arrivalConfirmation = new ArrivalConfirmation();
    private boolean receivingUpdates;

    @Override
    public void onCreate() {
        super.onCreate();
        state = new AppState(this);
        notificationManager = getSystemService(NotificationManager.class);
        locationManager = getSystemService(LocationManager.class);
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopMonitoring(null);
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)
                || ACTION_UPDATE.equals(action)
                || (intent == null && state.isMonitoring())) {
            startMonitoring();
            return START_STICKY;
        }

        stopSelf();
        return START_NOT_STICKY;
    }

    private void startMonitoring() {
        if (!state.hasDestination()) {
            stopMonitoring("Kein Ziel ausgewählt.");
            return;
        }
        if (!hasLocationPermission()) {
            stopMonitoring("Standortberechtigung fehlt.");
            return;
        }

        startForeground(
                TRACKING_NOTIFICATION_ID,
                buildTrackingNotification(Float.NaN),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        );

        removeLocationUpdates();
        arrivalConfirmation.reset();
        boolean requested = false;
        for (String provider : selectedProviders()) {
            requested |= requestProvider(provider);
        }

        if (!requested) {
            stopMonitoring(selectedProviderUnavailableMessage());
            return;
        }

        receivingUpdates = true;
        state.setMonitoring(true);
        broadcastState(true, Float.NaN, null, null);
        checkLastKnownLocation();
    }

    private boolean requestProvider(String provider) {
        try {
            if (!locationManager.isProviderEnabled(provider)) {
                return false;
            }
            locationManager.requestLocationUpdates(
                    provider,
                    UPDATE_INTERVAL_MS,
                    UPDATE_DISTANCE_METERS,
                    this
            );
            return true;
        } catch (SecurityException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private void checkLastKnownLocation() {
        Location freshest = null;
        for (String provider : selectedProviders()) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null
                        && (freshest == null || candidate.getTime() > freshest.getTime())) {
                    freshest = candidate;
                }
            } catch (SecurityException ignored) {
                return;
            }
        }
        if (freshest != null && System.currentTimeMillis() - freshest.getTime() <= 120_000L) {
            processLocation(freshest, false);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        processLocation(location, true);
    }

    private void processLocation(Location location, boolean mayConfirmArrival) {
        if (!state.isMonitoring() || !state.hasDestination()) {
            return;
        }
        if (!isSelectedProvider(location.getProvider())) {
            return;
        }

        float distance = DistanceCalculator.metersBetween(
                location.getLatitude(),
                location.getLongitude(),
                state.latitude(),
                state.longitude()
        );

        notificationManager.notify(
                TRACKING_NOTIFICATION_ID,
                buildTrackingNotification(distance)
        );
        broadcastState(true, distance, location, null);

        boolean fresh = isFreshForArrival(location);
        if (!mayConfirmArrival || !fresh) {
            arrivalConfirmation.reset();
            return;
        }
        if (arrivalConfirmation.record(distance <= state.radiusMeters())) {
            triggerArrival(distance);
        }
    }

    private boolean isFreshForArrival(Location location) {
        long ageNanos = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return ageNanos >= 0L && ageNanos <= MAX_ARRIVAL_FIX_AGE_NANOS;
    }

    private String[] selectedProviders() {
        switch (state.locationSourceMode()) {
            case AppState.LOCATION_SOURCE_GPS:
                return new String[]{LocationManager.GPS_PROVIDER};
            case AppState.LOCATION_SOURCE_NETWORK:
                return new String[]{LocationManager.NETWORK_PROVIDER};
            default:
                return new String[]{
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER
                };
        }
    }

    private boolean isSelectedProvider(String provider) {
        if (provider == null) {
            return false;
        }
        for (String selectedProvider : selectedProviders()) {
            if (selectedProvider.equals(provider)) {
                return true;
            }
        }
        return false;
    }

    private String selectedProviderUnavailableMessage() {
        switch (state.locationSourceMode()) {
            case AppState.LOCATION_SOURCE_GPS:
                return "Satelliten-GPS ist nicht verfügbar oder deaktiviert.";
            case AppState.LOCATION_SOURCE_NETWORK:
                return "Die Netzwerkortung über WLAN/Mobilfunk ist nicht verfügbar.";
            default:
                return "GPS und Netzwerkstandort sind nicht verfügbar.";
        }
    }

    private void triggerArrival(float distanceMeters) {
        removeLocationUpdates();
        state.setMonitoring(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        notificationManager.notify(
                ARRIVAL_NOTIFICATION_ID,
                buildArrivalNotification(distanceMeters)
        );
        broadcastState(false, distanceMeters, null, null);
        stopSelf();
    }

    private void stopMonitoring(String error) {
        removeLocationUpdates();
        state.setMonitoring(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        broadcastState(false, Float.NaN, null, error);
        stopSelf();
    }

    private void removeLocationUpdates() {
        if (locationManager == null) {
            return;
        }
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
            // Permission may have been revoked while the service was running.
        }
        receivingUpdates = false;
        arrivalConfirmation.reset();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void createNotificationChannels() {
        NotificationChannel tracking = new NotificationChannel(
                TRACKING_CHANNEL,
                "Aktive Zielüberwachung",
                NotificationManager.IMPORTANCE_LOW
        );
        tracking.setDescription("Zeigt an, dass AnkunftsAlarm im Hintergrund läuft.");
        tracking.setSound(null, null);
        tracking.enableVibration(false);

        AudioAttributes alarmAudio = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmSound == null) {
            alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }

        notificationManager.createNotificationChannel(tracking);
        notificationManager.createNotificationChannel(createArrivalChannel(
                ARRIVAL_POPUP_CHANNEL,
                "Ankunft – nur Hinweis",
                false,
                false,
                alarmSound,
                alarmAudio
        ));
        notificationManager.createNotificationChannel(createArrivalChannel(
                ARRIVAL_SOUND_CHANNEL,
                "Ankunft – Alarmton",
                true,
                false,
                alarmSound,
                alarmAudio
        ));
        notificationManager.createNotificationChannel(createArrivalChannel(
                ARRIVAL_VIBRATION_CHANNEL,
                "Ankunft – Vibration",
                false,
                true,
                alarmSound,
                alarmAudio
        ));
        notificationManager.createNotificationChannel(createArrivalChannel(
                ARRIVAL_BOTH_CHANNEL,
                "Ankunft – Ton und Vibration",
                true,
                true,
                alarmSound,
                alarmAudio
        ));
    }

    private NotificationChannel createArrivalChannel(
            String id,
            String name,
            boolean soundEnabled,
            boolean vibrationEnabled,
            Uri alarmSound,
            AudioAttributes alarmAudio
    ) {
        NotificationChannel channel = new NotificationChannel(
                id,
                name,
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Benachrichtigt beim Erreichen des eingestellten Zielradius.");
        channel.setSound(soundEnabled ? alarmSound : null, soundEnabled ? alarmAudio : null);
        channel.enableVibration(vibrationEnabled);
        if (vibrationEnabled) {
            channel.setVibrationPattern(new long[]{0L, 700L, 250L, 700L, 250L, 1200L});
        }
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        return channel;
    }

    private Notification buildTrackingNotification(float distanceMeters) {
        Intent openApp = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                10,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent stop = new Intent(this, LocationMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(
                this,
                11,
                stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String text = Float.isNaN(distanceMeters)
                ? "Ziel und Radius werden überwacht."
                : String.format(Locale.GERMANY, "Noch etwa %.0f m bis zum Ziel.", distanceMeters);

        return new Notification.Builder(this, TRACKING_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("AnkunftsAlarm ist aktiv")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, "Alarm stoppen", stopIntent)
                .build();
    }

    private Notification buildArrivalNotification(float distanceMeters) {
        Intent openArrival = new Intent(this, MainActivity.class)
                .putExtra(EXTRA_ARRIVAL, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent arrivalIntent = PendingIntent.getActivity(
                this,
                20,
                openArrival,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = new Notification.Builder(this, selectedArrivalChannel())
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Du näherst dich deinem Ziel")
                .setContentText(String.format(
                        Locale.GERMANY,
                        "Der Zielradius wurde erreicht (Entfernung %.0f m).",
                        distanceMeters
                ))
                .setContentIntent(arrivalIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setTimeoutAfter(10 * 60_000L);

        if (Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent()) {
            builder.setFullScreenIntent(arrivalIntent, true);
        }
        return builder.build();
    }

    private String selectedArrivalChannel() {
        boolean soundEnabled = state.isSoundEnabled();
        boolean vibrationEnabled = state.isVibrationEnabled();
        if (soundEnabled && vibrationEnabled) {
            return ARRIVAL_BOTH_CHANNEL;
        }
        if (soundEnabled) {
            return ARRIVAL_SOUND_CHANNEL;
        }
        if (vibrationEnabled) {
            return ARRIVAL_VIBRATION_CHANNEL;
        }
        return ARRIVAL_POPUP_CHANNEL;
    }

    private void broadcastState(
            boolean monitoring,
            float distanceMeters,
            Location location,
            String error
    ) {
        Intent broadcast = new Intent(ACTION_STATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_MONITORING, monitoring);
        if (!Float.isNaN(distanceMeters)) {
            broadcast.putExtra(EXTRA_DISTANCE_METERS, distanceMeters);
        }
        if (location != null) {
            broadcast.putExtra(EXTRA_LATITUDE, location.getLatitude());
            broadcast.putExtra(EXTRA_LONGITUDE, location.getLongitude());
        }
        if (error != null) {
            broadcast.putExtra(EXTRA_ERROR, error);
        }
        sendBroadcast(broadcast);
    }

    @Override
    public void onProviderEnabled(String provider) {
        // Updates resume automatically.
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (receivingUpdates && !hasEnabledSelectedProvider()) {
            stopMonitoring(selectedProviderUnavailableMessage());
        }
    }

    private boolean hasEnabledSelectedProvider() {
        for (String provider : selectedProviders()) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    return true;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Provider is not available on this device.
            }
        }
        return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // Required by older LocationListener API levels.
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        removeLocationUpdates();
        super.onDestroy();
    }
}
