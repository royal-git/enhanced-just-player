package com.brouken.player;

import android.net.Uri;

import androidx.annotation.Nullable;

import java.util.List;

// What is playing, as far as the stream link says. Stremio passes no title or id to an external player,
// but Comet's playback links carry them:
//   {host}/{config}/playback/{infoHash}/{service}/{fileIndex}/{season|n}/{episode|n}
//       ?torrent_name=...&name=<title>&media_id=<IMDb id>&media_type=movie|series
// The {config} segment holds the debrid key: it is never read, logged or sent anywhere.
final class MediaIdentity {

    @Nullable final String imdbId;
    @Nullable final String type;
    @Nullable final String name;
    final int season;
    final int episode;

    private MediaIdentity(@Nullable String imdbId, @Nullable String type, @Nullable String name, int season, int episode) {
        this.imdbId = imdbId;
        this.type = type;
        this.name = name;
        this.season = season;
        this.episode = episode;
    }

    @Nullable
    static MediaIdentity fromUri(@Nullable Uri uri) {
        if (uri == null || uri.isOpaque()) {
            return null;
        }
        String imdbId = uri.getQueryParameter("media_id");
        if (imdbId != null && !imdbId.matches("tt\\d{5,10}")) {
            imdbId = null;
        }
        String type = uri.getQueryParameter("media_type");
        if (!"movie".equals(type) && !"series".equals(type)) {
            type = null;
        }
        final String name = uri.getQueryParameter("name");

        int season = -1;
        int episode = -1;
        final List<String> segments = uri.getPathSegments();
        final int playback = segments.indexOf("playback");
        if (playback >= 0 && segments.size() > playback + 5) {
            season = parseNumber(segments.get(playback + 4));
            episode = parseNumber(segments.get(playback + 5));
        }

        if (imdbId == null && (name == null || name.trim().isEmpty())) {
            return null;
        }
        return new MediaIdentity(imdbId, type, name, season, episode);
    }

    boolean isEpisode() {
        return "series".equals(type) && season >= 0 && episode >= 0;
    }

    private static int parseNumber(String value) {
        return value != null && value.matches("\\d{1,4}") ? Integer.parseInt(value) : -1;
    }
}
