package com.example.autoeq;

import com.google.firebase.database.Exclude;

public class Folder {
    private String id;
    private String name;
    private String spotifyPlaylistId; // null if manually created, set if imported from a playlist

    public Folder() {}

    public Folder(String name, String spotifyPlaylistId) {
        this.name = name;
        this.spotifyPlaylistId = spotifyPlaylistId;
    }

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSpotifyPlaylistId() {
        return spotifyPlaylistId;
    }

    public void setSpotifyPlaylistId(String spotifyPlaylistId) {
        this.spotifyPlaylistId = spotifyPlaylistId;
    }
}