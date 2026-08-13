package com.example.autoeq;

import com.google.firebase.database.Exclude;

public class Folder {
    private String id;
    private String name;
    private String spotifyPlaylistId; // null if manually created, set if imported from a playlist
    private String source; // "local" for the single reusable folder local file imports land in (see EqualizerEditorFragment#findOrCreateLocalFilesFolder); null otherwise
    private String snapshotId; // Spotify's playlist snapshot_id as of the last import/sync - null for non-Spotify folders. Changes only when the playlist's contents change, so comparing this is a cheap way to skip a full re-sync when nothing has.

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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }
}