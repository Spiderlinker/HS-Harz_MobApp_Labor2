package de.hsharz.getelevation;

import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.mapbox.android.core.permissions.PermissionsListener;
import com.mapbox.android.core.permissions.PermissionsManager;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.Point;
import com.mapbox.mapboxsdk.Mapbox;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.location.LocationComponent;
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions;
import com.mapbox.mapboxsdk.location.LocationComponentOptions;
import com.mapbox.mapboxsdk.location.modes.CameraMode;
import com.mapbox.mapboxsdk.location.modes.RenderMode;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback;
import com.mapbox.mapboxsdk.maps.Style;
import com.mapbox.mapboxsdk.style.layers.SymbolLayer;
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.net.ssl.HttpsURLConnection;

import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.textAllowOverlap;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.textColor;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.textField;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.textSize;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback, PermissionsListener {

    private static final String ELEVATION_REQUEST_STRING = "https://api.airmap.com/elevation/v1/ele/?points=%f,%f";

    private PermissionsManager permissionsManager;
    private MapView mapView;
    private MapboxMap mapboxMap;
    private LocationComponent locationComponent;

    private TextView textLocationInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Mapbox.getInstance(this, getString(R.string.access_token));
        setContentView(R.layout.activity_main);

        textLocationInfo = findViewById(R.id.textLocationInfo);

        findViewById(R.id.btnSetToCurrentLocation).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                locationComponent.setCameraMode(CameraMode.TRACKING);
                locationComponent.zoomWhileTracking(16f);
            }
        });

        mapView = findViewById(R.id.mapView);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);
    }

    @Override
    public void onMapReady(@NonNull MapboxMap mapboxMap) {
        this.mapboxMap = mapboxMap;


        mapboxMap.setStyle(Style.OUTDOORS, new Style.OnStyleLoaded() {
            @Override
            public void onStyleLoaded(@NonNull Style style) {
                enableLocationComponent(style);

                if (locationComponent.getLastKnownLocation() != null) {
                    Location location = locationComponent.getLastKnownLocation();
                    textLocationInfo.setText("Latitude/Longitude: " //
                            + "\n Breitengrad: " + location.getLatitude() + "°" //
                            + "\n Längengrad: " + location.getLongitude() + "°" //
                            + "\n Genauigkeit: " + location.getAccuracy() + "m");
                }
            }
        });

        mapboxMap.addOnMapClickListener(point -> {
            getElevationFromOnlineApi(point.getLatitude(), point.getLongitude());
            return false;
        });
    }

    private void getElevationFromOnlineApi(@NonNull double lat, double lng) {
        new Thread(() -> {
            String urlRequest = String.format(ELEVATION_REQUEST_STRING, lat, lng);

            try {
                String rawResponse = getResponseFromUrlRequest(urlRequest);
                String elevation = parseElevationFromResponse(rawResponse);
                displayElevationOnMap(lat, lng, elevation);

            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Error getting data: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private String getResponseFromUrlRequest(String urlRequest) throws IOException {

        URL url = new URL(urlRequest);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

        try {
            InputStreamReader inputStreamReader = new InputStreamReader(urlConnection.getInputStream(), StandardCharsets.UTF_8);
            return new BufferedReader(inputStreamReader)
                    .lines() // Alle Zeilen lesen
                    .collect(Collectors.joining()); // und verbinden in einen String
        } finally {
            urlConnection.disconnect();
        }
    }

    private String parseElevationFromResponse(String rawResponse) {
        return rawResponse.substring(rawResponse.indexOf('[') + 1, rawResponse.indexOf(']'));
    }

    private void displayElevationOnMap(double lat, double lng, String elevation) {
        runOnUiThread(() -> {

            long timestamp = System.currentTimeMillis();
            String sourceID = "SOURCE_ID_" + timestamp;
            String layerID = "LAYER_ID_" + timestamp;

            mapboxMap.getStyle().addSource(new GeoJsonSource(sourceID, Feature.fromGeometry(Point.fromLngLat(lng, lat))));
            mapboxMap.getStyle().addLayer(new SymbolLayer(layerID, sourceID)
                    .withProperties(
                            textField(elevation + "m"),
                            textAllowOverlap(true),
                            textSize(18f),
                            textColor(Color.RED)
                    ));
        });
    }

    @SuppressWarnings({"MissingPermission"})
    private void enableLocationComponent(@NonNull Style loadedMapStyle) {
        // Check if permissions are enabled and if not request
        if (PermissionsManager.areLocationPermissionsGranted(this)) {

            // Create and customize the LocationComponent's options
            LocationComponentOptions customLocationComponentOptions = LocationComponentOptions.builder(this)
                    .elevation(5)
                    .accuracyAlpha(.6f)
                    .accuracyColor(Color.GREEN)
                    .build();

            // Get an instance of the component
            locationComponent = mapboxMap.getLocationComponent();

            LocationComponentActivationOptions locationComponentActivationOptions =
                    LocationComponentActivationOptions.builder(this, loadedMapStyle)
                            .locationComponentOptions(customLocationComponentOptions)
                            .build();

            locationComponent.activateLocationComponent(locationComponentActivationOptions);
            locationComponent.setLocationComponentEnabled(true);

            locationComponent.setCameraMode(CameraMode.TRACKING);
            locationComponent.setRenderMode(RenderMode.COMPASS);


        } else {
            permissionsManager = new PermissionsManager(this);
            permissionsManager.requestLocationPermissions(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        permissionsManager.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onExplanationNeeded(List<String> permissionsToExplain) {
        Toast.makeText(this, "Bitte erlaube der App auf deinen Standort zuzugreifen.", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onPermissionResult(boolean granted) {
        if (granted) {
            mapboxMap.getStyle(new Style.OnStyleLoaded() {
                @Override
                public void onStyleLoaded(@NonNull Style style) {
                    enableLocationComponent(style);
                }
            });
        } else {
            Toast.makeText(this, "Die Standortberechtigung wurde nicht erteilt!", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @SuppressWarnings({"MissingPermission"})
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mapView.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

}
