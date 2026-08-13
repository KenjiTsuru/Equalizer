package com.example.autoeq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Hardcoded generic 12-band starting points, one per broad genre bucket, used
 * by the playlist-import "apply genre EQ?" flow (see
 * EqualizerEditorFragment#showGenreEqPromptDialog). Band levels line up
 * 1:1 with EqBandConfig.BAND_FREQUENCIES_HZ and use the same tenths-of-a-dB
 * unit as SelectedEqualizer.bandLevels (e.g. 35 = 3.5 dB), clamped to
 * +/-120 (the app's +/-12 dB range) - these are deliberately mild/generic
 * since they're meant as an editable starting point, not a final mix.
 *
 * Tag data (from LastFmApiClient - see that class for why Spotify's own
 * artist "genres" field isn't used) is a free-form folksonomy rather than a
 * small fixed list - a single track can carry dozens of tags, most of them
 * not genre-related at all ("favorites", "2016", "female vocalists"). Each
 * bucket below instead carries a set of keyword substrings pulled from how
 * tracks actually get tagged in practice, checked in priority order (most
 * specific bucket first) so e.g. "indie rock" resolves to Indie Rock rather
 * than the broader Rock catch-all, and "pop rap"/"trap" resolve to Rap
 * rather than the broad Pop catch-all.
 *
 * The curves themselves follow the shapes most commonly recommended for each
 * genre by mainstream EQ guides (headphonesaddict.com's genre EQ guide,
 * Elite Auto Gear's car-audio genre tuning guide, and similar), adapted onto
 * this app's fixed 12 bands rather than invented from scratch:
 *  - Rap/Hip-Hop/Reggae/Latin/Electronic: heavy sub-bass (30-94 Hz), a
 *    low-mid scoop (~294-521 Hz) so the bass doesn't turn muddy, and a
 *    presence bump (~1631-5109 Hz) so vocals still cut through. Rap in
 *    particular is the textbook "bass boost" shape - highest at 30 Hz,
 *    sloping down band by band into the treble.
 *  - Rock/Metal/Punk: boosted low-end punch plus boosted presence/treble
 *    (guitars, cymbals) with a mid dip between them - the classic "smile"/V
 *    curve loudness-war rock mixes are tuned for.
 *  - R&B/Soul/Country/Folk/Jazz/Piano: comparatively flat - warmth in the
 *    low-mids and a gentle vocal/instrument presence lift, no aggressive
 *    bass or treble, since these genres translate worse under a scooped mix.
 *  - Classical: closest to flat of all of them - guides consistently say to
 *    leave classical mixes untouched beyond a hint of air on top.
 */
public final class GenrePresets {

    public static final class GenreBucket {
        public final String name;
        final String[] keywords;
        public final int[] bandLevels;

        GenreBucket(String name, int[] bandLevels, String... keywords) {
            this.name = name;
            this.bandLevels = bandLevels;
            this.keywords = keywords;
        }
    }

    // Order is match priority, most specific first - NOT display order.
    // Index into each bandLevels array lines up with EqBandConfig.BAND_FREQUENCIES_HZ:
    // 30, 53, 94, 166, 294, 521, 922, 1631, 2887, 5109, 9041, 12000 Hz.
    //
    // Retuned for the 30-16000 Hz range this app originally moved to
    // (bands 0-8 kept their original values unchanged, since the frequency
    // each one represents only shifted mildly and stayed within the same
    // perceptual zone - bass/low-mid/mid/presence). Bands 9-11 got real
    // rework, since those shifted much further - band 11 in particular moved
    // from 8000 Hz (a safe, pleasant "air" frequency every genre could take
    // a gentle boost at) to what was then 16000 Hz, right at or beyond
    // typical adult hearing (many adults need it 30-70 dB louder than
    // midrange just to perceive it - Miracle-Ear/audiometric data) and
    // squarely the zone where a boost stops sounding like "sparkle" and
    // starts sounding thin/shrill (per guitar/vocal EQ guides on 14-15kHz+
    // boosts). Band 11 itself later moved down further, first to 14000 Hz
    // and then to 12000 Hz - the more canonical "air" reference point
    // mixing/mastering practice actually targets, above the 5-8kHz
    // sibilance/harshness zone rather than in it - but
    // the band levels below were already tuned conservative for that top
    // band and didn't need revisiting: kept minimal almost everywhere, with
    // the "brightness" most genres previously got from it shifted down to
    // band 10 (9041 Hz) instead, still comfortably audible and still a real
    // cymbal-shimmer zone.
    private static final List<GenreBucket> PRIORITY_ORDER = Collections.unmodifiableList(Arrays.asList(
            new GenreBucket("Indie Rock",
                    new int[]{5, 5, 10, 0, 0, 0, 0, 5, 5, 10, 10, 0},
                    "indie rock", "indietronica", "garage rock"),
            new GenreBucket("Hip-Hop",
                    new int[]{35, 40, 35, 5, -10, -15, 0, 10, 15, 10, 8, 0},
                    "hip hop", "hip-hop", "hiphop", "boom bap", "hyphy", "g-funk", "sampling"),
            // The textbook "bass boost" shape: highest at 30 Hz, sloping
            // steadily down band by band into the treble, per the classic
            // rap-EQ description (loud low end, gradually receding as
            // frequency rises) plus a presence bump for lyric clarity.
            new GenreBucket("Rap",
                    new int[]{40, 40, 30, 20, 10, 0, 5, 15, 20, 10, 5, 0},
                    "rap", "trap", "drill"),
            new GenreBucket("R&B",
                    new int[]{20, 25, 20, 10, 5, 0, 5, 10, 15, 5, 5, 0},
                    "r&b", "rnb", "r-n-b", "urban contemporary", "urban", "rhythmic"),
            new GenreBucket("Soul",
                    new int[]{10, 15, 20, 15, 10, 5, 5, 10, 15, 10, 5, 0},
                    "soul", "motown"),
            new GenreBucket("Electronic",
                    new int[]{40, 40, 30, 5, -15, -20, -5, 0, 5, 15, 18, 8},
                    "electronic", "edm", "house", "techno", "trance", "dubstep",
                    "electropop", "electronica", "drum and bass", "dnb", "dance"),
            new GenreBucket("Metal",
                    new int[]{20, 20, 20, 5, -5, -20, -5, 5, 20, 25, 15, 5},
                    "metal"),
            new GenreBucket("Punk",
                    new int[]{-5, 0, 5, 5, 0, -5, 0, 15, 20, 20, 15, 5},
                    "punk"),
            // Checked before Country - Discogs' broad genre category is
            // literally named "Folk, World, & Country" as one compound
            // string, which contains "country" as a substring. Without this
            // ordering, genuinely folk/world tags would misroute to Country
            // just because that word happens to appear in the label.
            new GenreBucket("Folk/Acoustic",
                    new int[]{0, 5, 5, 5, 5, 0, 5, 10, 15, 10, 10, 0},
                    "folk", "acoustic", "singer-songwriter"),
            new GenreBucket("Country",
                    new int[]{5, 5, 5, 5, 5, 0, 5, 10, 15, 10, 10, 0},
                    "country"),
            new GenreBucket("Jazz",
                    new int[]{0, 5, 5, 5, 5, 5, 5, 10, 5, 5, 5, 0},
                    "jazz"),
            // Classical's "hint of air on top" moved from band 11 to band 10
            // - 16 kHz is too close to inaudible for a "hint" to register as
            // anything but a wasted, slightly-riskier boost; 9041 Hz still
            // reads as orchestral string/hall "air" and actually gets heard.
            new GenreBucket("Classical",
                    new int[]{0, 0, 0, 0, 5, 0, 0, 0, 0, 5, 8, 3},
                    "classical", "orchestra", "orchestral"),
            new GenreBucket("Latin",
                    new int[]{25, 30, 20, 5, -5, -5, 0, 10, 15, 15, 15, 5},
                    "latin", "reggaeton", "salsa", "bachata", "banda", "corrido"),
            new GenreBucket("Reggae",
                    new int[]{35, 40, 30, 5, -15, -20, -5, 0, 5, 5, 5, 0},
                    "reggae", "dancehall", "ska"),
            new GenreBucket("Piano",
                    new int[]{0, 0, 5, 5, 5, 0, 0, 5, 5, 15, 10, 0},
                    "piano"),
            // Checked late (after every genre-specific bucket, right before
            // the broad Rock/Pop catch-alls) because "dark" alone is too
            // generic to trust first - a tag list that also contains "trap"
            // or "techno" should still resolve to Rap or Electronic via
            // those keywords before ever reaching this one. Only a track
            // whose tags are genuinely just mood/space words like "dark",
            // "atmospheric", "ethereal" with nothing more specific lands here.
            // Band 11 kept slightly higher than most other genres here (8,
            // not 0-5) since airy high-frequency shimmer is a defining
            // characteristic of this one rather than an incidental accent.
            new GenreBucket("Ambient/Atmospheric",
                    new int[]{5, 10, 10, 5, 5, 0, 0, 0, 5, 5, 15, 8},
                    "ambient", "atmospheric", "ethereal", "dream pop", "shoegaze",
                    "darkwave", "dark ambient", "downtempo", "dark"),
            new GenreBucket("Rock",
                    new int[]{10, 20, 20, 0, -5, -10, 5, 10, 20, 20, 15, 5},
                    "rock"),
            new GenreBucket("Pop",
                    new int[]{5, 15, 15, 5, 0, -5, 0, 5, 10, 15, 10, 5},
                    "pop")
    ));

    /** Display list, alphabetical - independent of match priority order above. */
    public static List<String> allGenreNames() {
        List<String> names = new ArrayList<>();
        for (GenreBucket bucket : PRIORITY_ORDER) names.add(bucket.name);
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static int[] bandLevelsFor(String genreName) {
        for (GenreBucket bucket : PRIORITY_ORDER) {
            if (bucket.name.equalsIgnoreCase(genreName)) return bucket.bandLevels;
        }
        return null;
    }

    /**
     * Matches a track's raw tags (e.g. Last.fm's per-song community tags -
     * ["hip hop", "rap", "favorites", "2016"]) to the single best bucket,
     * checking buckets in priority order and every one of the track's tags
     * at each bucket before moving to the next - so a more specific bucket
     * always wins regardless of tag order, and non-genre tags ("favorites")
     * simply never match anything. Returns null if nothing matches (no tags,
     * or none of them are genre-like - caller should fall back to zeroed
     * levels, same as today).
     */
    public static String matchTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;

        List<String> lowerTags = new ArrayList<>(tags.size());
        for (String tag : tags) {
            if (tag != null) lowerTags.add(tag.toLowerCase(Locale.US));
        }

        for (GenreBucket bucket : PRIORITY_ORDER) {
            for (String tag : lowerTags) {
                for (String keyword : bucket.keywords) {
                    if (tag.contains(keyword)) return bucket.name;
                }
            }
        }
        return null;
    }

    private GenrePresets() {}
}
