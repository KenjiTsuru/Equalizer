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
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Minimal client for Last.fm's track.gettoptags - stands in for Spotify's own
 * artist "genres" field (see GenrePresets), which is now flagged deprecated
 * by Spotify and empirically comes back empty for the large majority of real
 * artists (confirmed against a real playlist: 0/7 artists had any genre
 * data). Last.fm's tags are crowd-sourced per TRACK rather than per artist,
 * which also gets genre matching down to song granularity instead of
 * assuming every song by an artist shares one genre.
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
    private final OkHttpClient httpClient = new OkHttpClient();

    public interface TrackTagsCallback {
        /** Always called, even on a network error, an API error, or a track Last.fm has no match for - tags is simply empty in every one of those cases, indistinguishable from a real "no tags" result. */
        void onResult(List<String> tags);
    }

    /**
     * GET track.gettoptags - the community tags for one specific song, most
     * of which double as genre labels (e.g. "hip hop", "synthpop") mixed in
     * with plenty of non-genre ones ("favorites", "2016", "female vocalists")
     * - GenrePresets.matchTags simply won't match those against anything, so
     * no separate filtering step is needed here. autocorrect=1 lets Last.fm
     * fix minor spelling/formatting differences between what Spotify calls a
     * track/artist and what Last.fm has on file.
     */
    public void fetchTrackTags(String apiKey, String artist, String track, TrackTagsCallback callback) {
        HttpUrl base = HttpUrl.parse(BASE_URL);
        HttpUrl url = base.newBuilder()
                .addQueryParameter("method", "track.gettoptags")
                .addQueryParameter("artist", artist)
                .addQueryParameter("track", track)
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("autocorrect", "1")
                .addQueryParameter("format", "json")
                .build();

        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Failed to fetch tags for \"" + track + "\" by " + artist, e);
                callback.onResult(new ArrayList<>());
            }

            @Override
            public void onResponse(Call call, Response response) {
                List<String> tags = new ArrayList<>();
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        JsonObject body = JsonParser.parseString(r.body().string()).getAsJsonObject();
                        if (body.has("error")) {
                            Log.i(TAG, "No tags for \"" + track + "\" by " + artist + ": "
                                    + (body.has("message") ? body.get("message").getAsString() : body.get("error").toString()));
                        } else {
                            collectTagNames(body.getAsJsonObject("toptags"), tags);
                        }
                    } else {
                        Log.w(TAG, "Last.fm request failed for \"" + track + "\" by " + artist + ": HTTP " + r.code());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse tags for \"" + track + "\" by " + artist, e);
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
