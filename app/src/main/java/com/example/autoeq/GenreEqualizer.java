package com.example.autoeq;

public class GenreEqualizer {
    private String name; // Can be Song Name or Genre Name
    private String artist; // Artist name (used if type is Song and Artist)
    private short type; // 0 for Song and Artist, 1 for Genre
    private int[] centerFreqmHz;
    private short[] levelsMb;

    public GenreEqualizer(String name, String artist, short type, int[] centerFreqmHz, short[] levelsMb) {
        this.name = name;
        this.artist = artist;
        this.type = type;
        this.centerFreqmHz = centerFreqmHz;
        this.levelsMb = levelsMb;
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

    public short getType() {
        return type;
    }

    public void setType(short type) {
        this.type = type;
    }

    public int[] getCenterFreqmHz() {
        return centerFreqmHz;
    }

    public void setCenterFreqmHz(int[] centerFreqmHz) {
        this.centerFreqmHz = centerFreqmHz;
    }

    public short[] getLevelsMb() {
        return levelsMb;
    }

    public void setLevelsMb(short[] levelsMb) {
        this.levelsMb = levelsMb;
    }

    public String getDisplayName() {
        if (type == 0 && artist != null && !artist.isEmpty()) {
            return name + " - " + artist;
        }
        return name;
    }
}
