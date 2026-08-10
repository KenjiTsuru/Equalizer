package com.example.autoeq;

/**
 * The 12-band shape (frequencies, count) shared by SpotifyMonitorService,
 * which owns the live DynamicsProcessing instance, and
 * EqualizerEditorFragment, which builds the seekbar UI around it. Pulled out
 * on its own so the two never drift out of sync with each other.
 */
public final class EqBandConfig {
    public static final int NUM_BANDS = 12;
    public static final float[] BAND_FREQUENCIES_HZ = {
            30f, 50f, 83f, 138f, 229f, 380f, 632f, 1050f, 1744f, 2900f, 4800f, 8000f
    };

    private EqBandConfig() {}
}