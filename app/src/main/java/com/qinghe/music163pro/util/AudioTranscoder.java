package com.qinghe.music163pro.util;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import com.hzy.liblame.LameUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Transcodes audio files (AAC, M4A, Opus, WebM, etc.) to proper MP3 format.
 * Uses Android's built-in MediaExtractor + MediaCodec for decoding,
 * and LAME for MP3 encoding.
 *
 * Usage:
 *   AudioTranscoder.transcodeToMp3(inputPath, outputPath, callback);
 *   // or synchronous:
 *   boolean ok = AudioTranscoder.transcodeToMp3Sync(inputPath, outputPath);
 */
public class AudioTranscoder {

    private static final String TAG = "AudioTranscoder";
    private static final int MP3_BITRATE = 128;   // kbps
    private static final int MP3_QUALITY = 2;     // 0=best..9=worst
    private static final int TIMEOUT_US = 10000;

    public interface TranscodeCallback {
        void onProgress(int percent);
        void onComplete();
        void onError(String message);
    }

    /**
     * Transcode an audio file to MP3 format asynchronously.
     */
    public static void transcodeToMp3(String inputPath, String outputPath, TranscodeCallback callback) {
        new Thread(() -> {
            boolean ok = transcodeToMp3Sync(inputPath, outputPath);
            if (ok) {
                callback.onComplete();
            } else {
                callback.onError("转码失败");
            }
        }).start();
    }

    /**
     * Transcode an audio file to MP3 format synchronously (blocks caller thread).
     * @return true on success, false on failure
     */
    public static boolean transcodeToMp3Sync(String inputPath, String outputPath) {
        File inputFile = new File(inputPath);
        File outputFile = new File(outputPath);
        File tempFile = new File(outputPath + ".mp3enc");

        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        FileOutputStream fos = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);

            // Find the best audio track
            int audioTrackIndex = findBestAudioTrack(extractor);
            if (audioTrackIndex < 0) {
                Log.w(TAG, "No audio track found in " + inputPath);
                return false;
            }

            MediaFormat format = extractor.getTrackFormat(audioTrackIndex);
            extractor.selectTrack(audioTrackIndex);

            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;

            // Create decoder
            String mime = format.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            // Initialize LAME encoder
            int lameMode = (channelCount == 1) ? 3 : 0; // 3=mono, 0=stereo
            LameUtil.init(sampleRate, sampleRate, channelCount, MP3_BITRATE, MP3_QUALITY, lameMode);
            int mp3BufSize = LameUtil.getMP3BufferSize();
            byte[] mp3Buffer = new byte[mp3BufSize];

            fos = new FileOutputStream(tempFile);

