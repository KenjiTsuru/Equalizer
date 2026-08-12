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
 *  - Rap/Hip-Hop/Reggae/Latin/Electronic: heavy sub-bass (30-83 Hz), a
 *    low-mid scoop (~229-380 Hz) so the bass doesn't turn muddy, and a
 *    presence bump (~1050-2900 Hz) so vocals still cut through. Rap in
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
    // 30, 50, 83, 138, 229, 380, 632, 1050, 1744, 2900, 4800, 8000 Hz.
    private static final List<GenreBucket> PRIORITY_ORDER = Collections.unmodifiableList(Arrays.asList(
            new GenreBucket("Indie Rock",
                    new int[]{5, 10, 15, 0, 0, 0, 0, 5, 10, 15, 15, 10},
                    "indie rock", "indietronica", "garage rock"),
            new GenreBucket("Hip-Hop",
                    new int[]{50, 60, 50, 10, -15, -20, 0, 15, 20, 15, 10, 15},
                    "hip hop", "hip-hop", "boom bap", "hyphy", "g-funk"),
            // The textbook "bass boost" shape: highest at 30 Hz, sloping
            // steadily down band by band into the treble, per the classic
            // rap-EQ description (loud low end, gradually receding as
            // frequency rises) plus a presence bump for lyric clarity.
            new GenreBucket("Rap",
                    new int[]{60, 55, 45, 30, 15, 0, 5, 20, 25, 15, 10, 5},
                    "rap", "trap", "drill"),
            new GenreBucket("R&B",
                    new int[]{30, 35, 30, 15, 5, 0, 10, 15, 20, 10, 10, 5},
                    "r&b", "rnb", "r-n-b", "urban contemporary"),
            new GenreBucket("Soul",
                    new int[]{15, 20, 25, 20, 15, 5, 5, 15, 20, 15, 10, 5},
                    "soul", "motown"),
            new GenreBucket("Electronic",
                    new int[]{55, 60, 45, 5, -20, -25, -10, 0, 10, 20, 35, 45},
                    "electronic", "edm", "house", "techno", "trance", "dubstep",
                    "electropop", "electronica", "drum and bass", "dnb", "dance"),
            new GenreBucket("Metal",
                    new int[]{25, 30, 30, 10, -10, -25, -10, 10, 25, 35, 30, 25},
                    "metal"),
            new GenreBucket("Punk",
                    new int[]{-5, 0, 10, 10, 0, -10, 0, 20, 30, 30, 25, 20},
                    "punk"),
            // Checked before Country - Discogs' broad genre category is
            // literally named "Folk, World, & Country" as one compound
            // string, which contains "country" as a substring. Without this
            // ordering, genuinely folk/world tags would misroute to Country
            // just because that word happens to appear in the label.
            new GenreBucket("Folk/Acoustic",
                    new int[]{0, 5, 10, 10, 5, 0, 5, 15, 20, 15, 15, 10},
                    "folk", "acoustic", "singer-songwriter"),
            new GenreBucket("Country",
                    new int[]{5, 10, 10, 5, 5, 0, 5, 15, 20, 15, 15, 10},
                    "country"),
            new GenreBucket("Jazz",
                    new int[]{0, 5, 5, 5, 5, 5, 10, 15, 10, 5, 5, 0},
                    "jazz"),
            new GenreBucket("Classical",
                    new int[]{0, 0, 0, 0, 10, 0, 0, 0, 0, 5, 10, 15},
                    "classical", "orchestra", "orchestral"),
            new GenreBucket("Latin",
                    new int[]{35, 40, 30, 5, -10, -10, 0, 15, 20, 20, 25, 20},
                    "latin", "reggaeton", "salsa", "bachata", "banda", "corrido"),
            new GenreBucket("Reggae",
                    new int[]{50, 55, 40, 10, -20, -25, -10, 0, 10, 10, 10, 5},
                    "reggae", "dancehall", "ska"),
            new GenreBucket("Piano",
                    new int[]{0, 0, 5, 10, 5, 0, 0, 5, 10, 20, 15, 10},
                    "piano"),
            new GenreBucket("Rock",
                    new int[]{15, 25, 30, 0, -10, -15, 5, 15, 25, 30, 20, 20},
                    "rock"),
            new GenreBucket("Pop",
                    new int[]{10, 20, 20, 5, 0, -5, 0, 10, 15, 20, 15, 20},
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
