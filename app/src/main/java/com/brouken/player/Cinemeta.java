package com.brouken.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

// Title details from Cinemeta, Stremio's own metadata service: no API key, and Stremio already asks it about
// everything it shows, so no new party learns what is watched. Only the IMDb id is sent.
final class Cinemeta {

    private static final String BASE = "https://v3-cinemeta.strem.io/meta/";
    private static final long CACHE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(3);
    private static final int TIMEOUT_MS = 8000;

    static final class Meta {
        String name;
        @Nullable String releaseInfo;
        @Nullable String runtime;
        @Nullable String imdbRating;
        @Nullable String description;
        @Nullable String poster;
        final List<String> genres = new ArrayList<>();
        final List<String> cast = new ArrayList<>();
        final List<String> directors = new ArrayList<>();
        @Nullable Episode episode;
    }

    static final class Episode {
        int season;
        int number;
        @Nullable String name;
        @Nullable String overview;
        @Nullable String released;
    }

    private Cinemeta() {
    }

    @WorkerThread
    static Meta fetch(Context context, MediaIdentity identity) throws IOException, JSONException {
        final String type = identity.type != null ? identity.type : "movie";
        final File cacheFile = new File(new File(context.getCacheDir(), "cinemeta"), type + "_" + identity.imdbId + ".json");
        String json = null;
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_MAX_AGE_MS) {
            json = new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8);
        }
        if (json == null) {
            json = new String(download(BASE + type + "/" + identity.imdbId + ".json"), StandardCharsets.UTF_8);
            final File dir = cacheFile.getParentFile();
            if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                try (FileOutputStream out = new FileOutputStream(cacheFile)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        return parse(new JSONObject(json).getJSONObject("meta"), identity);
    }

    @WorkerThread
    @Nullable
    static Bitmap fetchImage(String url, int maxWidth) throws IOException {
        final byte[] bytes = download(url);
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= maxWidth) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static Meta parse(JSONObject json, MediaIdentity identity) {
        final Meta meta = new Meta();
        meta.name = json.optString("name", identity.name != null ? identity.name : "");
        meta.releaseInfo = optString(json, "releaseInfo");
        meta.runtime = optString(json, "runtime");
        meta.imdbRating = optString(json, "imdbRating");
        meta.description = optString(json, "description");
        meta.poster = optString(json, "poster");
        addStrings(json.optJSONArray("genres"), meta.genres);
        addStrings(json.optJSONArray("cast"), meta.cast);
        addStrings(json.optJSONArray("director"), meta.directors);

        final JSONArray videos = json.optJSONArray("videos");
        if (identity.isEpisode() && videos != null) {
            for (int i = 0; i < videos.length(); i++) {
                final JSONObject video = videos.optJSONObject(i);
                if (video != null && video.optInt("season", -1) == identity.season && video.optInt("episode", -1) == identity.episode) {
                    final Episode episode = new Episode();
                    episode.season = identity.season;
                    episode.number = identity.episode;
                    episode.name = optString(video, "name");
                    if (episode.name == null) {
                        episode.name = optString(video, "title");
                    }
                    episode.overview = optString(video, "overview");
                    if (episode.overview == null) {
                        episode.overview = optString(video, "description");
                    }
                    episode.released = optString(video, "released");
                    meta.episode = episode;
                    break;
                }
            }
        }
        return meta;
    }

    @Nullable
    private static String optString(JSONObject json, String key) {
        final String value = json.optString(key, "").trim();
        return value.isEmpty() || "null".equals(value) ? null : value;
    }

    private static void addStrings(@Nullable JSONArray array, List<String> out) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            final String value = array.optString(i, "").trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
    }

    private static byte[] download(String url) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "EnhancedJustPlayer");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                final byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}