            // Decode loop
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long totalEncodedTime = 0;

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        int sampleSize = extractor.readSampleData(inBuf, 0);
                        if (sampleSize <= 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                if (!outputDone) {
                    int outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Ignore format change
                    } else if (outIdx >= 0) {
                        if (info.size > 0) {
                            ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                            byte[] pcmBytes = new byte[info.size];
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            outBuf.get(pcmBytes);

                            // Encode PCM to MP3
                            encodePcmToMp3(pcmBytes, channelCount, mp3Buffer, fos);

                            totalEncodedTime += info.presentationTimeUs;
                        }
                        decoder.releaseOutputBuffer(outIdx, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true;
                        }
                    }
                }
            }

            // Flush LAME encoder
            int flushed = LameUtil.flush(mp3Buffer);
            if (flushed > 0) {
                fos.write(mp3Buffer, 0, flushed);
            }
            fos.flush();
            fos.close();
            fos = null;

            LameUtil.close();
            decoder.stop();
            decoder.release();
            decoder = null;
            extractor.release();
            extractor = null;

            // Replace original with transcoded MP3
            if (inputFile.exists()) inputFile.delete();
            tempFile.renameTo(outputFile);

            Log.i(TAG, "Transcode complete: " + outputPath
                    + " (" + (totalEncodedTime / 1000000) + "s, "
                    + sampleRate + "Hz, " + channelCount + "ch, " + MP3_BITRATE + "kbps)");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Transcode error for " + inputPath, e);
            // Cleanup
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            try { LameUtil.close(); } catch (Exception ignored) {}
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) {}
            try { if (extractor != null) extractor.release(); } catch (Exception ignored) {}
            tempFile.delete();
            return false;
        }
    }

    /**
     * Quick check if a file is likely already a proper MP3 (MP3 frame sync).
     * Returns true if the file starts with FF FB or FF F3 or ID3 tag.
     */
    public static boolean isLikelyMp3(String filePath) {
        try {
            FileInputStream fis = new FileInputStream(filePath);
            byte[] header = new byte[3];
            int read = fis.read(header);
            fis.close();
            if (read < 3) return false;
            // ID3 tag
            if (header[0] == 0x49 && header[1] == 0x44 && header[2] == 0x33) return true;
            // MP3 sync (FF FB or FF F3 or FF F2)
            if (header[0] == (byte) 0xFF && (header[1] & 0xE0) == 0xE0) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get file extension from MIME type.
     */
    public static String guessAudioFormat(String filePath) {
        if (filePath == null) return "unknown";
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".mp3")) return "mp3";
        if (lower.endsWith(".m4a") || lower.endsWith(".aac")) return "m4a/aac";
        if (lower.endsWith(".webm")) return "webm";
        if (lower.endsWith(".flac")) return "flac";
        if (lower.endsWith(".ogg")) return "ogg";
        if (lower.endsWith(".wav")) return "wav";
        if (lower.endsWith(".wma")) return "wma";
        // Check file header for format detection
        try {
            FileInputStream fis = new FileInputStream(filePath);
            byte[] header = new byte[12];
            int read = fis.read(header);
            fis.close();
            if (read < 4) return "unknown";
            // ftyp (M4A/MP4/MOV)
            if (header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') return "m4a/aac";
            // OggS
            if (header[0] == 'O' && header[1] == 'g' && header[2] == 'g' && header[3] == 'S') return "ogg";
            // RIFF (WAV)
            if (header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F') return "wav";
            // fLaC
            if (header[0] == 'f' && header[1] == 'L' && header[2] == 'a' && header[3] == 'C') return "flac";
            // ID3 or MP3 sync
            if (header[0] == 0x49 && header[1] == 0x44 && header[2] == 0x33) return "mp3";
            if (header[0] == (byte) 0xFF && (header[1] & 0xE0) == 0xE0) return "mp3";
            // WebM
            if (header[0] == 0x1A && header[1] == 0x45 && header[2] == 0xDF && header[3] == 0xA3) return "webm";
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }

    private static int findBestAudioTrack(MediaExtractor extractor) {
        int best = -1;
        int bestBitrate = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("audio/")) continue;
            int bitrate = -1;
            if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) {
                bitrate = fmt.getInteger(MediaFormat.KEY_BIT_RATE);
            }
            if (best < 0 || bitrate > bestBitrate) {
                best = i;
                bestBitrate = bitrate;
            }
        }
        return best;
    }

    private static void encodePcmToMp3(byte[] pcmBytes, int channelCount, byte[] mp3Buffer,
                                         FileOutputStream fos) throws Exception {
        short[] pcmShort = bytesToShorts(pcmBytes);
        int numSamples = pcmShort.length / channelCount;

        if (channelCount == 1) {
            int encoded = LameUtil.encode(pcmShort, pcmShort, numSamples, mp3Buffer);
            if (encoded > 0) fos.write(mp3Buffer, 0, encoded);
        } else {
            // De-interleave stereo PCM
            short[] left = new short[numSamples];
            short[] right = new short[numSamples];
            for (int i = 0; i < numSamples; i++) {
                left[i] = pcmShort[i * 2];
                right[i] = pcmShort[i * 2 + 1];
            }
            int encoded = LameUtil.encode(left, right, numSamples, mp3Buffer);
            if (encoded > 0) fos.write(mp3Buffer, 0, encoded);
        }
    }

    private static short[] bytesToShorts(byte[] bytes) {
        short[] shorts = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
        return shorts;
    }
}
