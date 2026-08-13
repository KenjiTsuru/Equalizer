package com.example.autoeq;

import okhttp3.OkHttpClient;

/**
 * One OkHttpClient shared by every network call the app makes (Spotify Web
 * API, Last.fm, Discogs, Spotify token refresh), instead of each client
 * class creating its own. This isn't just tidiness: per OkHttp's own docs,
 * each OkHttpClient instance owns its own connection pool and dispatcher
 * thread pool, so instantiating several needlessly multiplies idle threads
 * and held-open connections that a single shared instance would otherwise
 * reuse for free.
 */
final class NetworkClients {
    static final OkHttpClient SHARED = new OkHttpClient();

    private NetworkClients() {}
}
