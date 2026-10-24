package com.brouken.player;

import android.net.Uri;

import androidx.annotation.WorkerThread;
import androidx.media3.common.Format;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// Reads the video frame rate from a Matroska file's header with one small range request: the video track's
// DefaultDuration (nanoseconds per frame). Media3 reads that field but does not report it as Format.frameRate,
// and the alternative, sampling a few hundred frames, downloads tens of megabytes of a 4K remux.
final class MatroskaHeader {

    private static final int HEADER_BYTES = 1024 * 1024;
    private static final int TIMEOUT_MS = 6000;

    private static final int ID_EBML = 0x1A45DFA3;
    private static final int ID_SEGMENT = 0x18538067;
    private static final int ID_TRACKS = 0x1654AE6B;
    private static final int ID_TRACK_ENTRY = 0xAE;
    private static final int ID_TRACK_TYPE = 0x83;
    private static final int ID_DEFAULT_DURATION = 0x23E383;
    private static final int ID_CLUSTER = 0x1F43B675;
    private static final int TRACK_TYPE_VIDEO = 1;

    private MatroskaHeader() {
    }

    // Frames per second, or Format.NO_VALUE when the header does not say (or is not Matroska)
    @WorkerThread
    static float readFrameRate(Uri uri) throws IOException {
        final byte[] header = download(uri);
        try {
            return parseFrameRate(header);
        } catch (RuntimeException e) {
            return Format.NO_VALUE;
        }
    }

    private static byte[] download(Uri uri) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Range", "bytes=0-" + (HEADER_BYTES - 1));
        try {
            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + code);
            }
            // A server that ignores Range sends the whole file: stop after the header-sized prefix either way
            final byte[] buffer = new byte[HEADER_BYTES];
            int filled = 0;
            try (InputStream in = connection.getInputStream()) {
                int read;
                while (filled < buffer.length && (read = in.read(buffer, filled, buffer.length - filled)) != -1) {
                    filled += read;
                }
            }
            final byte[] out = new byte[filled];
            System.arraycopy(buffer, 0, out, 0, filled);
            return out;
        } finally {
            connection.disconnect();
        }
    }

    static float parseFrameRate(byte[] data) {
        final int[] pos = {0};
        if (readId(data, pos) != ID_EBML) {
            return Format.NO_VALUE;
        }
        pos[0] = skip(data, pos);
        if (readId(data, pos) != ID_SEGMENT) {
            return Format.NO_VALUE;
        }
        final long segmentSize = readSize(data, pos);
        final int segmentEnd = segmentSize < 0 ? data.length : (int) Math.min(data.length, pos[0] + segmentSize);
        while (pos[0] < segmentEnd) {
            final int id = readId(data, pos);
            final long size = readSize(data, pos);
            if (id == ID_CLUSTER || size < 0) {
                break;
            }
            final int end = (int) Math.min(segmentEnd, pos[0] + size);
            if (id == ID_TRACKS) {
                return parseTracks(data, pos[0], end);
            }
            pos[0] = end;
        }
        return Format.NO_VALUE;
    }

    private static float parseTracks(byte[] data, int start, int end) {
        final int[] pos = {start};
        while (pos[0] < end) {
            final int id = readId(data, pos);
            final long size = readSize(data, pos);
            if (size < 0) {
                break;
            }
            final int entryEnd = (int) Math.min(end, pos[0] + size);
            if (id == ID_TRACK_ENTRY) {
                long type = 0;
                long defaultDuration = 0;
                final int[] child = {pos[0]};
                while (child[0] < entryEnd) {
                    final int childId = readId(data, child);
                    final long childSize = readSize(data, child);
                    if (childSize < 0) {
                        break;
                    }
                    if (childId == ID_TRACK_TYPE) {
                        type = readUnsigned(data, child[0], (int) childSize);
                    } else if (childId == ID_DEFAULT_DURATION) {
                        defaultDuration = readUnsigned(data, child[0], (int) childSize);
                    }
                    child[0] += childSize;
                }
                if (type == TRACK_TYPE_VIDEO && defaultDuration > 0) {
                    return 1_000_000_000f / defaultDuration;
                }
            }
            pos[0] = entryEnd;
        }
        return Format.NO_VALUE;
    }

    // Element IDs keep their length marker; 1 to 4 bytes
    private static int readId(byte[] data, int[] pos) {
        final int first = data[pos[0]] & 0xFF;
        final int length = Integer.numberOfLeadingZeros(first) - 24 + 1;
        if (length < 1 || length > 4) {
            throw new IllegalStateException("bad id");
        }
        int id = 0;
        for (int i = 0; i < length; i++) {
            id = (id << 8) | (data[pos[0] + i] & 0xFF);
        }
        pos[0] += length;
        return id;
    }

    // Sizes drop their length marker; all ones means unknown (returned as -1)
    private static long readSize(byte[] data, int[] pos) {
        final int first = data[pos[0]] & 0xFF;
        final int length = Integer.numberOfLeadingZeros(first) - 24 + 1;
        if (length < 1 || length > 8) {
            throw new IllegalStateException("bad size");
        }
        long value = first & (0xFF >> length);
        boolean allOnes = value == (0xFF >> length);
        for (int i = 1; i < length; i++) {
            final int b = data[pos[0] + i] & 0xFF;
            value = (value << 8) | b;
            allOnes &= b == 0xFF;
        }
        pos[0] += length;
        return allOnes ? -1 : value;
    }

    private static int skip(byte[] data, int[] pos) {
        final long size = readSize(data, pos);
        if (size < 0) {
            throw new IllegalStateException("unknown size");
        }
        return (int) (pos[0] + size);
    }

    private static long readUnsigned(byte[] data, int start, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (data[start + i] & 0xFF);
        }
        return value;
    }
}
