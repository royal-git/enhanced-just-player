package com.brouken.player;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.media3.common.C;
import androidx.media3.extractor.metadata.Chapter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// Parts of a video worth skipping: the intro, a recap, the credits. From the file's own chapters when they are
// named for it, otherwise from TheIntroDB (crowd-sourced timestamps, looked up by IMDb id and episode).
final class SkipSegments {

    enum Kind {
        INTRO("Skip intro"), RECAP("Skip recap"), CREDITS("Skip credits");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    static final class Segment {
        final Kind kind;
        final long startMs;
        // C.TIME_END_OF_SOURCE when the segment runs to the end of the video
        final long endMs;

        Segment(Kind kind, long startMs, long endMs) {
            this.kind = kind;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        boolean contains(long positionMs, long durationMs) {
            final long end = endMs == C.TIME_END_OF_SOURCE ? durationMs : endMs;
            return positionMs >= startMs && (end <= 0 || positionMs < end);
        }
    }

    // The names Jellyfin's Intro Skipper matches by default, plus "previously on"
    private static final Pattern INTRO = Pattern.compile("^(intro|introduction|op|opening|opening credits|title sequence)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECAP = Pattern.compile("^(recap|previously|previously on.*|last time.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDITS = Pattern.compile("^(credits?|end credits|closing credits|ed|ending|outro)$", Pattern.CASE_INSENSITIVE);

    private static final String API = "https://api.theintrodb.org/v4/media";
    private static final int TIMEOUT_MS = 8000;

    private SkipSegments() {
    }

    static List<Segment> fromChapters(List<Chapter> chapters) {
        final List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            final Chapter chapter = chapters.get(i);
            if (chapter.getTitle() == null || chapter.getTitle().value == null) {
                continue;
            }
            final String name = chapter.getTitle().value.trim().toLowerCase(Locale.ROOT);
            final Kind kind = INTRO.matcher(name).matches() ? Kind.INTRO
                    : RECAP.matcher(name).matches() ? Kind.RECAP
                    : CREDITS.matcher(name).matches() ? Kind.CREDITS : null;
            if (kind == null) {
                continue;
            }
            final long end = i + 1 < chapters.size() ? chapters.get(i + 1).getStartTimeMs() : C.TIME_END_OF_SOURCE;
            segments.add(new Segment(kind, chapter.getStartTimeMs(), end));
        }
        return segments;
    }

    // TheIntroDB by IMDb id (and season and episode for series); the duration picks the matching release cut.
    // Only the id, the episode numbers and the duration are sent.
    @WorkerThread
    static List<Segment> fromTheIntroDb(MediaIdentity identity, long durationMs) throws IOException, JSONException {
        final StringBuilder url = new StringBuilder(API).append("?imdb_id=").append(identity.imdbId);
        if (identity.isEpisode()) {
            url.append("&season=").append(identity.season).append("&episode=").append(identity.episode);
        }
        if (durationMs > 0) {
            url.append("&duration_ms=").append(durationMs);
        }
        final HttpURLConnection connection = (HttpURLConnection) new URL(url.toString()).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "EnhancedJustPlayer");
        final List<Segment> segments = new ArrayList<>();
        try {
            // 404 with "no_submissions" is the common answer for a title nobody has timed yet
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return segments;
            }
            final JSONObject json;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                final byte[] buffer = new byte[8 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                json = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
            addSegments(json.optJSONArray("intro"), Kind.INTRO, segments);
            addSegments(json.optJSONArray("recap"), Kind.RECAP, segments);
            addSegments(json.optJSONArray("credits"), Kind.CREDITS, segments);
        } finally {
            connection.disconnect();
        }
        return segments;
    }

    private static void addSegments(@Nullable JSONArray array, Kind kind, List<Segment> out) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            final JSONObject segment = array.optJSONObject(i);
            if (segment == null) {
                continue;
            }
            // A missing start means from the beginning, a missing end means to the end of the video
            final long start = segment.isNull("start_ms") ? 0 : segment.optLong("start_ms", 0);
            final long end = segment.isNull("end_ms") ? C.TIME_END_OF_SOURCE : segment.optLong("end_ms", C.TIME_END_OF_SOURCE);
            if (end == C.TIME_END_OF_SOURCE || end > start) {
                out.add(new Segment(kind, start, end));
            }
        }
    }
}
