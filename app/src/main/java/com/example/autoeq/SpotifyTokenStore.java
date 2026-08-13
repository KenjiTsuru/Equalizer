package com.example.autoeq;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Persists the Spotify Web API access/refresh token pair across app
 * restarts (Authorization Code + PKCE - see
 * MainActivity#requestSpotifyWebApiToken for how the initial pair is
 * obtained) and silently renews an expired access token using the refresh
 * token, entirely in the background with no browser/login UI involved.
 *
 * This is what closes the gap the old Implicit Grant flow had: that flow
 * never issued a refresh token at all (by design - it's not meant for
 * anything beyond a short-lived session), so anything needing a token
 * outside the exact app session that logged in - like playlist sync-on-open
 * running after the ~1 hour access token lifetime, or after a full app
 * restart - had no way to get one without re-prompting login.
 *
 * Stored in plain SharedPreferences, not EncryptedSharedPreferences - a
 * reasonable tradeoff for a personal-use app whose only scope is
 * playlist-read, but worth hardening later if that changes.
 */
public class SpotifyTokenStore {
    private static final String TAG = "SpotifyTokenStore";
    private static final String PREFS_NAME = "spotify_token_store";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token";
    // Refresh a little before actual expiry, not right at the edge - avoids
    // a request landing just past expiry due to clock drift or the refresh
    // request's own round-trip time eating into the remaining window.
    private static final long EXPIRY_BUFFER_MS = 60_000L;

    private final SharedPreferences prefs;
    private final OkHttpClient httpClient = NetworkClients.SHARED;

    public interface TokenCallback {
        void onTokenReady(String accessToken);
        /** No stored session (never logged in), or a refresh attempt that failed (revoked, expired refresh token, network down) - either way only an interactive login can recover from here. */
        void onNoValidToken();
    }

    public SpotifyTokenStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void storeTokens(String accessToken, String refreshToken, int expiresInSeconds) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000L);
        // Spotify doesn't always return a fresh refresh_token on every
        // refresh - keep the previous one rather than overwriting it with
        // null and losing the ability to refresh again next time.
        if (refreshToken != null && !refreshToken.isEmpty()) {
            editor.putString(KEY_REFRESH_TOKEN, refreshToken);
        }
        editor.apply();
    }

    /**
     * Returns a usable access token without ever showing UI: the stored one
     * directly if it's still valid, a silently refreshed one if it's expired
     * but a refresh token exists, or onNoValidToken() if neither works out.
     * Callers still need their own interactive-login fallback for that last
     * case - this class never launches one itself. onTokenReady may fire
     * synchronously (valid cached token) or from a background thread
     * (refresh happened) - treat every call as possibly off the main thread.
     */
    public void getValidAccessToken(String clientId, TokenCallback callback) {
        String accessToken = prefs.getString(KEY_ACCESS_TOKEN, null);
        long expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0);

        if (accessToken != null && System.currentTimeMillis() < expiresAt - EXPIRY_BUFFER_MS) {
            callback.onTokenReady(accessToken);
            return;
        }

        String refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null);
        if (refreshToken == null) {
            callback.onNoValidToken();
            return;
        }

        refreshAccessToken(clientId, refreshToken, callback);
    }

    private void refreshAccessToken(String clientId, String refreshToken, TokenCallback callback) {
        RequestBody body = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .build();
        Request request = new Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Token refresh failed (network)", e);
                callback.onNoValidToken();
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        Log.w(TAG, "Token refresh failed: HTTP " + r.code());
                        // A 400/401 here almost always means the refresh
                        // token itself was revoked - clear it so future
                        // calls fail fast instead of retrying a dead token
                        // forever.
                        prefs.edit().remove(KEY_REFRESH_TOKEN).apply();
                        callback.onNoValidToken();
                        return;
                    }
                    JsonObject json = JsonParser.parseString(r.body().string()).getAsJsonObject();
                    String newAccessToken = json.get("access_token").getAsString();
                    int expiresIn = json.has("expires_in") && !json.get("expires_in").isJsonNull()
                            ? json.get("expires_in").getAsInt() : 3600;
                    String newRefreshToken = json.has("refresh_token") && !json.get("refresh_token").isJsonNull()
                            ? json.get("refresh_token").getAsString() : null;

                    storeTokens(newAccessToken, newRefreshToken, expiresIn);
                    callback.onTokenReady(newAccessToken);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse token refresh response", e);
                    callback.onNoValidToken();
                }
            }
        });
    }
}
