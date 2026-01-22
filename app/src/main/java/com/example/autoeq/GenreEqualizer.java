package com.example.autoeq;
import java.util.Arrays;

public class GenreEqualizer{
    String genreName;
    int[] centerFreqmHz;
    short[] levelsMb;

    public GenreEqualizer(String genreName, int[] centerFreqmHz, short[] levelsMb) {
        this.genreName = genreName;
        this.centerFreqmHz = centerFreqmHz;
        this.levelsMb = levelsMb;
    }

    public String getGenreName() {
        return genreName;
    }

    public void setGenreName(String genreName) {
        this.genreName = genreName;
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

    public void setLevelsMB(short[] levelsMb) {
        this.levelsMb = levelsMb;
    }
}
