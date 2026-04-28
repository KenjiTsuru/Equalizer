package com.example.autoeq;

public class GenreEqualizer {
    private String genreName;
    private short type; // 0 for Song, 1 for Genre, 2 for Artist
    private int[] centerFreqmHz;
    private short[] levelsMb;

    public GenreEqualizer(String genreName, short type, int[] centerFreqmHz, short[] levelsMb) {
        this.genreName = genreName;
        this.type = type;
        this.centerFreqmHz = centerFreqmHz;
        this.levelsMb = levelsMb;
    }

    public String getGenreName() {
        return genreName;
    }

    public void setGenreName(String genreName) {
        this.genreName = genreName;
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
}
