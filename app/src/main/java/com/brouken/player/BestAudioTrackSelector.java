package com.brouken.player;

import android.content.Context;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// Picks the best audio track instead of the one flagged default: object audio (Atmos, DTS:X) first,
// then lossless, then channel count. Commentary and audio description tracks are never auto-picked.
// Manual choices are track selection overrides, which DefaultTrackSelector applies after this.
public class BestAudioTrackSelector extends DefaultTrackSelector {

    private static final Pattern EXCLUDED_LABEL = Pattern.compile("comment|descri|narrat|visually impaired", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUDIO_DESCRIPTION_LABEL = Pattern.compile("\\bAD\\b");

    public BestAudioTrackSelector(Context context) {
        super(context);
    }

    @Nullable
    @Override
    protected Pair<ExoTrackSelection.Definition, Integer> selectAudioTrack(MappedTrackInfo mappedTrackInfo, int[][][] rendererFormatSupports, int[] rendererMixedMimeTypeAdaptationSupports, Parameters params) throws ExoPlaybackException {
        final Pair<ExoTrackSelection.Definition, Integer> selection = super.selectAudioTrack(mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params);

        final List<Format> formats = new ArrayList<>();
        final List<int[]> positions = new ArrayList<>();
        for (int renderer = 0; renderer < mappedTrackInfo.getRendererCount(); renderer++) {
            if (mappedTrackInfo.getRendererType(renderer) != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            final TrackGroupArray groups = mappedTrackInfo.getTrackGroups(renderer);
            for (int group = 0; group < groups.length; group++) {
                for (int track = 0; track < groups.get(group).length; track++) {
                    if (isSupported(rendererFormatSupports[renderer][group][track], false)) {
                        formats.add(groups.get(group).getFormat(track));
                        positions.add(new int[] {renderer, group, track});
                    }
                }
            }
        }

        final int best = bestIndex(formats, params.preferredAudioLanguages);
        if (best == C.INDEX_UNSET) {
            return selection;
        }
        final int[] position = positions.get(best);
        final TrackGroup group = mappedTrackInfo.getTrackGroups(position[0]).get(position[1]);
        if (selection != null && selection.first.group == group && selection.first.tracks.length == 1 && selection.first.tracks[0] == position[2]) {
            return selection;
        }
        return Pair.create(new ExoTrackSelection.Definition(group, position[2]), position[0]);
    }

    // The best playable audio format among the current tracks, or null when there is no audio.
    @Nullable
    public static Format findBest(Tracks tracks, List<String> preferredLanguages) {
        final List<Format> formats = new ArrayList<>();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            for (int track = 0; track < group.length; track++) {
                if (group.isTrackSupported(track)) {
                    formats.add(group.getTrackFormat(track));
                }
            }
        }
        final int best = bestIndex(formats, preferredLanguages);
        return best == C.INDEX_UNSET ? null : formats.get(best);
    }

    // Tracks in a preferred (or untagged) language win; if none are, every language is considered.
    private static int bestIndex(List<Format> formats, List<String> preferredLanguages) {
        for (boolean filterLanguage : new boolean[] {true, false}) {
            int best = C.INDEX_UNSET;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < formats.size(); i++) {
                final Format format = formats.get(i);
                if (isExcluded(format) || (filterLanguage && !isLanguageAllowed(format, preferredLanguages))) {
                    continue;
                }
                final int score = score(format);
                if (score > bestScore) {
                    best = i;
                    bestScore = score;
                }
            }
            if (best != C.INDEX_UNSET) {
                return best;
            }
        }
        return C.INDEX_UNSET;
    }

    public static boolean isExcluded(Format format) {
        return excludedRole(format) != null;
    }

    // Why a track is never auto-picked, in words for the menus, or null when it can be
    @Nullable
    public static String excludedRole(Format format) {
        final String label = format.label == null ? "" : format.label;
        if ((format.roleFlags & C.ROLE_FLAG_COMMENTARY) != 0 || label.toLowerCase(Locale.ROOT).contains("comment")) {
            return "Commentary";
        }
        if ((format.roleFlags & C.ROLE_FLAG_DESCRIBES_VIDEO) != 0 || EXCLUDED_LABEL.matcher(label).find() || AUDIO_DESCRIPTION_LABEL.matcher(label).find()) {
            return "Audio description";
        }
        return null;
    }

