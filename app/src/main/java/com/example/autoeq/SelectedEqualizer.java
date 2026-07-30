package com.example.autoeq;

import com.google.firebase.database.Exclude;

import java.util.List;

public class SelectedEqualizer {
    private String id;
    private String name; // Can be Song Name or Genre Name
    private String artist; // Artist name (used if type is Song and Artist)
    private int type; // 0 for Song and Artist, 1 for Genre
    private List<Integer> bandIds;
    private List<Integer> bandLevels;

    public SelectedEqualizer() {}

    public SelectedEqualizer(String name, String artist, int type, List<Integer> bandIds, List<Integer> bandLevels) {
        this.name = name;
        this.artist = artist;
        this.type = type;
        this.bandIds = bandIds;
        this.bandLevels = bandLevels;
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

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public List<Integer> getBandIds() {
        return bandIds;
    }

    public void setBandIds(List<Integer> bandIds) {
        this.bandIds = bandIds;
    }

    public List<Integer> getBandLevels() {
        return bandLevels;
    }

    public void setBandLevels(List<Integer> levelsMb) {
        this.bandLevels = bandLevels;
    }

    public String getDisplayName() {
        if (type == 0 && artist != null && !artist.isEmpty()) {
            return name + " - " + artist;
        }
        return name;
    }
}
