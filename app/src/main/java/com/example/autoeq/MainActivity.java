package com.example.autoeq;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.spotify.sdk.android.auth.AuthorizationClient;
import com.spotify.sdk.android.auth.AuthorizationRequest;
import com.spotify.sdk.android.auth.AuthorizationResponse;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "EqualizerApp";
    private static final String CLIENT_ID = BuildConfig.SPOTIFY_CLIENT_ID;
    private static final String REDIRECT_URI = "com.example.autoeq://spotify-callback"; // Must match dashboard
    private static final int WEB_API_TOKEN_REQUEST_CODE = 1337;
    private static final String PREFS_NAME = "autoeq_prefs";
    private static final String PREF_BATTERY_OPT_REQUESTED = "battery_opt_requested";

    // Cached Web API token for the playlist-import feature. Separate from
    // SpotifyMonitorService's own App Remote connection - App Remote only
    // covers local playback state, not listing/reading playlists, which
    // needs real Web API scopes via the browser-based auth flow.
    private String cachedWebApiToken;
    private SpotifyTokenCallback pendingTokenCallback;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Nothing to branch on either way - the foreground service
                // still starts and works without it, Android just won't show
                // its notification (silently, no crash) if this is denied.
                Log.d(TAG, "POST_NOTIFICATIONS granted: " + granted);
            });

    public interface SpotifyTokenCallback {
        void onTokenReady(String accessToken);
        void onTokenError(String message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // sets the whole view to activity_main

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.equalizer_fragment_container, new EqualizerEditorFragment())
                .commit();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        requestNotificationPermissionIfNeeded();
        requestIgnoreBatteryOptimizationsIfNeeded();
        startAutoEqService();
    }

    // Android 13+ requires this to be requested at runtime before any
    // notification can show, including a foreground service's required one.
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    // Without this, many OEMs (Samsung, Xiaomi, etc.) kill the whole app
    // process during idle/Doze under battery management, foreground service
    // or not - the service's own START_STICKY only helps once the process is
    // already dead, it can't prevent the kill in the first place. This shows
    // the standard system "allow to run in background" prompt so the auto-
    // switching service actually stays alive.
    //
    // Only asked once ever, via the prefs flag below - if the user dismisses
    // or denies it, we don't nag them again on every launch. They can still
    // grant it later from Android's own battery settings if they change
    // their mind.
    private void requestIgnoreBatteryOptimizationsIfNeeded() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_BATTERY_OPT_REQUESTED, false)) return;

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) return;

        prefs.edit().putBoolean(PREF_BATTERY_OPT_REQUESTED, true).apply();

        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "Device doesn't support battery optimization exemption requests", e);
        }
    }

    // Starts the service that owns the EQ effect and the Spotify connection
    // for as long as the app is installed and has been opened at least once
    // - it keeps running (and auto-switching presets) after this Activity is
    // gone, which is the whole point. See SpotifyMonitorService.
    private void startAutoEqService() {
        Intent serviceIntent = new Intent(this, SpotifyMonitorService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    /**
     * Gets a Spotify Web API access token for playlist reads (listing
     * playlists, reading their tracks). Only triggers Spotify's login screen
     * when actually needed - the first time this is called - not on every
     * app launch, and caches the result for the rest of the session.
     */
    public void requestSpotifyWebApiToken(SpotifyTokenCallback callback) {
        if (cachedWebApiToken != null) {
            callback.onTokenReady(cachedWebApiToken);
            return;
        }

        if (CLIENT_ID == null || CLIENT_ID.isEmpty()) {
            callback.onTokenError("SPOTIFY_CLIENT_ID is not set in local.properties");
            return;
        }

        pendingTokenCallback = callback;

        AuthorizationRequest.Builder builder =
                new AuthorizationRequest.Builder(CLIENT_ID, AuthorizationResponse.Type.TOKEN, REDIRECT_URI);
        builder.setScopes(new String[]{"playlist-read-private", "playlist-read-collaborative"});
        AuthorizationClient.openLoginActivity(this, WEB_API_TOKEN_REQUEST_CODE, builder.build());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        if (requestCode == WEB_API_TOKEN_REQUEST_CODE) {
            SpotifyTokenCallback callback = pendingTokenCallback;
            pendingTokenCallback = null;
            if (callback == null) return;

            if (intent == null) {
                callback.onTokenError("Login cancelled");
                return;
            }

            AuthorizationResponse response;
            try {
                response = AuthorizationClient.getResponse(resultCode, intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse Spotify auth response", e);
                callback.onTokenError("Could not read Spotify login response");
                return;
            }

            switch (response.getType()) {
                case TOKEN:
                    cachedWebApiToken = response.getAccessToken();
                    callback.onTokenReady(cachedWebApiToken);
                    break;
                case ERROR:
                    Log.e(TAG, "Spotify auth error: " + response.getError());
                    callback.onTokenError(response.getError());
                    break;
                default:
                    callback.onTokenError("Login cancelled");
                    break;
            }
        }
    }
}