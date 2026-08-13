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
import android.util.Base64;
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
import com.spotify.sdk.android.auth.PKCEInformation;
import com.spotify.sdk.android.auth.TokenExchangeRequest;
import com.spotify.sdk.android.auth.TokenExchangeResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "EqualizerApp";
    private static final String CLIENT_ID = BuildConfig.SPOTIFY_CLIENT_ID;
    private static final String REDIRECT_URI = "com.example.autoeq://spotify-callback"; // Must match dashboard
    private static final int WEB_API_TOKEN_REQUEST_CODE = 1337;
    private static final String PREFS_NAME = "autoeq_prefs";
    private static final String PREF_BATTERY_OPT_REQUESTED = "battery_opt_requested";

    // Web API auth for the playlist-import/sync feature - separate from
    // SpotifyMonitorService's own App Remote connection, which only covers
    // local playback state, not listing/reading playlists. Authorization
    // Code + PKCE, not Implicit Grant: the latter never issues a refresh
    // token by design, which meant any Web API work outside the exact app
    // session that logged in (sync running after the ~1 hour access token
    // expired, or after a full app restart) had no way to get a token
    // without re-prompting an interactive login. SpotifyTokenStore persists
    // the resulting token pair and silently refreshes with no UI involved.
    private SpotifyTokenStore tokenStore;
    private String pendingCodeVerifier;
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

        tokenStore = new SpotifyTokenStore(this);

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
     * playlists, reading/syncing their tracks). Tries the stored session
     * first (valid cached token, or a silent refresh if it's expired) and
     * only falls back to Spotify's interactive login screen if neither of
     * those produces one - so a returning user with a still-refreshable
     * session never sees a login prompt at all, even after the access token
     * expired or the app was fully restarted.
     */
    public void requestSpotifyWebApiToken(SpotifyTokenCallback callback) {
        if (CLIENT_ID == null || CLIENT_ID.isEmpty()) {
            callback.onTokenError("SPOTIFY_CLIENT_ID is not set in local.properties");
            return;
        }

        tokenStore.getValidAccessToken(CLIENT_ID, new SpotifyTokenStore.TokenCallback() {
            @Override
            public void onTokenReady(String accessToken) {
                runOnUiThread(() -> callback.onTokenReady(accessToken));
            }

            @Override
            public void onNoValidToken() {
                runOnUiThread(() -> startInteractiveLogin(callback));
            }
        });
    }

    private void startInteractiveLogin(SpotifyTokenCallback callback) {
        pendingTokenCallback = callback;
        pendingCodeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(pendingCodeVerifier);

        AuthorizationRequest.Builder builder =
                new AuthorizationRequest.Builder(CLIENT_ID, AuthorizationResponse.Type.CODE, REDIRECT_URI);
        // user-library-read is what GET /me/tracks (Liked Songs) needs -
        // without it Spotify just 403s that endpoint, which surfaced in the
        // app as a silent "Imported 0 songs" rather than a visible error
        // (see importPlaylistsSequentially's onFailure).
        builder.setScopes(new String[]{"playlist-read-private", "playlist-read-collaborative", "user-library-read"});
        builder.setPkceInformation(PKCEInformation.sha256(pendingCodeVerifier, codeChallenge));
        AuthorizationClient.openLoginActivity(this, WEB_API_TOKEN_REQUEST_CODE, builder.build());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        if (requestCode == WEB_API_TOKEN_REQUEST_CODE) {
            SpotifyTokenCallback callback = pendingTokenCallback;
            String codeVerifier = pendingCodeVerifier;
            pendingTokenCallback = null;
            pendingCodeVerifier = null;
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
                case CODE:
                    exchangeCodeForTokens(response.getCode(), codeVerifier, callback);
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

    /**
     * The other half of the PKCE handshake: trades the authorization code
     * Spotify's login screen returned for a real access/refresh token pair.
     * Uses the SDK's own TokenExchangeRequest rather than a hand-rolled
     * OkHttp call - it already implements exactly this exchange (RFC 7636).
     * Its execute() is a blocking call by its own doc comment, hence the
     * background thread.
     */
    private void exchangeCodeForTokens(String code, String codeVerifier, SpotifyTokenCallback callback) {
        if (code == null || codeVerifier == null) {
            callback.onTokenError("Spotify login response was missing required data");
            return;
        }

        new Thread(() -> {
            TokenExchangeResponse result = new TokenExchangeRequest.Builder()
                    .setClientId(CLIENT_ID)
                    .setCode(code)
                    .setRedirectUri(REDIRECT_URI)
                    .setCodeVerifier(codeVerifier)
                    .build()
                    .execute();

            runOnUiThread(() -> {
                if (result.isSuccess()) {
                    tokenStore.storeTokens(result.getAccessToken(), result.getRefreshToken(), result.getExpiresIn());
                    callback.onTokenReady(result.getAccessToken());
                } else {
                    Log.e(TAG, "Token exchange failed: " + result.getError() + " - " + result.getErrorDescription());
                    callback.onTokenError("Could not complete Spotify login: " + result.getError());
                }
            });
        }).start();
    }

    /** 64 random bytes, base64url-encoded with no padding - within RFC 7636's required 43-128 character range for a PKCE code_verifier. */
    private static String generateCodeVerifier() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    /** code_challenge = BASE64URL-ENCODE(SHA256(code_verifier)), per RFC 7636's S256 method. */
    private static String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every Android device.
            throw new RuntimeException(e);
        }
    }
}