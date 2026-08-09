package com.example.autoeq;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.fragment.app.Fragment;

import com.spotify.android.appremote.api.ConnectionParams;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.protocol.types.Track;
import com.spotify.sdk.android.auth.AuthorizationClient;
import com.spotify.sdk.android.auth.AuthorizationRequest;
import com.spotify.sdk.android.auth.AuthorizationResponse;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "EqualizerApp";
    private static final String CLIENT_ID = BuildConfig.SPOTIFY_CLIENT_ID;
    private static final String REDIRECT_URI = "com.example.autoeq://spotify-callback"; // Must match dashboard
    private static final int WEB_API_TOKEN_REQUEST_CODE = 1337;
    private SpotifyAppRemote mSpotifyAppRemote; // Controls the local Spotify player

    // Cached Web API token for the playlist-import feature. Separate from
    // App Remote's own authorization below - App Remote only covers local
    // playback state, not listing/reading playlists, which needs real Web
    // API scopes via the browser-based auth flow.
    private String cachedWebApiToken;
    private SpotifyTokenCallback pendingTokenCallback;

    public interface SpotifyTokenCallback {
        void onTokenReady(String accessToken);
        void onTokenError(String message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // sets the whole view to activity_main
        Fragment fragment;

        fragment = new EqualizerEditorFragment();

        getSupportFragmentManager().beginTransaction().replace(R.id.equalizer_fragment_container, fragment)
                .commit();


        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        connectToAppRemote();
    }

    // Connects to the Spotify app running on the device so we can read live
    // playback state (currently playing track, artist, etc.). App Remote
    // handles its own authorization with a native in-app Spotify permission
    // dialog - no browser redirect needed just to read playback state.
    private void connectToAppRemote() {
        if (CLIENT_ID == null || CLIENT_ID.isEmpty()) {
            Log.e(TAG, "SPOTIFY_CLIENT_ID is empty - set it in local.properties before connecting");
            return;
        }

        ConnectionParams connectionParams =
                new ConnectionParams.Builder(CLIENT_ID)
                        .setRedirectUri(REDIRECT_URI)
                        .showAuthView(true)
                        .build();

        SpotifyAppRemote.connect(this, connectionParams, new Connector.ConnectionListener() {
            @Override
            public void onConnected(SpotifyAppRemote spotifyAppRemote) {
                mSpotifyAppRemote = spotifyAppRemote;
                Log.d(TAG, "Connected to Spotify App Remote!");
                trackCurrentTrack();
            }

            @Override
            public void onFailure(Throwable throwable) {
                Log.e(TAG, "Could not connect to local Spotify app", throwable);
            }
        });
    }

    // Subscribes to player state updates so we get notified every time the
    // track changes, is paused, resumed, or skipped.
    private void trackCurrentTrack() {
        mSpotifyAppRemote.getPlayerApi()
                .subscribeToPlayerState()
                .setEventCallback(playerState -> {
                    final Track track = playerState.track;
                    if (track != null) {
                        String songName = track.name;
                        String artistName = track.artist.name;
                        Log.d(TAG, "Now Playing: " + songName + " by " + artistName);

                        // TODO: Pass your track metadata to your equalizer processing engine here
                    }
                });
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

    @Override
    protected void onStop() {
        super.onStop();
        // Closes the background pipe to stop the app from consuming battery when closed
        if (mSpotifyAppRemote != null && mSpotifyAppRemote.isConnected()) {
            SpotifyAppRemote.disconnect(mSpotifyAppRemote);
        }
        mSpotifyAppRemote = null;
    }
}