    private static boolean isLanguageAllowed(Format format, List<String> preferredLanguages) {
        if (preferredLanguages.isEmpty() || normalizeUndeterminedLanguageToNull(format.language) == null) {
            return true;
        }
        for (String language : preferredLanguages) {
            if (getFormatLanguageScore(format, language, false) > 0) {
                return true;
            }
        }
        return false;
    }

    public static boolean isObjectAudio(Format format) {
        final String label = format.label == null ? "" : format.label.toLowerCase(Locale.ROOT);
        return MimeTypes.AUDIO_E_AC3_JOC.equals(format.sampleMimeType) || label.contains("atmos") || label.contains("dts:x") || label.contains("dts-x");
    }

    private static int codecRank(Format format) {
        final String mime = format.sampleMimeType == null ? "" : format.sampleMimeType;
        final String label = format.label == null ? "" : format.label.toLowerCase(Locale.ROOT);
        switch (mime) {
            case MimeTypes.AUDIO_TRUEHD:
                return 5;
            case MimeTypes.AUDIO_DTS_HD:
            case MimeTypes.AUDIO_FLAC:
            case MimeTypes.AUDIO_RAW:
                return 4;
            case MimeTypes.AUDIO_E_AC3:
            case MimeTypes.AUDIO_E_AC3_JOC:
                return 3;
            case MimeTypes.AUDIO_DTS:
                // Matroska labels every DTS stream A_DTS; the track name tells DTS-HD MA apart.
                return label.contains("dts-hd") || label.contains("dts-ma") ? 4 : 2;
            case MimeTypes.AUDIO_AC3:
                return 2;
            default:
                return 1;
        }
    }

    // Object audio, then codec, then channels; the default flag only breaks ties.
    private static int score(Format format) {
        final int channels = Math.min(Math.max(format.channelCount, 0), 99);
        final int isDefault = (format.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0 ? 1 : 0;
        return (isObjectAudio(format) ? 100_000 : 0) + codecRank(format) * 1_000 + channels * 10 + isDefault;
    }

    public static String describeAudio(Format format) {
        final StringBuilder description = new StringBuilder(describeCodec(format));
        final String language = normalizeUndeterminedLanguageToNull(format.language);
        if (language != null) {
            description.append(" · ").append(languageName(format));
        }
        final String role = excludedRole(format);
        if (role != null) {
            description.append(" · ").append(role);
        }
        return description.toString();
    }

    public static String languageName(Format format) {
        final String language = normalizeUndeterminedLanguageToNull(format.language);
        return language == null ? "Unknown language" : Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH);
    }

    // Codec, object audio and channel layout, e.g. "TrueHD Atmos 7.1"
    public static String describeCodec(Format format) {
        final String mime = format.sampleMimeType == null ? "" : format.sampleMimeType;
        final String label = format.label == null ? "" : format.label.toLowerCase(Locale.ROOT);
        String codec;
        switch (mime) {
            case MimeTypes.AUDIO_TRUEHD: codec = "TrueHD"; break;
            case MimeTypes.AUDIO_DTS_HD: codec = "DTS-HD"; break;
            case MimeTypes.AUDIO_DTS: codec = codecRank(format) == 4 ? "DTS-HD MA" : "DTS"; break;
            case MimeTypes.AUDIO_E_AC3:
            case MimeTypes.AUDIO_E_AC3_JOC: codec = "DD+"; break;
            case MimeTypes.AUDIO_AC3: codec = "DD"; break;
            case MimeTypes.AUDIO_AAC: codec = "AAC"; break;
            case MimeTypes.AUDIO_OPUS: codec = "Opus"; break;
            case MimeTypes.AUDIO_FLAC: codec = "FLAC"; break;
            case MimeTypes.AUDIO_RAW: codec = "PCM"; break;
            default: codec = mime.replace("audio/", "").toUpperCase(Locale.ROOT);
        }
        if (isObjectAudio(format)) {
            codec += label.contains("dts:x") || label.contains("dts-x") ? " (DTS:X)" : " Atmos";
        }
        final StringBuilder description = new StringBuilder(codec);
        switch (format.channelCount) {
            case Format.NO_VALUE: break;
            case 1: description.append(" 1.0"); break;
            case 2: description.append(" 2.0"); break;
            case 6: description.append(" 5.1"); break;
            case 8: description.append(" 7.1"); break;
            default: description.append(" ").append(format.channelCount).append("ch");
        }
        return description.toString();
    }
}
