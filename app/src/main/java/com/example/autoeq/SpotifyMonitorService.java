package com.example.autoeq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.audiofx.DynamicsProcessing;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.spotify.android.appremote.api.ConnectionParams;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.protocol.types.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owns the live EQ effect and the Spotify connection for as long as the app
 * is "on," independent of whether any Activity/Fragment is visible. This is
 * what makes preset auto-switching keep working with the screen off or a
 * different app in the foreground - previously all of this lived in
 * MainActivity/the Fragment, which tore the Spotify connection down the
 * moment the app left the foreground (onStop).
 *
 * DynamicsProcessing is a session-wide audio effect; running a second,
 * independent instance alongside this one (e.g. if the Fragment still built
 * its own) would stack and double-process the audio. This service is the
 * single, sole owner of it - the Fragment binds to this service and reads/
 * drives the same instance rather than creating its own.
 */
public class SpotifyMonitorService extends Service {
    private static final String TAG = "SpotifyMonitorService";
    private static final String CHANNEL_ID = "spotify_monitor_channel";
    private static final int NOTIFICATION_ID = 1;

    private static final String CLIENT_ID = BuildConfig.SPOTIFY_CLIENT_ID;
    private static final String REDIRECT_URI = "com.example.autoeq://spotify-callback";

    // App Remote connections die silently and don't tell you: the access
    // token expires after roughly an hour, the Spotify app can get killed/
    // restarted by Android under memory pressure, and brief network hiccups
    // drop the link too. None of those fire onFailure - isConnected() just
    // quietly goes false and subscribeToPlayerState's callback stops firing
    // forever. This watchdog polls and reconnects so a dead connection
    // doesn't mean auto-switching is dead until the app is relaunched.
    private static final long WATCHDOG_INTERVAL_MS = 30_000L;

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private DynamicsProcessing systemEq;
    private EqualizerDataHandler dataHandler;
    private List<SelectedEqualizer> presets = new ArrayList<>();
    private SelectedEqualizer currentEq;

    private SpotifyAppRemote spotifyAppRemote;
    private boolean connectingToAppRemote = false;
    private String lastTrackedSongName;
    private String lastTrackedArtistName;

