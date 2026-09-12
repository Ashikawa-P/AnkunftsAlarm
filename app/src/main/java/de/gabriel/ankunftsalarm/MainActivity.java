package de.gabriel.ankunftsalarm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Main screen: select a destination, choose a radius, then start the alarm. */
public final class MainActivity extends Activity {
    private static final int MAP_LOCATION_PERMISSION_REQUEST = 420;
    private static final int ALARM_PERMISSION_REQUEST = 421;
    private static final int LOCATION_SOURCE_AUTOMATIC_ID = 5101;
    private static final int LOCATION_SOURCE_GPS_ID = 5102;
    private static final int LOCATION_SOURCE_NETWORK_ID = 5103;
    private static final int BLUE = Color.rgb(21, 101, 192);
    private static final int BLUE_DARK = Color.rgb(12, 74, 140);
    private static final int TEXT = Color.rgb(28, 37, 48);
    private static final int MUTED = Color.rgb(91, 104, 119);
    private static final int BACKGROUND = Color.rgb(247, 249, 252);

    private AppState state;
    private MapTileView mapView;
    private TextView statusView;
    private TextView distanceView;
    private Button startStopButton;
    private LocationManager uiLocationManager;
    private Location latestUiLocation;
    private boolean receiverRegistered;
    private boolean activityStarted;
    private boolean uiLocationUpdatesActive;
    private boolean mapLocationRequestPending;
    private boolean mapLocationPermissionAsked;
    private boolean startAfterPermission;
    private boolean destinationPickerVisible;
    private boolean centerOnFirstUiLocation;
    private boolean addressSearchInProgress;
    private double candidateLatitude;
    private double candidateLongitude;
    private boolean hasCandidate;

