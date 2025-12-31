package com.example.autoeq;
import android.media.audiofx.Equalizer;

public class GenreEqualizer extends Equalizer{
    String genreName;



    /**
     * Class constructor.
     *
     * @param priority     the priority level requested by the application for controlling the Equalizer
     *                     engine. As the same engine can be shared by several applications, this parameter indicates
     *                     how much the requesting application needs control of effect parameters. The normal priority
     *                     is 0, above normal is a positive number, below normal a negative number.
     * @param audioSession system wide unique audio session identifier. The Equalizer will be
     *                     attached to the MediaPlayer or AudioTrack in the same audio session.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     * @throws RuntimeException
     */
    public GenreEqualizer(int priority, int audioSession, String genreName) throws IllegalArgumentException, IllegalStateException, RuntimeException, UnsupportedOperationException {
        super(priority, audioSession);
        this.genreName = genreName;
    }

    public String getGenreName() {
        return genreName;
    }

    public void setGenreName(String genreName) {
        this.genreName = genreName;
    }
}
