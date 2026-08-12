package com.example.autoeq;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Minimal client for the two Spotify Web API endpoints the playlist-import
 * feature needs - not a full SDK wrapper, just enough to list the user's
 * playlists and read a playlist's tracks. Callbacks fire on a background
 * thread (OkHttp's dispatcher), same as everywhere else Firebase callbacks
 * are used in this app - callers need to hop back to the UI thread themselves.
 */
public class SpotifyWebApiClient {
    private static final String TAG = "SpotifyWebApiClient";
    private static final String BASE_URL = "https://api.spotify.com/v1";
    private final OkHttpClient httpClient = new OkHttpClient();

    public interface PlaylistsCallback {
        void onSuccess(List<SpotifyPlaylist> playlists);
        void onFailure(Exception e);
    }

    public interface TracksCallback {
        void onSuccess(List<SpotifyTrack> tracks);
        void onFailure(Exception e);
        /** Called after each page (Spotify caps pages at 50) - total is -1 if the response didn't include one. Lets callers show real fetch progress instead of an unbounded spinner. */
        void onProgress(int fetchedSoFar, int total);
    }

    public static class SpotifyPlaylist {
        public final String id;
        public final String name;
        public SpotifyPlaylist(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static class SpotifyTrack {
        public final String name;
        public final String artist;
        public final String albumArtUrl; // smallest size Spotify offers (usually 64x64) - null if none present
        public SpotifyTrack(String name, String artist, String albumArtUrl) {
            this.name = name;
            this.artist = artist;
            this.albumArtUrl = albumArtUrl;
        }
    }

    /**
     * Spotify error responses are {"error": {"status": ..., "message": "..."}}
     * - the message is what actually explains a failure (e.g. "User not
     * registered in the Developer Dashboard" for accounts not allow-listed
     * on an app still in Development Mode), so surface it instead of just
     * the bare status code. Falls back to the code alone if the body isn't
     * that shape.
     */
    private static String describeError(Response r) {
        String base = "HTTP " + r.code();
        try {
            String bodyStr = r.body() != null ? r.body().string() : null;
            if (bodyStr == null || bodyStr.isEmpty()) return base;
            JsonObject error = JsonParser.parseString(bodyStr).getAsJsonObject().getAsJsonObject("error");
            if (error != null && error.has("message") && !error.get("message").isJsonNull()) {
                return base + " (" + error.get("message").getAsString() + ")";
            }
        } catch (Exception ignored) {
            // Body wasn't the expected {"error": {...}} shape - fall back to the bare code.
        }
        return base;
    }

    /** GET /me/playlists - the current user's own playlists. */
    public void fetchUserPlaylists(String accessToken, PlaylistsCallback callback) {
        Request request = new Request.Builder()
                .url(BASE_URL + "/me/playlists?limit=50")
                .header("Authorization", "Bearer " + accessToken)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to fetch playlists", e);
                callback.onFailure(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        callback.onFailure(new IOException("Spotify playlists request failed: " + describeError(r)));
                        return;
                    }
                    JsonObject body = JsonParser.parseString(r.body().string()).getAsJsonObject();
                    List<SpotifyPlaylist> playlists = new ArrayList<>();
                    JsonArray items = body.getAsJsonArray("items");
                    if (items != null) {
                        for (JsonElement el : items) {
                            JsonObject obj = el.getAsJsonObject();
                            String id = obj.get("id").getAsString();
                            String name = obj.get("name").getAsString();
                            playlists.add(new SpotifyPlaylist(id, name));
                        }
                    }
                    callback.onSuccess(playlists);
                } catch (Exception e) {
                    callback.onFailure(e);
                }
            }
        });
    }

    /**
     * GET /playlists/{id}/items - every track in the playlist, following
     * pagination (Spotify caps each page at 50) until all pages are read.
     * Skips episodes and local files, which don't have normal track/artist
     * data to build a preset from. Uses the current (non-deprecated) /items
     * endpoint - the older /tracks endpoint is deprecated as of the
     * February 2026 Web API changes.
     */
    public void fetchPlaylistTracks(String accessToken, String playlistId, TracksCallback callback) {
        List<SpotifyTrack> collected = new ArrayList<>();
        fetchTracksPage(accessToken, BASE_URL + "/playlists/" + playlistId + "/items?limit=50", collected, callback);
    }

    /**
     * Spotify's album.images array is ordered largest-first (typically 640,
     * 300, then 64px). Every use of this in the app renders at 24dp, so the
     * smallest (last) entry is picked deliberately - it's plenty of
     * resolution for the target size and keeps the download tiny.
     */
    private static String extractSmallestAlbumArtUrl(JsonObject track) {
        JsonElement albumEl = track.get("album");
        if (albumEl == null || !albumEl.isJsonObject()) return null;

        JsonArray images = albumEl.getAsJsonObject().getAsJsonArray("images");
        if (images == null || images.size() == 0) return null;

        JsonElement smallest = images.get(images.size() - 1);
        JsonElement urlEl = smallest.getAsJsonObject().get("url");
        return urlEl != null && !urlEl.isJsonNull() ? urlEl.getAsString() : null;
    }

    private void fetchTracksPage(String accessToken, String url, List<SpotifyTrack> collected, TracksCallback callback) {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to fetch playlist tracks", e);
                callback.onFailure(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        callback.onFailure(new IOException("Spotify playlist tracks request failed: " + describeError(r)));
                        return;
                    }
                    JsonObject body = JsonParser.parseString(r.body().string()).getAsJsonObject();
                    JsonArray items = body.getAsJsonArray("items");
                    if (items != null) {
                        for (JsonElement el : items) {
                            JsonObject entry = el.getAsJsonObject();
                            JsonObject track = (entry.has("item") && !entry.get("item").isJsonNull())
                                    ? entry.getAsJsonObject("item")
                                    : null;
                            if (track == null) continue;

                            JsonElement typeEl = track.get("type");
                            if (typeEl == null || !"track".equals(typeEl.getAsString())) continue; // skip episodes

                            JsonElement isLocalEl = track.get("is_local");
                            if (isLocalEl != null && isLocalEl.getAsBoolean()) continue; // skip local files

                            String name = track.has("name") && !track.get("name").isJsonNull()
                                    ? track.get("name").getAsString() : null;
                            JsonArray artists = track.getAsJsonArray("artists");
                            String artist = (artists != null && artists.size() > 0)
                                    ? artists.get(0).getAsJsonObject().get("name").getAsString()
                                    : null;
                            String albumArtUrl = extractSmallestAlbumArtUrl(track);

                            if (name != null && artist != null) {
                                collected.add(new SpotifyTrack(name, artist, albumArtUrl));
                            }
                        }
                    }

                    JsonElement totalEl = body.get("total");
                    int total = totalEl != null && !totalEl.isJsonNull() ? totalEl.getAsInt() : -1;
                    callback.onProgress(collected.size(), total);

                    JsonElement nextEl = body.get("next");
                    if (nextEl != null && !nextEl.isJsonNull()) {
                        fetchTracksPage(accessToken, nextEl.getAsString(), collected, callback);
                    } else {
                        callback.onSuccess(collected);
                    }
                } catch (Exception e) {
                    callback.onFailure(e);
                }
            }
        });
    }
}