    private StateListener listener;

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (spotifyAppRemote == null || !spotifyAppRemote.isConnected()) {
                Log.d(TAG, "Watchdog: App Remote not connected, reconnecting");
                spotifyAppRemote = null;
                connectToAppRemote();
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    /** Notifies a bound client (the Fragment, when visible) that currentEq/systemEq changed here - e.g. an auto-switch from a track change. */
    public interface StateListener {
        void onStateChanged();
    }

    public class LocalBinder extends Binder {
        SpotifyMonitorService getService() {
            return SpotifyMonitorService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForegroundCompat();
        initSystemEqualizer();

        dataHandler = new EqualizerDataHandler();
        dataHandler.listenToPresets(new EqualizerDataHandler.PresetsListener() {
            @Override
            public void onPresetsLoaded(List<SelectedEqualizer> updatedPresets) {
                presets = updatedPresets;
                // Presets can finish loading from Firebase after Spotify has
                // already reported what's playing - if that first attempt
                // found nothing because the list was still empty, try again
                // now rather than waiting for the next track change.
                if (lastTrackedSongName != null) {
                    onTrackChanged(lastTrackedSongName, lastTrackedArtistName);
                }
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Presets listener error", e);
            }
        });

        connectToAppRemote();
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: if the system kills this process under memory
        // pressure, restart the service (with a null intent) rather than
        // leaving auto-switching silently dead until the app is reopened.
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public DynamicsProcessing getSystemEq() {
        return systemEq;
    }

    public SelectedEqualizer getCurrentEq() {
        return currentEq;
    }

    public void setStateListener(StateListener listener) {
        this.listener = listener;
    }

    /**
     * Applies a preset - same effect as EqualizerEditorFragment's old
     * applySelectedPreset, minus anything UI-specific. Used both when the
     * user picks a preset manually (the Fragment calls this instead of
     * touching systemEq itself) and when a Spotify track match is found.
     */
    public void applyPreset(SelectedEqualizer eq) {
        currentEq = eq;
        if (systemEq == null || eq == null) return;

        // The effect starts disabled (see initSystemEqualizer) and stays that
        // way until there's actually a preset to apply - covers both an auto
        // match from Spotify and a manual pick from the drawer.
        systemEq.setEnabled(true);

        List<Integer> levels = eq.getBandLevels();
        List<Integer> safeLevels = new ArrayList<>(EqBandConfig.NUM_BANDS);
        for (int i = 0; i < EqBandConfig.NUM_BANDS; i++) {
            int level = (levels != null && i < levels.size() && levels.get(i) != null) ? levels.get(i) : 0;
            safeLevels.add(level);
            systemEq.setPreEqBandAllChannelsTo(i,
                    new DynamicsProcessing.EqBand(true, EqBandConfig.BAND_FREQUENCIES_HZ[i], level / 10f));
        }
        eq.setBandLevels(safeLevels);

        if (listener != null) listener.onStateChanged();
    }

    private void initSystemEqualizer() {
        try {
            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2, true, EqBandConfig.NUM_BANDS, false, 0, false, 0, false)
                    .build();

            systemEq = new DynamicsProcessing(0, 0, config);
            // Left disabled until applyPreset actually has gains to apply -
            // this is a system-wide effect on every app's audio (session 0),
            // so enabling it here unconditionally meant it was actively
            // processing all device audio, 24/7, from the moment the app was
            // ever opened, even with nothing playing and every band at 0dB.
            systemEq.setEnabled(false);

            for (int b = 0; b < EqBandConfig.NUM_BANDS; b++) {
                systemEq.setPreEqBandAllChannelsTo(b,
                        new DynamicsProcessing.EqBand(true, EqBandConfig.BAND_FREQUENCIES_HZ[b], 0f));
            }
        } catch (Throwable t) {
            systemEq = null;
            Log.e(TAG, "Equalizer not supported", t);
        }
    }

    private void connectToAppRemote() {
        if (CLIENT_ID == null || CLIENT_ID.isEmpty()) {
            Log.e(TAG, "SPOTIFY_CLIENT_ID is empty - set it in local.properties before connecting");
            return;
        }
        // Guards against the watchdog and an in-flight connect() racing each
        // other into two overlapping connection attempts.
        if (connectingToAppRemote) return;
        connectingToAppRemote = true;

        ConnectionParams connectionParams =
                new ConnectionParams.Builder(CLIENT_ID)
                        .setRedirectUri(REDIRECT_URI)
                        .showAuthView(true)
                        .build();

        SpotifyAppRemote.connect(this, connectionParams, new Connector.ConnectionListener() {
            @Override
            public void onConnected(SpotifyAppRemote appRemote) {
                connectingToAppRemote = false;
                spotifyAppRemote = appRemote;
                Log.d(TAG, "Connected to Spotify App Remote!");
                subscribeToPlayerState();
            }

            @Override
            public void onFailure(Throwable throwable) {
                connectingToAppRemote = false;
                spotifyAppRemote = null;
                Log.e(TAG, "Could not connect to local Spotify app", throwable);
                // Don't wait for the next watchdog tick - Spotify may not be
                // running yet (e.g. right after boot); the watchdog will keep
                // retrying every WATCHDOG_INTERVAL_MS regardless.
            }
        });
    }

    private void subscribeToPlayerState() {
        spotifyAppRemote.getPlayerApi()
                .subscribeToPlayerState()
                .setEventCallback(playerState -> {
                    final Track track = playerState.track;
                    if (track == null) return;

                    String songName = track.name;
                    String artistName = track.artist != null ? track.artist.name : null;

                    boolean sameAsBefore = songName != null && songName.equals(lastTrackedSongName)
                            && (artistName == null ? lastTrackedArtistName == null : artistName.equals(lastTrackedArtistName));
                    if (sameAsBefore) return;

                    lastTrackedSongName = songName;
                    lastTrackedArtistName = artistName;

                    Log.d(TAG, "Now Playing: " + songName + " by " + artistName);
                    onTrackChanged(songName, artistName);
                })
                .setErrorCallback(throwable -> {
                    // The subscription itself died (e.g. the connection was
                    // dropped) - the watchdog would eventually notice via
                    // isConnected(), but reconnecting immediately means no
                    // gap in auto-switching while waiting for the next poll.
                    Log.e(TAG, "Player state subscription error, reconnecting", throwable);
                    spotifyAppRemote = null;
                    connectToAppRemote();
                });
    }

    private void onTrackChanged(String songName, String artistName) {
        if (systemEq == null) return;

        SelectedEqualizer match = resolveDataSource(findPresetForTrack(songName, artistName));

        if (match != null) {
            systemEq.setEnabled(true);
            applyPreset(match);
        } else {
            systemEq.setEnabled(false);
            currentEq = null;
            if (listener != null) listener.onStateChanged();
        }

        updateNotification(match);
    }

    private SelectedEqualizer findPresetForTrack(String songName, String artistName) {
        if (songName == null) return null;
        String normalizedName = songName.trim().toLowerCase(Locale.US);
        String normalizedArtist = artistName == null ? "" : artistName.trim().toLowerCase(Locale.US);

        for (SelectedEqualizer eq : presets) {
            if (eq.getType() != 0) continue;
            String eqName = eq.getName() == null ? "" : eq.getName().trim().toLowerCase(Locale.US);
            String eqArtist = eq.getArtist() == null ? "" : eq.getArtist().trim().toLowerCase(Locale.US);
            if (eqName.equals(normalizedName) && eqArtist.equals(normalizedArtist)) {
                return eq;
            }
        }
        return null;
    }

    /**
     * Follows linkedPresetId to the real data owner - same logic as
     * EqualizerEditorFragment's resolveDataSource, duplicated here rather
     * than shared since the two classes keep independent copies of the
     * preset list. Matters a lot for this specific matching path: a song
     * already imported from a Spotify playlist that's also imported as a
     * local file becomes a linked duplicate (existing dedup behavior, see
     * findMatchingPreset), and only the real owner's bandLevels reflect
     * edits made since - the duplicate's own copy can go stale. Applying it
     * unresolved would silently apply an outdated EQ.
     */
    private SelectedEqualizer resolveDataSource(SelectedEqualizer eq) {
        if (eq == null || eq.getLinkedPresetId() == null) return eq;
        for (SelectedEqualizer candidate : presets) {
            if (eq.getLinkedPresetId().equals(candidate.getId())) {
                return candidate;
            }
        }
        return eq;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Spotify auto-EQ", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps your EQ preset in sync with what's playing on Spotify");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(SelectedEqualizer active) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = active != null
                ? "Applying \"" + active.getDisplayName() + "\""
                : "Waiting for a song with a matching preset";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Auto-EQ is running")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(SelectedEqualizer active) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(active));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (dataHandler != null) {
            dataHandler.stopListening();
        }
        if (spotifyAppRemote != null && spotifyAppRemote.isConnected()) {
            SpotifyAppRemote.disconnect(spotifyAppRemote);
        }
        if (systemEq != null) {
            systemEq.release();
            systemEq = null;
        }
    }
}