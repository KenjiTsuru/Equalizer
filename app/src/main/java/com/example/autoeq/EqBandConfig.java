package com.example.autoeq;

/**
 * The 12-band shape (frequencies, count) shared by SpotifyMonitorService,
 * which owns the live DynamicsProcessing instance, and
 * EqualizerEditorFragment, which builds the seekbar UI around it. Pulled out
 * on its own so the two never drift out of sync with each other.
 */
public final class EqBandConfig {
    public static final int NUM_BANDS = 12;
    // Log-spaced across 30 Hz-16000 Hz for bands 0-10 (ratio ~1.77 between
    // consecutive bands); the top band was pulled in - first to 14000, then
    // to 12000 Hz (breaking that ratio slightly) since 16 kHz sits right at
    // or beyond typical adult hearing, and even 14 kHz is only the upper
    // edge of the "air band" mixing/mastering practice actually targets.
    // 12 kHz is the more canonical air/shimmer reference point - clearly
    // audible to virtually everyone, and above the 5-8 kHz sibilance/
    // harshness zone rather than in it, so a boost here reads as openness
    // and brightness instead of harsh or thin.
    public static final float[] BAND_FREQUENCIES_HZ = {
            30f, 53f, 94f, 166f, 294f, 521f, 922f, 1631f, 2887f, 5109f, 9041f, 12000f
    };

    private EqBandConfig() {}
}