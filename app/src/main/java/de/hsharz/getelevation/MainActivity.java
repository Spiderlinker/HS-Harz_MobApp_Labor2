package de.hsharz.getelevation;

import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.mapbox.android.core.permissions.PermissionsListener;
import com.mapbox.android.core.permissions.PermissionsManager;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.Point;
import com.mapbox.mapboxsdk.Mapbox;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.textAllowOverlap;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.textColor;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.textField;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.textSize;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback, PermissionsListener {

    /**
     * URL-Request für Holen von Elevation-Data
     */
    private static final String ELEVATION_REQUEST_STRING = "https://api.airmap.com/elevation/v1/ele/?points=%f,%f";

    // ---- Komponenten aus xml
    private MapView mapView;
    private MapboxMap mapboxMap;
    private TextView textLocationInfo;
    private Button btnSetToCurrentLocation;

    private PermissionsManager permissionsManager;
    private LocationComponent locationComponent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // MapBox initalisieren
        Mapbox.getInstance(this, getString(R.string.access_token));
        // Activity mit xml-Datei verknüpfen
        setContentView(R.layout.activity_main);

        // Alle Komponenten aus der xml-Datei abspeichern
        getComponents();
        // Interaktionen von einzelnen Komponenten festlegen
        setupInteractions(savedInstanceState);
    }

    /**
     * Alle Komponenten aus der xml-Datei in Objekte speichern,
     * damit diese im weiteren Verlauf verwendet werden können
     */
    private void getComponents() {
        textLocationInfo = findViewById(R.id.textLocationInfo);
        btnSetToCurrentLocation = findViewById(R.id.btnSetToCurrentLocation);
        mapView = findViewById(R.id.mapView);
    }

    /**
     * Interaktionen von und mit Komponenten festlegen.
     *
     * @param savedInstanceState savedInstanceState der App
     */
    private void setupInteractions(Bundle savedInstanceState) {
        btnSetToCurrentLocation.setOnClickListener(view -> {
                    // Auf den aktuellen Standort zoomen
                    locationComponent.setCameraMode(CameraMode.TRACKING);
                    locationComponent.zoomWhileTracking(16f);

                    // Aktuelle Standortinformationen aktualisieren
                    updateLocationInformation();
                }
        );

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);
    }

    @Override
    public void onMapReady(@NonNull MapboxMap mapboxMap) {
        this.mapboxMap = mapboxMap;

        // Style setzen und sobald dieser geladen wurde
        // die aktuelle Position auf der Karte anzeigen
        // und die Standortinformationen aktualisieren
        mapboxMap.setStyle(Style.OUTDOORS, this::enableLocationComponent);

        // Aktion festlegen, wenn Anwender auf die Karte tippt
        // Für den angetippten Punkt soll die Höhe geholt werden
        // und auf der Karte angezeigt werden
        mapboxMap.addOnMapClickListener(point -> {
            getElevationFromOnlineApi(point.getLatitude(), point.getLongitude());
            return true;
        });
    }

    /**
     * Standortinformationen aktualisieren
     */
    private void updateLocationInformation() {
        // Informationen nur aktualisieren, falls welche vorhanden sind
        if (locationComponent != null && locationComponent.getLastKnownLocation() != null) {
            Location location = locationComponent.getLastKnownLocation();
            String infoText = "Latitude/Longitude: %n"
                    + "Breitengrad: %f° %n"
                    + "Längengrad: %f° %n"
                    + "Genauigkeit: %fm";

            textLocationInfo.setText(String.format(Locale.getDefault(), infoText,
                    location.getLatitude(),
                    location.getLongitude(),
                    location.getAccuracy()));
        }
    }

    /**
     * Höhe (Elevation) von den angegebenen Koordinaten in Form von <br>
     * <li> Breitengrad / Latitude: {@code lat} und </li>
     * <li> Längengrad / Longitude: {@code lng} </li>
     * über eine Anfrage an eine Online-API ermitteln.
     * <p>
     * Die Anfrage an eine Online-API ist notwendig,
     * da die Höheninformationen eine enorme Menge an Daten darstellen und
     * diese nicht lokal auf dem Gerät gespeichert werden können.
     * Deshalb werden diese von einem Server abgerufen.
     *
     * @param lat Breitengrad des Punktes, dessen Höhe ermittelt werden soll
     * @param lng Längengrad der Punktes, dessen Höhe ermittelt werden soll
     */
    private void getElevationFromOnlineApi(double lat, double lng) {
        new Thread(() -> {
            // Anfrage vorbereiten
            String urlRequest = String.format(Locale.getDefault(), ELEVATION_REQUEST_STRING, lat, lng);

            try {
                // Antwort von Server holen
                String rawResponse = getResponseFromUrlRequest(urlRequest);
                // Antwort parsen und Höhe extrahieren
                String elevation = parseElevationFromResponse(rawResponse);
                // Höhe auf Karte darstellen
                displayElevationOnMap(lat, lng, elevation);

            } catch (IOException e) {
                e.printStackTrace();
                // Ein Fehler ist aufgetreten, keine Internetverbindung?
                runOnUiThread(() -> {
                    // Fehler mittels Toast dem Nutzer darstellen
                    Toast.makeText(MainActivity.this, "Error getting data: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private String getResponseFromUrlRequest(String urlRequest) throws IOException {

        // Verbindung zu gegebenen URL aufbauen
        URL url = new URL(urlRequest);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

        try {
            // InputStream von aufgebauter Verbindung aufbereiten zum Lesen aller
            // zurückgelieferten Zeilen
            InputStreamReader inputStreamReader = new InputStreamReader(urlConnection.getInputStream(), StandardCharsets.UTF_8);
            return new BufferedReader(inputStreamReader)
                    .lines() // Alle Zeilen lesen
                    .collect(Collectors.joining()); // und verbinden in einen String
        } finally {
            // Verbindung trennen
            urlConnection.disconnect();
        }
    }

    /**
     * Höhe aus erhaltener Response extrahieren
     * <p>
     * Response des Servers sieht exemplarisch folgendermaßen aus: <br>
     * {@code {"status":"success","data":[273]}} <br>
     * Die Höhe wird also im data-Teil in eckigen Klammern übermittelt.
     * Dies in den eckigen Klammern enthaltende Zahl wird nun extrahiert und
     * zurückgegeben
     * </p>
     *
     * @param rawResponse Antwort von Server in Form von '{@code {"status":"success","data":[273]}}'
     * @return In Response enthaltende Höhe (Elevation)
     */
    private String parseElevationFromResponse(String rawResponse) {
        return rawResponse.substring(rawResponse.indexOf('[') + 1, rawResponse.indexOf(']'));
    }

    private void displayElevationOnMap(double lat, double lng, String elevation) {
        runOnUiThread(() -> {

            // Jede Source und jeder Layer müssen eine einzigartige ID besitzen
            long timestamp = System.currentTimeMillis();
            String sourceID = "SOURCE_ID_" + timestamp;
            String layerID = "LAYER_ID_" + timestamp;

            Style style = mapboxMap.getStyle();
            if (style != null) {
                // Neuen Layer auf die Karte hinzufügen und die Höhe am gegebenen Punkt anzeigen
                mapboxMap.getStyle().addSource(new GeoJsonSource(sourceID, Feature.fromGeometry(Point.fromLngLat(lng, lat))));
                mapboxMap.getStyle().addLayer(new SymbolLayer(layerID, sourceID)
                        .withProperties(
                                textField(elevation + "m"), // Höhe anzeigen
                                textAllowOverlap(true),
                                textSize(18f),
                                textColor(Color.RED)
                        ));
            }
        });
    }

    /**
     * Prüfen, ob Standortabruf erlaubt ist und Standort auf Map anzeigen.
     * Zudem werden die Standortinformationen abgerufen und angezeigt
     *
     * @param loadedMapStyle Style der Map
     */
    private void enableLocationComponent(@NonNull Style loadedMapStyle) {
        // Prüfe, ob Standortabruf erlaubt ist
        // Nur bei erlaubtem Standort kann der Standort angezeigt werden
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            // LocationComponent initialisieren
            setupLocationComponent(loadedMapStyle);

            // Standortinformationen aktualisieren
            updateLocationInformation();
        } else {
            // Standortzugriff wurde noch nicht erteilt,
            // Benutzer um Berechtigung fragen
            permissionsManager = new PermissionsManager(this);
            permissionsManager.requestLocationPermissions(this);
        }
    }

    private void setupLocationComponent(@NonNull Style style) {
        // Standort des Benutzers soll eingefärbt werden
        // Genaugikeit soll als grüner Kreis unter Standort angezeigt werden
        LocationComponentOptions customLocationComponentOptions = LocationComponentOptions.builder(this)
                .elevation(5)
                .accuracyAlpha(.6f)
                .accuracyColor(Color.GREEN)
                .build();

        // LocationComponent holen initalisieren
        locationComponent = mapboxMap.getLocationComponent();
        LocationComponentActivationOptions locationComponentActivationOptions =
                LocationComponentActivationOptions.builder(this, style)
                        .locationComponentOptions(customLocationComponentOptions)
                        .build();

        // LocationComponent aktivieren
        locationComponent.activateLocationComponent(locationComponentActivationOptions);
        locationComponent.setLocationComponentEnabled(true);

        // Fokus auf den aktuellen Standort und Kompass aktivieren
        locationComponent.setCameraMode(CameraMode.TRACKING);
        locationComponent.setRenderMode(RenderMode.COMPASS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        // Der Benutzer hat eine Entscheidung getroffen, ob die App auf den Standort zugreifen darf
        // Diese Entscheidung an den PermissionManager weitergeben
        permissionsManager.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onExplanationNeeded(List<String> permissionsToExplain) {
        // Information zur Erteilung der Berechtigung für den Zugriff auf den Standort
        Toast.makeText(this, "Bitte erlaube der App auf deinen Standort zuzugreifen.", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onPermissionResult(boolean granted) {
        if (granted) {
            // Sobald der Zugriff auf den Standort erlaubt wurde
            // Standort auf Map anzeigen und Standortinformationen anzeigen
            mapboxMap.getStyle(this::enableLocationComponent);
        } else {
            // Standortzugirff wurde nicht erteilt, Applikation beenden
            Toast.makeText(this, "Die Standortberechtigung wurde nicht erteilt!", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // ------- sonstige benötigte Methoden für Mapbox --------

    @Override
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
    protected void onSaveInstanceState(@NonNull Bundle outState) {
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