    private final LocationListener uiLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            showUiLocation(location);
        }
    };

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean monitoring = intent.getBooleanExtra(
                    LocationMonitorService.EXTRA_MONITORING,
                    false
            );
            state.setMonitoring(monitoring);
            if (intent.hasExtra(LocationMonitorService.EXTRA_DISTANCE_METERS)) {
                float distance = intent.getFloatExtra(
                        LocationMonitorService.EXTRA_DISTANCE_METERS,
                        Float.NaN
                );
                updateDistance(distance);
            }
            if (mapView != null
                    && intent.hasExtra(LocationMonitorService.EXTRA_LATITUDE)
                    && intent.hasExtra(LocationMonitorService.EXTRA_LONGITUDE)) {
                mapView.setCurrentLocation(
                        intent.getDoubleExtra(LocationMonitorService.EXTRA_LATITUDE, 0.0),
                        intent.getDoubleExtra(LocationMonitorService.EXTRA_LONGITUDE, 0.0)
                );
            }
            String error = intent.getStringExtra(LocationMonitorService.EXTRA_ERROR);
            updateMonitoringUi();
            if (error != null && !error.isEmpty()) {
                showMessage("Alarm konnte nicht gestartet werden", error);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        state = new AppState(this);
        uiLocationManager = getSystemService(LocationManager.class);
        renderCurrentScreen();
        handleArrivalIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        if (!receiverRegistered) {
            registerReceiver(
                    serviceReceiver,
                    new IntentFilter(LocationMonitorService.ACTION_STATE),
                    Context.RECEIVER_NOT_EXPORTED
            );
            receiverRegistered = true;
        }
        startUiLocationUpdates();
        updateMonitoringUi();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        stopUiLocationUpdates();
        if (receiverRegistered) {
            unregisterReceiver(serviceReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleArrivalIntent(intent);
    }

    private void renderCurrentScreen() {
        if (state.hasDestination()) {
            showConfiguration();
        } else {
            showDestinationPicker(false);
        }
    }

    private void showDestinationPicker(boolean showExistingTarget) {
        destinationPickerVisible = true;
        centerOnFirstUiLocation = !showExistingTarget;
        addressSearchInProgress = false;
        hasCandidate = showExistingTarget && state.hasDestination();
        if (hasCandidate) {
            candidateLatitude = state.latitude();
            candidateLongitude = state.longitude();
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);

        final MapTileView pickerMap = new MapTileView(this);
        mapView = pickerMap;
        pickerMap.setSelectable(true);
        if (hasCandidate) {
            pickerMap.setCenter(candidateLatitude, candidateLongitude, 15);
            pickerMap.setTarget(candidateLatitude, candidateLongitude, state.radiusMeters());
        }
        if (latestUiLocation != null) {
            pickerMap.setCurrentLocation(
                    latestUiLocation.getLatitude(),
                    latestUiLocation.getLongitude()
            );
        }
        root.addView(pickerMap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView instruction = new TextView(this);
        instruction.setText("Ziel auf der Karte antippen");
        instruction.setTextColor(TEXT);
        instruction.setTextSize(17);
        instruction.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(dp(18), dp(12), dp(18), dp(12));
        instruction.setBackground(rounded(Color.argb(238, 255, 255, 255), 18, 0));
        FrameLayout.LayoutParams instructionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        instructionParams.topMargin = dp(18);
        root.addView(instruction, instructionParams);

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(8), dp(7), dp(8), dp(7));
        addressRow.setBackground(rounded(Color.argb(242, 255, 255, 255), 16,
                Color.rgb(205, 214, 222)));

        EditText addressInput = new EditText(this);
        addressInput.setHint("Adresse oder Ort eingeben");
        addressInput.setSingleLine(true);
        addressInput.setTextColor(TEXT);
        addressInput.setHintTextColor(MUTED);
        addressInput.setTextSize(15);
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
        addressInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        addressInput.setBackgroundColor(Color.TRANSPARENT);
        addressRow.addView(addressInput, new LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
        ));

        Button searchButton = secondaryButton("Suchen");
        addressRow.addView(searchButton, new LinearLayout.LayoutParams(dp(94), dp(44)));

        FrameLayout.LayoutParams addressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        addressParams.leftMargin = dp(14);
        addressParams.rightMargin = dp(14);
        addressParams.topMargin = dp(76);
        root.addView(addressRow, addressParams);

        Button locateButton = secondaryButton("⌾ Mein Standort");
        FrameLayout.LayoutParams locateParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(46),
                Gravity.TOP | Gravity.END
        );
        locateParams.topMargin = dp(148);
        locateParams.rightMargin = dp(14);
        root.addView(locateButton, locateParams);

        Button confirm = primaryButton(hasCandidate
                ? "Diesen Standort verwenden"
                : "Zuerst einen Standort wählen");
        confirm.setEnabled(hasCandidate);
        confirm.setAlpha(hasCandidate ? 1f : 0.65f);
        FrameLayout.LayoutParams confirmParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58),
                Gravity.BOTTOM
        );
        confirmParams.leftMargin = dp(18);
        confirmParams.rightMargin = dp(18);
        confirmParams.bottomMargin = dp(22);
        root.addView(confirm, confirmParams);

        pickerMap.setOnPointSelectedListener(new MapTileView.OnPointSelectedListener() {
            @Override
            public void onPointSelected(double latitude, double longitude) {
                candidateLatitude = latitude;
                candidateLongitude = longitude;
                hasCandidate = true;
                confirm.setEnabled(true);
                confirm.setAlpha(1f);
                confirm.setText("Diesen Standort verwenden");
                instruction.setText(String.format(
                        Locale.GERMANY,
                        "%.5f, %.5f",
                        latitude,
                        longitude
                ));
            }
        });

        View.OnClickListener searchClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                searchAddress(
                        addressInput,
                        searchButton,
                        instruction,
                        confirm,
                        pickerMap
                );
            }
        };
        searchButton.setOnClickListener(searchClickListener);
        addressInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId,
                                          android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    searchClickListener.onClick(view);
                    return true;
                }
                return false;
            }
        });

        locateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                centerMapOnCurrentLocation();
            }
        });

        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!hasCandidate) {
                    return;
                }
                stopMonitoring();
                state.saveDestination(candidateLatitude, candidateLongitude);
                showConfiguration();
            }
        });

        setContentView(root);
        ensureMapLocationPermission();
    }

    private void showConfiguration() {
        destinationPickerVisible = false;
        centerOnFirstUiLocation = false;
        addressSearchInProgress = false;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);

        TextView title = new TextView(this);
        title.setText("AnkunftsAlarm");
        title.setTextColor(TEXT);
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(20), 0, dp(20), 0);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(60)
        ));

        mapView = new MapTileView(this);
        mapView.setSelectable(false);
        mapView.setCenter(state.latitude(), state.longitude(), 15);
        mapView.setTarget(state.latitude(), state.longitude(), state.radiusMeters());
        if (latestUiLocation != null) {
            mapView.setCurrentLocation(
                    latestUiLocation.getLatitude(),
                    latestUiLocation.getLongitude()
            );
        }

        FrameLayout mapContainer = new FrameLayout(this);
        mapContainer.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        Button locateButton = secondaryButton("⌾ Mein Standort");
        FrameLayout.LayoutParams locateParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(44),
                Gravity.TOP | Gravity.END
        );
        locateParams.topMargin = dp(12);
        locateParams.rightMargin = dp(12);
        mapContainer.addView(locateButton, locateParams);
        root.addView(mapContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(285)
        ));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(controls, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView coordinates = textView(13, MUTED, false);
        coordinates.setText(String.format(
                Locale.GERMANY,
                "x: %.6f    y: %.6f",
                state.longitude(),
                state.latitude()
        ));
        controls.addView(coordinates);

        TextView radiusLabel = textView(16, TEXT, true);
        radiusLabel.setPadding(0, dp(16), 0, 0);
        controls.addView(radiusLabel);

        SeekBar radiusSlider = new SeekBar(this);
        int steps = (AppState.MAX_RADIUS_METERS - AppState.MIN_RADIUS_METERS) / 50;
        radiusSlider.setMax(steps);
        radiusSlider.setProgress((state.radiusMeters() - AppState.MIN_RADIUS_METERS) / 50);
        controls.addView(radiusSlider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        TextView locationSourceLabel = textView(16, TEXT, true);
        locationSourceLabel.setText("Standortquelle");
        locationSourceLabel.setPadding(0, dp(12), 0, dp(3));
        controls.addView(locationSourceLabel);

        TextView locationSourceHint = textView(13, MUTED, false);
        locationSourceHint.setText("Automatisch nutzt beide Quellen. Bei den anderen Modi wird ausschließlich die gewählte Quelle ausgewertet.");
        locationSourceHint.setLineSpacing(0, 1.12f);
        controls.addView(locationSourceHint);

        RadioGroup locationSourceGroup = new RadioGroup(this);
        locationSourceGroup.setOrientation(RadioGroup.VERTICAL);
        locationSourceGroup.addView(locationSourceRadioButton(
                LOCATION_SOURCE_AUTOMATIC_ID,
                "Automatisch (GPS + Netzwerk)"
        ));
        locationSourceGroup.addView(locationSourceRadioButton(
                LOCATION_SOURCE_GPS_ID,
                "Nur Satelliten-GPS"
        ));
        locationSourceGroup.addView(locationSourceRadioButton(
                LOCATION_SOURCE_NETWORK_ID,
                "Nur Netzwerk (WLAN/Mobilfunk)"
        ));
        locationSourceGroup.check(locationSourceRadioId(state.locationSourceMode()));
        controls.addView(locationSourceGroup, marginParams(0, 4, 0, 4));

        Switch soundSwitch = new Switch(this);
        soundSwitch.setText("Alarmton abspielen");
        soundSwitch.setTextColor(TEXT);
        soundSwitch.setTextSize(15);
        soundSwitch.setChecked(state.isSoundEnabled());
        soundSwitch.setPadding(dp(2), dp(8), dp(2), dp(8));
        controls.addView(soundSwitch, marginParams(0, 8, 0, 0));

        Switch vibrationSwitch = new Switch(this);
        vibrationSwitch.setText("Handy vibrieren lassen");
        vibrationSwitch.setTextColor(TEXT);
        vibrationSwitch.setTextSize(15);
        vibrationSwitch.setChecked(state.isVibrationEnabled());
        vibrationSwitch.setPadding(dp(2), dp(8), dp(2), dp(8));
        controls.addView(vibrationSwitch, marginParams(0, 0, 0, 0));

        statusView = textView(15, TEXT, true);
        statusView.setPadding(dp(14), dp(12), dp(14), dp(12));
        controls.addView(statusView, marginParams(0, 8, 0, 0));

        distanceView = textView(14, MUTED, false);
        distanceView.setText("Die Entfernung erscheint nach dem ersten Standortsignal.");
        controls.addView(distanceView, marginParams(2, 10, 2, 0));

        startStopButton = primaryButton("");
        controls.addView(startStopButton, marginParams(0, 18, 0, 0, dp(56)));

        Button chooseNew = secondaryButton("Anderes Ziel auswählen");
        controls.addView(chooseNew, marginParams(0, 10, 0, 0, dp(52)));

        TextView explanation = textView(13, MUTED, false);
        explanation.setLineSpacing(0, 1.18f);
        explanation.setText("Nach dem Start läuft die Standortüberwachung als sichtbarer Hintergrunddienst weiter. Ein einzelner Messwert im Zielkreis reicht nicht aus: Erst zwei frische Messungen hintereinander lösen den Alarm aus. Die Benachrichtigung erscheint immer; Alarmton und Vibration folgen nur entsprechend deiner Auswahl.");
        controls.addView(explanation, marginParams(2, 16, 2, 0));

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        setContentView(root);

        updateRadiusLabel(radiusLabel, state.radiusMeters());
        radiusSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int meters = AppState.MIN_RADIUS_METERS + progress * 50;
                state.setRadiusMeters(meters);
                mapView.setRadiusMeters(meters);
                updateRadiusLabel(radiusLabel, meters);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (state.isMonitoring()) {
                    sendServiceAction(LocationMonitorService.ACTION_UPDATE, true);
                }
            }
        });

        locationSourceGroup.setOnCheckedChangeListener(
                new RadioGroup.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(RadioGroup group, int checkedId) {
                        int mode = locationSourceModeForRadioId(checkedId);
                        if (mode == state.locationSourceMode()) {
                            return;
                        }
                        state.setLocationSourceMode(mode);
                        restartUiLocationUpdates();
                        if (state.isMonitoring()) {
                            sendServiceAction(LocationMonitorService.ACTION_UPDATE, true);
                        }
                    }
                }
        );

        soundSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                state.setSoundEnabled(isChecked);
            }
        });
        vibrationSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                state.setVibrationEnabled(isChecked);
            }
        });

        locateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                centerMapOnCurrentLocation();
            }
        });

        startStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (state.isMonitoring()) {
                    stopMonitoring();
                } else {
                    ensurePermissionsAndStart();
                }
            }
        });
        chooseNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopMonitoring();
                showDestinationPicker(true);
            }
        });
        updateMonitoringUi();
        ensureMapLocationPermission();
    }

    private void searchAddress(
            EditText addressInput,
            Button searchButton,
            TextView instruction,
            Button confirm,
            MapTileView pickerMap
    ) {
        if (addressSearchInProgress) {
            return;
        }
        String query = addressInput.getText().toString().trim();
        if (query.isEmpty()) {
            addressInput.setError("Bitte eine Adresse oder einen Ort eingeben.");
            return;
        }
        if (!Geocoder.isPresent()) {
            showMessage(
                    "Adresssuche nicht verfügbar",
                    "Auf diesem Gerät ist kein Geocoder-Dienst eingerichtet. Du kannst das Ziel weiterhin direkt auf der Karte auswählen."
            );
            return;
        }

        addressSearchInProgress = true;
        searchButton.setEnabled(false);
        searchButton.setText("Suche …");
        hideKeyboard(addressInput);

        try {
            Geocoder geocoder = new Geocoder(this, Locale.GERMANY);
            geocoder.getFromLocationName(query, 1, new Geocoder.GeocodeListener() {
                @Override
                public void onGeocode(List<Address> addresses) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            finishAddressSearch(searchButton);
                            if (mapView != pickerMap || !destinationPickerVisible) {
                                return;
                            }
                            if (addresses.isEmpty()) {
                                showMessage(
                                        "Adresse nicht gefunden",
                                        "Versuche es mit Straße, Hausnummer, Ort und optional der Postleitzahl."
                                );
                                return;
                            }
                            Address result = addresses.get(0);
                            double latitude = result.getLatitude();
                            double longitude = result.getLongitude();
                            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
                                showMessage(
                                        "Adresse nicht gefunden",
                                        "Der Suchdienst hat keine gültige Kartenposition geliefert."
                                );
                                return;
                            }

                            candidateLatitude = latitude;
                            candidateLongitude = longitude;
                            hasCandidate = true;
                            centerOnFirstUiLocation = false;
                            pickerMap.setCenter(latitude, longitude, 16);
                            pickerMap.setTarget(latitude, longitude, state.radiusMeters());
                            confirm.setEnabled(true);
                            confirm.setAlpha(1f);
                            confirm.setText("Diesen Standort verwenden");
                            String displayName = result.getMaxAddressLineIndex() >= 0
                                    ? result.getAddressLine(0)
                                    : query;
                            instruction.setText(displayName);
                        }
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            finishAddressSearch(searchButton);
                            if (mapView == pickerMap && destinationPickerVisible) {
                                showMessage(
                                        "Adresssuche fehlgeschlagen",
                                        "Prüfe die Internetverbindung oder wähle das Ziel direkt auf der Karte."
                                );
                            }
                        }
                    });
                }
            });
        } catch (RuntimeException exception) {
            finishAddressSearch(searchButton);
            showMessage(
                    "Adresssuche fehlgeschlagen",
                    "Der Suchdienst ist momentan nicht erreichbar. Du kannst das Ziel direkt auf der Karte auswählen."
            );
        }
    }

    private void finishAddressSearch(Button searchButton) {
        addressSearchInProgress = false;
        searchButton.setEnabled(true);
        searchButton.setText("Suchen");
    }

    private void hideKeyboard(View view) {
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void ensureMapLocationPermission() {
        if (hasMapLocationPermission()) {
            startUiLocationUpdates();
            return;
        }
        requestMapLocationPermission(false);
    }

    private void requestMapLocationPermission(boolean force) {
        if (hasMapLocationPermission()) {
            startUiLocationUpdates();
            return;
        }
        if (mapLocationRequestPending || (!force && mapLocationPermissionAsked)) {
            return;
        }
        mapLocationPermissionAsked = true;
        mapLocationRequestPending = true;
        requestPermissions(
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                MAP_LOCATION_PERMISSION_REQUEST
        );
    }

    private boolean hasMapLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startUiLocationUpdates() {
        if (!activityStarted || uiLocationUpdatesActive || !hasMapLocationPermission()) {
            return;
        }
        boolean requested = false;
        for (String provider : selectedUiLocationProviders()) {
            requested |= requestUiLocationProvider(provider);
        }
        uiLocationUpdatesActive = requested;

        Location freshest = null;
        for (String provider : selectedUiLocationProviders()) {
            try {
                Location candidate = uiLocationManager.getLastKnownLocation(provider);
                if (candidate != null
                        && (freshest == null || candidate.getTime() > freshest.getTime())) {
                    freshest = candidate;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                // The permission or provider may have changed while the screen was opening.
            }
        }
        if (freshest != null) {
            showUiLocation(freshest);
        }
    }

    private String[] selectedUiLocationProviders() {
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

    private void restartUiLocationUpdates() {
        stopUiLocationUpdates();
        latestUiLocation = null;
        if (mapView != null) {
            mapView.clearCurrentLocation();
        }
        startUiLocationUpdates();
    }

    private boolean requestUiLocationProvider(String provider) {
        try {
            if (!uiLocationManager.isProviderEnabled(provider)) {
                return false;
            }
            uiLocationManager.requestLocationUpdates(
                    provider,
                    2_000L,
                    3f,
                    uiLocationListener
            );
            return true;
        } catch (SecurityException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private void stopUiLocationUpdates() {
        if (!uiLocationUpdatesActive || uiLocationManager == null) {
            return;
        }
        try {
            uiLocationManager.removeUpdates(uiLocationListener);
        } catch (SecurityException ignored) {
            // Permission may have been revoked while the activity was visible.
        }
        uiLocationUpdatesActive = false;
    }

    private void showUiLocation(Location location) {
        latestUiLocation = location;
        if (mapView == null) {
            return;
        }
        mapView.setCurrentLocation(location.getLatitude(), location.getLongitude());
        if (destinationPickerVisible && centerOnFirstUiLocation && !hasCandidate) {
            centerOnFirstUiLocation = false;
            mapView.setCenter(location.getLatitude(), location.getLongitude(), 15);
        }
    }

    private void centerMapOnCurrentLocation() {
        if (!hasMapLocationPermission()) {
            requestMapLocationPermission(true);
            return;
        }
        startUiLocationUpdates();
        if (latestUiLocation == null) {
            Toast.makeText(this, "Standort wird gesucht …", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mapView != null) {
            mapView.setCurrentLocation(
                    latestUiLocation.getLatitude(),
                    latestUiLocation.getLongitude()
            );
            mapView.setCenter(
                    latestUiLocation.getLatitude(),
                    latestUiLocation.getLongitude(),
                    16
            );
        }
    }

    private void ensurePermissionsAndStart() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!missing.isEmpty()) {
            startAfterPermission = true;
            requestPermissions(missing.toArray(new String[0]), ALARM_PERMISSION_REQUEST);
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Benachrichtigungen sind ausgeschaltet")
                    .setMessage("Aktiviere Benachrichtigungen in den App-Einstellungen, damit der Ankunftsalarm sichtbar und hörbar ist.")
                    .setNegativeButton("Abbrechen", null)
                    .setPositiveButton("App-Einstellungen", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            openAppSettings();
                        }
                    })
                    .show();
            return;
        }
        startMonitoring();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MAP_LOCATION_PERMISSION_REQUEST) {
            mapLocationRequestPending = false;
            if (hasMapLocationPermission()) {
                startUiLocationUpdates();
            }
            return;
        }
        if (requestCode != ALARM_PERMISSION_REQUEST || !startAfterPermission) {
            return;
        }
        startAfterPermission = false;
        if (hasRequiredPermissions()) {
            startUiLocationUpdates();
            startMonitoring();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Berechtigungen erforderlich")
                    .setMessage("Für den Hintergrundalarm werden der genaue Standort und Benachrichtigungen benötigt.")
                    .setNegativeButton("Abbrechen", null)
                    .setPositiveButton("App-Einstellungen", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            openAppSettings();
                        }
                    })
                    .show();
        }
    }

    private boolean hasRequiredPermissions() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
                && manager.areNotificationsEnabled();
    }

    private void startMonitoring() {
        if (!hasRequiredPermissions()) {
            ensurePermissionsAndStart();
            return;
        }
        sendServiceAction(LocationMonitorService.ACTION_START, true);
        state.setMonitoring(true);
        updateMonitoringUi();
    }

    private void stopMonitoring() {
        state.setMonitoring(false);
        sendServiceAction(LocationMonitorService.ACTION_STOP, false);
        updateMonitoringUi();
    }

    private void sendServiceAction(String action, boolean foreground) {
        Intent service = new Intent(this, LocationMonitorService.class).setAction(action);
        try {
            if (foreground) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (RuntimeException exception) {
            state.setMonitoring(false);
            updateMonitoringUi();
            showMessage(
                    "Hintergrunddienst konnte nicht starten",
                    "Bitte öffne die App erneut und prüfe Standort- und Akku-Einstellungen."
            );
        }
    }

    private void updateMonitoringUi() {
        if (statusView == null || startStopButton == null || state == null) {
            return;
        }
        boolean active = state.isMonitoring();
        statusView.setText(active
                ? "Alarm aktiv – andere Apps können jetzt verwendet werden."
                : "Alarm noch nicht aktiv");
        statusView.setTextColor(active ? Color.rgb(24, 105, 55) : MUTED);
        statusView.setBackground(rounded(
                active ? Color.rgb(224, 244, 231) : Color.rgb(235, 239, 244),
                14,
                0
        ));
        startStopButton.setText(active ? "Alarm stoppen" : "Alarm starten");
        startStopButton.setBackground(rounded(active
                ? Color.rgb(181, 45, 45)
                : BLUE, 16, 0));
    }

    private void updateDistance(float distanceMeters) {
        if (distanceView == null || Float.isNaN(distanceMeters)) {
            return;
        }
        distanceView.setText(String.format(
                Locale.GERMANY,
                "Aktuelle Entfernung zum Ziel: %.0f m",
                distanceMeters
        ));
    }

    private void handleArrivalIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(LocationMonitorService.EXTRA_ARRIVAL, false)) {
            return;
        }
        intent.removeExtra(LocationMonitorService.EXTRA_ARRIVAL);
        state.setMonitoring(false);
        updateMonitoringUi();
        new AlertDialog.Builder(this)
                .setTitle("Du näherst dich deinem Ziel")
                .setMessage("Der eingestellte Zielradius wurde erreicht. Du kannst dich jetzt auf den Ausstieg vorbereiten.")
                .setPositiveButton("Verstanden", null)
                .show();
    }

    private void openAppSettings() {
        Intent settings = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(settings);
    }

    private void showMessage(String title, String message) {
        if (isFinishing()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void updateRadiusLabel(TextView view, int meters) {
        view.setText(String.format(Locale.GERMANY, "Alarmradius: %d m", meters));
    }

    private RadioButton locationSourceRadioButton(int id, String text) {
        RadioButton button = new RadioButton(this);
        button.setId(id);
        button.setText(text);
        button.setTextColor(TEXT);
        button.setTextSize(15);
        button.setPadding(dp(2), dp(3), dp(2), dp(3));
        return button;
    }

    private int locationSourceRadioId(int mode) {
        if (mode == AppState.LOCATION_SOURCE_GPS) {
            return LOCATION_SOURCE_GPS_ID;
        }
        if (mode == AppState.LOCATION_SOURCE_NETWORK) {
            return LOCATION_SOURCE_NETWORK_ID;
        }
        return LOCATION_SOURCE_AUTOMATIC_ID;
    }

    private int locationSourceModeForRadioId(int checkedId) {
        if (checkedId == LOCATION_SOURCE_GPS_ID) {
            return AppState.LOCATION_SOURCE_GPS;
        }
        if (checkedId == LOCATION_SOURCE_NETWORK_ID) {
            return AppState.LOCATION_SOURCE_NETWORK;
        }
        return AppState.LOCATION_SOURCE_AUTOMATIC;
    }

    private TextView textView(int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(rounded(BLUE, 16, 0));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(BLUE_DARK);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(Color.WHITE, 16, Color.rgb(190, 203, 216)));
        return button;
    }

    private GradientDrawable rounded(int fillColor, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams marginParams(int left, int top, int right, int bottom) {
        return marginParams(left, top, right, bottom, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams marginParams(
            int left,
            int top,
            int right,
            int bottom,
            int height
    ) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
        );
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
