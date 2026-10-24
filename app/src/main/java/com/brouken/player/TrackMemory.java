package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Tracks;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

// Remembers audio and subtitle choices by kind rather than by track number, which differs between files:
// a language, plus SDH and forced for subtitles, or "off". Per show (keyed by IMDb id) for both, and the last
// subtitle choice anywhere as the fallback for titles never seen before.
final class TrackMemory {

    static final class Choice {
        final boolean off;
        @Nullable final String language;
        final boolean sdh;
        final boolean forced;

        private Choice(boolean off, @Nullable String language, boolean sdh, boolean forced) {
            this.off = off;
            this.language = language;
            this.sdh = sdh;
            this.forced = forced;
        }

        static Choice off() {
            return new Choice(true, null, false, false);
        }

        static Choice of(Format format) {
            return new Choice(false, normalize(format.language), isSdh(format), isForced(format));
        }

        String toJson() {
            final JSONObject json = new JSONObject();
            try {
                json.put("off", off);
                json.put("language", language == null ? JSONObject.NULL : language);
                json.put("sdh", sdh);
                json.put("forced", forced);
            } catch (JSONException ignored) {
            }
            return json.toString();
        }

        @Nullable
        static Choice fromJson(@Nullable String value) {
            if (value == null) {
                return null;
            }
            try {
                final JSONObject json = new JSONObject(value);
                final String language = json.isNull("language") ? null : json.optString("language", null);
                return new Choice(json.optBoolean("off"), language, json.optBoolean("sdh"), json.optBoolean("forced"));
            } catch (JSONException e) {
                return null;
            }
        }
    }

    private static final String AUDIO = "audio:";
    private static final String TEXT = "text:";
    private static final String GLOBAL_TEXT = "text:*";

    private final SharedPreferences preferences;

    TrackMemory(Context context) {
        preferences = context.getSharedPreferences("track_memory", Context.MODE_PRIVATE);
    }

    void rememberAudio(@Nullable String showId, Format format) {
        if (showId != null) {
            preferences.edit().putString(AUDIO + showId, Choice.of(format).toJson()).apply();
        }
    }

    void rememberText(@Nullable String showId, Choice choice) {
        final SharedPreferences.Editor editor = preferences.edit().putString(GLOBAL_TEXT, choice.toJson());
        if (showId != null) {
            editor.putString(TEXT + showId, choice.toJson());
        }
        editor.apply();
    }

    @Nullable
    Choice audioFor(@Nullable String showId) {
        return showId == null ? null : Choice.fromJson(preferences.getString(AUDIO + showId, null));
    }

    @Nullable
    Choice textForShow(@Nullable String showId) {
        return showId == null ? null : Choice.fromJson(preferences.getString(TEXT + showId, null));
    }

    @Nullable
    Choice globalText() {
        return Choice.fromJson(preferences.getString(GLOBAL_TEXT, null));
    }

    // The subtitle track that best fits a remembered choice: same language required, then SDH and forced as
    // remembered. Null when the file has nothing in that language.
    @Nullable
    static int[] findText(Tracks tracks, Choice choice) {
        int[] best = null;
        int bestScore = -1;
        int groupIndex = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_TEXT) {
                for (int i = 0; i < group.length; i++) {
                    final Format format = group.getTrackFormat(i);
                    if (!group.isTrackSupported(i) || choice.language == null || !choice.language.equals(normalize(format.language))) {
                        continue;
                    }
                    final int score = (isSdh(format) == choice.sdh ? 2 : 0) + (isForced(format) == choice.forced ? 4 : 0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = new int[] {groupIndex, i};
                    }
                }
            }
            groupIndex++;
        }
        return best;
    }

    @Nullable
    private static String normalize(@Nullable String language) {
        if (language == null || language.isEmpty() || "und".equals(language)) {
            return null;
        }
        // "en", "eng" and "en-GB" are the same language for this purpose
        try {
            return new Locale(language.split("[-_]")[0]).getISO3Language();
        } catch (RuntimeException e) {
            return language.toLowerCase(Locale.ROOT);
        }
    }

    static boolean isSdh(Format format) {
        final String label = format.label == null ? "" : format.label.toLowerCase(Locale.ROOT);
        return (format.roleFlags & C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 || label.contains("sdh") || label.contains("hearing");
    }

    static boolean isForced(Format format) {
        final String label = format.label == null ? "" : format.label.toLowerCase(Locale.ROOT);
        return (format.selectionFlags & C.SELECTION_FLAG_FORCED) != 0 || label.contains("forced");
    }
}
