package com.example.autoeq;

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


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "EqualizerApp";
    private static final String CLIENT_ID = BuildConfig.SPOTIFY_CLIENT_ID;
    private static final String REDIRECT_URI = "com.example.autoeq://spotify-callback"; // Must match dashboard
    private SpotifyAppRemote mSpotifyAppRemote; // Controls the local Spotify player

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
    // dialog - no browser redirect or AuthorizationClient flow needed just
    // to read playback state, which is what was crashing onCreate before.
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

                // Connection complete! Start streaming song information.
                trackCurrentTrack();
            }

            @Override
            public void onFailure(Throwable throwable) {
                // Common causes: Spotify app not installed, user declined the
                // in-app permission dialog, or a missing <queries> package
                // visibility declaration in AndroidManifest.xml (required on
                // API 30+ for the app to even detect Spotify is installed).
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