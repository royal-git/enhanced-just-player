package com.brouken.player;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.media3.extractor.Ac3Util;

import java.nio.ByteBuffer;

// Finds Dolby Atmos inside passthrough audio. Matroska only says "E-AC3" or "TrueHD"; the Atmos signalling
// is in the frames themselves, which is where an AV receiver or soundbar reads it too:
// - E-AC3: the JOC extension in the frame's additional bit stream info (Media3's Ac3Util reports it as
//   audio/eac3-joc)
// - TrueHD: a fourth substream with the top bit of substream_info set, the rule FFmpeg uses for
//   AV_PROFILE_TRUEHD_ATMOS
// Decoded (PCM) audio arrives without frames to inspect, so nothing is reported for it.
class ObjectAudioDetectingSink extends ForwardingAudioSink {

    interface Listener {
        void onObjectAudioDetected(Format format);
    }

    // A few buffers are enough: E-AC3 frames all carry the bit stream info, and TrueHD repeats its major sync
    private static final int MAX_BUFFERS_TO_INSPECT = 32;
    private static final int TRUEHD_MAJOR_SYNC = 0xF8726FBA;

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private Format inputFormat;
    private int buffersInspected;
    private boolean decided;

    ObjectAudioDetectingSink(AudioSink sink, Listener listener) {
        super(sink);
        this.listener = listener;
    }

    // The legacy configure(Format, int, int[]) is final and forwards here
    @Override
    public void configure(AudioSinkConfig config) throws ConfigurationException {
        inputFormat = config.format;
        buffersInspected = 0;
        final String mime = config.format.sampleMimeType;
        decided = !MimeTypes.AUDIO_E_AC3.equals(mime) && !MimeTypes.AUDIO_E_AC3_JOC.equals(mime) && !MimeTypes.AUDIO_TRUEHD.equals(mime);
        super.configure(config);
    }

    @Override
    public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount) throws InitializationException, WriteException {
        if (!decided && inputFormat != null && buffer.hasRemaining()) {
            inspect(buffer, inputFormat);
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
    }

    private void inspect(ByteBuffer buffer, Format format) {
        buffersInspected++;
        boolean atmos = false;
        try {
            atmos = MimeTypes.AUDIO_TRUEHD.equals(format.sampleMimeType) ? isTrueHdAtmos(buffer) : isEac3Joc(buffer);
        } catch (RuntimeException ignored) {
            // A frame that does not parse says nothing either way; the next one may
        }
        if (atmos || buffersInspected >= MAX_BUFFERS_TO_INSPECT) {
            decided = true;
        }
        if (atmos) {
            mainHandler.post(() -> listener.onObjectAudioDetected(format));
        }
    }

    private static boolean isEac3Joc(ByteBuffer buffer) {
        final int start = buffer.position();
        if (buffer.remaining() < 32 || (buffer.get(start) & 0xFF) != 0x0B || (buffer.get(start + 1) & 0xFF) != 0x77) {
            return false;
        }
        final byte[] header = new byte[Math.min(buffer.remaining(), 256)];
        buffer.duplicate().get(header);
        return MimeTypes.AUDIO_E_AC3_JOC.equals(Ac3Util.parseAc3SyncframeInfo(new ParsableBitArray(header)).mimeType);
    }

    // Major sync: sync word and stream type (4 bytes), then 12 bytes of rates and channel layouts and 48 bits
    // that are skipped; num_substreams is the high nibble 16 bytes in and substream_info the byte after it
    private static boolean isTrueHdAtmos(ByteBuffer buffer) {
        final int end = buffer.limit() - 18;
        for (int i = buffer.position(); i < end; i++) {
            if (buffer.getInt(i) != TRUEHD_MAJOR_SYNC) {
                continue;
            }
            final int numSubstreams = (buffer.get(i + 16) >> 4) & 0x0F;
            final int substreamInfo = buffer.get(i + 17) & 0xFF;
            return numSubstreams == 4 && (substreamInfo & 0x80) != 0;
        }
        return false;
    }
}
