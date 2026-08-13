package com.example.autoeq;

import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Minimal client for Last.fm's *.gettoptags family - stands in for Spotify's
 * own artist "genres" field (see GenrePresets), which is now flagged
 * deprecated by Spotify and empirically comes back empty for the large
 * majority of real artists (confirmed against a real playlist: 0/7 artists
 * had any genre data).
 *
 * Three granularities, used as a fallback chain by EqualizerEditorFragment
 * (track, then album, then artist, only moving on when the previous one
 * came back empty) since a track with no tags of its own often still has an
 * album or artist that does:
 *  - fetchTrackTags: tags on that one specific song.
 *  - fetchAlbumTags: tags on the album/EP it's from.
 *  - fetchArtistTags: tags on the artist as a whole - the widest net, but
 *    also the least song-specific (an artist can span genres across their
 *    catalog), which is exactly why it's tried last, not first.
 *
 * Needs a free API key from https://www.last.fm/api/account/create (an
 * "Application name" is all that's required - no callback URL needed). This
 * only makes unauthenticated public GET reads, so no shared secret/OAuth is
 * needed, just the key, set as LASTFM_API_KEY in local.properties the same
 * way SPOTIFY_CLIENT_ID already is.
 */
public class LastFmApiClient {
    private static final String TAG = "LastFmApiClient";
    private static final String BASE_URL = "https://ws.audioscrobbler.com/2.0/";
    private final OkHttpClient httpClient = NetworkClients.SHARED;

    public interface TagsCallback {
        /** Always called, even on a network error, an API error, or no match - tags is simply empty in every one of those cases, indistinguishable from a real "no tags" result. */
        void onResult(List<String> tags);
    }

    /** GET track.gettoptags - community tags for one specific song. */
    public void fetchTrackTags(String apiKey, String artist, String track, TagsCallback callback) {
        HttpUrl url = urlBuilder(apiKey)
                .addQueryParameter("method", "track.gettoptags")
                .addQueryParameter("artist", artist)
                .addQueryParameter("track", track)
                .build();
        fetch(url, "track \"" + track + "\" by " + artist, callback);
    }

    /** GET album.gettoptags - community tags for the album/EP a song is from. */
    public void fetchAlbumTags(String apiKey, String artist, String album, TagsCallback callback) {
        HttpUrl url = urlBuilder(apiKey)
                .addQueryParameter("method", "album.gettoptags")
                .addQueryParameter("artist", artist)
                .addQueryParameter("album", album)
                .build();
        fetch(url, "album \"" + album + "\" by " + artist, callback);
    }

    /** GET artist.gettoptags - community tags for the artist as a whole. */
    public void fetchArtistTags(String apiKey, String artist, TagsCallback callback) {
        HttpUrl url = urlBuilder(apiKey)
                .addQueryParameter("method", "artist.gettoptags")
                .addQueryParameter("artist", artist)
                .build();
        fetch(url, "artist " + artist, callback);
    }

    private static HttpUrl.Builder urlBuilder(String apiKey) {
        // autocorrect=1 lets Last.fm fix minor spelling/formatting differences
        // between what Spotify calls a track/album/artist and what Last.fm
        // has on file.
        return HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("autocorrect", "1")
                .addQueryParameter("format", "json");
    }

    /**
     * Shared request/response handling for all three *.gettoptags calls -
     * tags returned mix genre labels ("hip hop", "synthpop") with plenty of
     * non-genre ones ("favorites", "2016", "female vocalists");
     * GenrePresets.matchTags simply won't match those against anything, so
     * no separate filtering happens here.
     */
    private void fetch(HttpUrl url, String description, TagsCallback callback) {
        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Failed to fetch tags for " + description, e);
                callback.onResult(new ArrayList<>());
            }

            @Override
            public void onResponse(Call call, Response response) {
                List<String> tags = new ArrayList<>();
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        JsonObject body = JsonParser.parseString(r.body().string()).getAsJsonObject();
                        if (body.has("error")) {
                            Log.i(TAG, "No tags for " + description + ": "
                                    + (body.has("message") ? body.get("message").getAsString() : body.get("error").toString()));
                        } else {
                            collectTagNames(body.getAsJsonObject("toptags"), tags);
                        }
                    } else {
                        Log.w(TAG, "Last.fm request failed for " + description + ": HTTP " + r.code());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse tags for " + description, e);
                }
                callback.onResult(tags);
            }
        });
    }

    /**
     * Last.fm's JSON is a thin wrapper over their original XML API, which
     * collapses a single-child list to a lone object instead of a one-item
     * array - so "tag" can be a JSON array (2+ tags), a single JSON object
     * (exactly 1 tag), or absent (0 tags). All three are real, common cases.
     */
    private static void collectTagNames(JsonObject topTags, List<String> out) {
        if (topTags == null || !topTags.has("tag") || topTags.get("tag").isJsonNull()) return;

        JsonElement tagEl = topTags.get("tag");
        if (tagEl.isJsonArray()) {
            for (JsonElement el : tagEl.getAsJsonArray()) {
                addTagName(out, el);
            }
        } else if (tagEl.isJsonObject()) {
            addTagName(out, tagEl);
        }
    }

    private static void addTagName(List<String> out, JsonElement tagEl) {
        JsonElement nameEl = tagEl.getAsJsonObject().get("name");
        if (nameEl != null && !nameEl.isJsonNull()) out.add(nameEl.getAsString());
    }
}
