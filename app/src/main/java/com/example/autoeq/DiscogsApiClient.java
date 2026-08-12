package com.example.autoeq;

import android.os.Handler;
import android.os.Looper;
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
 * Fallback genre source for songs LastFmApiClient found no tags for -
 * Discogs catalogs releases (physical and digital) against a curated
 * genre+style taxonomy rather than community folksonomy, which tends to be
 * cleaner and cover more ground, but it has no per-track tag endpoint the
 * way Last.fm does: this searches releases by artist+track and reads
 * genre/style off the single best (first, relevance-ranked) match.
 *
 * Needs a free personal access token from
 * https://www.discogs.com/settings/developers ("Generate new token" - no
 * app registration/review needed), set as DISCOGS_TOKEN in local.properties
 * the same way SPOTIFY_CLIENT_ID and LASTFM_API_KEY already are.
 *
 * Discogs allows 60 requests/minute with a token (25/minute without one) -
 * since this is only called for the subset of songs Last.fm already missed,
 * that's usually fine, but a run of consecutive misses could still burst
 * past it if fired back to back. Rather than push that concern onto every
 * caller, this class paces its own requests internally (one instance =
 * one shared 60/min budget), so callers can just fire requests whenever
 * they have one ready.
 */
public class DiscogsApiClient {
    private static final String TAG = "DiscogsApiClient";
    private static final String BASE_URL = "https://api.discogs.com/database/search";
    // Discogs asks every client to identify itself - generic/unidentified
    // traffic is more likely to be throttled or blocked outright.
    private static final String USER_AGENT = "AutoEQEqualizerApp/1.0";
    // 1100ms, not the theoretical 1000ms floor for 60/min - a small margin so
    // normal jitter doesn't tip a borderline request over Discogs' moving-
    // average limit and draw a 429.
    private static final long MIN_REQUEST_SPACING_MS = 1100;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long nextRequestAtMs = 0;

    public interface GenreTagsCallback {
        /** Always called, even on a network error, an API error, or no search match - tags is simply empty in every one of those cases. */
        void onResult(List<String> tags);
    }

    /** Must be called from the main thread - the internal pacing queue assumes a single caller thread. */
    public void fetchGenreTags(String token, String artist, String track, GenreTagsCallback callback) {
        long now = System.currentTimeMillis();
        long delay = Math.max(0, nextRequestAtMs - now);
        nextRequestAtMs = Math.max(now, nextRequestAtMs) + MIN_REQUEST_SPACING_MS;
        mainHandler.postDelayed(() -> doFetch(token, artist, track, callback), delay);
    }

    private void doFetch(String token, String artist, String track, GenreTagsCallback callback) {
        HttpUrl base = HttpUrl.parse(BASE_URL);
        HttpUrl url = base.newBuilder()
                .addQueryParameter("artist", artist)
                .addQueryParameter("track", track)
                .addQueryParameter("type", "release")
                .addQueryParameter("per_page", "1")
                .build();

        Request.Builder requestBuilder = new Request.Builder().url(url).header("User-Agent", USER_AGENT);
        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "Discogs token=" + token);
        }

        httpClient.newCall(requestBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Failed to search for \"" + track + "\" by " + artist, e);
                callback.onResult(new ArrayList<>());
            }

            @Override
            public void onResponse(Call call, Response response) {
                List<String> tags = new ArrayList<>();
                try (Response r = response) {
                    if (r.isSuccessful() && r.body() != null) {
                        JsonObject body = JsonParser.parseString(r.body().string()).getAsJsonObject();
                        JsonArray results = body.getAsJsonArray("results");
                        if (results != null && results.size() > 0) {
                            JsonObject topResult = results.get(0).getAsJsonObject();
                            addAll(tags, topResult.getAsJsonArray("genre"));
                            addAll(tags, topResult.getAsJsonArray("style"));
                        }
                    } else {
                        Log.w(TAG, "Discogs request failed for \"" + track + "\" by " + artist + ": HTTP " + r.code());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse Discogs response for \"" + track + "\" by " + artist, e);
                }
                callback.onResult(tags);
            }
        });
    }

    private static void addAll(List<String> out, JsonArray array) {
        if (array == null) return;
        for (JsonElement el : array) {
            if (el != null && !el.isJsonNull()) out.add(el.getAsString());
        }
    }
}
