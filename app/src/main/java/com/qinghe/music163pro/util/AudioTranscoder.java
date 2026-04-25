package com.qinghe.music163pro.util;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * Transcodes audio files (AAC, M4A, Opus, WebM, m4s fragments, etc.) to proper M4A/AAC format.
 * Uses Android's built-in MediaExtractor + MediaCodec + MediaMuxer — zero external dependencies.
 *
 * The output is a standard MPEG-4 container with AAC audio, saved with the caller's desired
 * extension. Android's ExoPlayer (used by this app) detects format by header, not extension.
 *
 * Usage:
 *   AudioTranscoder.transcodeToMp3(inputPath, outputPath, callback);
 *   // or synchronous:
 *   boolean ok = AudioTranscoder.transcodeToMp3Sync(inputPath, outputPath);
 */
public class AudioTranscoder {

    private static final String TAG = "AudioTranscoder";
    private static final int AAC_BITRATE = 128000;  // 128 kbps
    private static final int TIMEOUT_US = 10000;

    public interface TranscodeCallback {
        void onProgress(int percent);
        void onComplete();
        void onError(String message);
    }

    /**
     * Transcode an audio file to M4A/AAC format asynchronously.
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
     * Transcode an audio file to proper M4A/AAC format synchronously (blocks caller thread).
     * Uses two-phase approach: decode to PCM file, then encode PCM to M4A.
     * @return true on success, false on failure
     */
    public static boolean transcodeToMp3Sync(String inputPath, String outputPath) {
        File inputFile = new File(inputPath);
        File outputFile = new File(outputPath);
        File tempPcm = new File(outputPath + ".pcm");
        File tempM4a = new File(outputPath + ".m4aenc");

        try {
            // Phase 1: Extract audio info and decode to raw PCM
            int[] audioInfo = new int[2]; // [0]=sampleRate, [1]=channelCount
            boolean decodeOk = decodeToPcm(inputPath, tempPcm.getAbsolutePath(), audioInfo);
            if (!decodeOk) {
                Log.w(TAG, "Phase 1 decode failed for " + inputPath);
                return false;
            }

            int sampleRate = audioInfo[0];
            int channelCount = audioInfo[1];
            Log.i(TAG, "Decoded to PCM: " + sampleRate + "Hz, " + channelCount + "ch, "
                    + (tempPcm.length() / 1024) + "KB PCM");

            // Phase 2: Encode PCM to M4A/AAC
            boolean encodeOk = encodePcmToM4a(
                    tempPcm.getAbsolutePath(), sampleRate, channelCount,
                    tempM4a.getAbsolutePath());
            if (!encodeOk) {
                Log.w(TAG, "Phase 2 encode failed");
                tempPcm.delete();
                return false;
            }

            // Cleanup temp files
            tempPcm.delete();
            if (inputFile.exists()) {
                inputFile.delete();
            }
            tempM4a.renameTo(outputFile);

            Log.i(TAG, "Transcode complete: " + outputPath
                    + " (" + sampleRate + "Hz, " + channelCount + "ch, AAC " + (AAC_BITRATE / 1000) + "kbps)");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Transcode error for " + inputPath, e);
            tempPcm.delete();
            tempM4a.delete();
            return false;
        }
    }

    /**
     * Phase 1: Decode any audio file to raw PCM using MediaExtractor + MediaCodec.
     * @param inputPath input audio file path
     * @param pcmPath output raw PCM file path (16-bit signed, native byte order)
     * @param outAudioInfo int[2] to receive [sampleRate, channelCount]
     * @return true on success
     */
    private static boolean decodeToPcm(String inputPath, String pcmPath, int[] outAudioInfo) {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        FileOutputStream fos = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);

            int audioTrackIndex = findBestAudioTrack(extractor);
            if (audioTrackIndex < 0) {
                Log.w(TAG, "No audio track found in " + inputPath);
                return false;
            }

            MediaFormat format = extractor.getTrackFormat(audioTrackIndex);
            extractor.selectTrack(audioTrackIndex);

            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) : 2;
            outAudioInfo[0] = sampleRate;
            outAudioInfo[1] = channelCount;

            // Create decoder
            String mime = format.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            fos = new FileOutputStream(pcmPath);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long totalDecoded = 0;
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;

            while (!outputDone) {
                // Feed input to decoder
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        int sampleSize = extractor.readSampleData(inBuf, 0);
                        if (sampleSize <= 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                // Read decoded PCM output
                if (!outputDone) {
                    int outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Format change, ignore
                    } else if (outIdx >= 0) {
                        if (info.size > 0) {
                            ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                            byte[] pcmBytes = new byte[info.size];
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            outBuf.get(pcmBytes);
                            fos.write(pcmBytes);
                            totalDecoded += info.size;
                        }
                        decoder.releaseOutputBuffer(outIdx, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true;
                        }
                    }
                }
            }

            fos.flush();
            fos.close();
            fos = null;
            decoder.stop();
            decoder.release();
            decoder = null;
            extractor.release();
            extractor = null;

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Decode error", e);
            return false;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) {}
            try { if (extractor != null) extractor.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * Phase 2: Encode raw PCM file to M4A/AAC using MediaCodec encoder + MediaMuxer.
     * @param pcmPath input raw PCM file (16-bit signed, native byte order)
     * @param sampleRate PCM sample rate
     * @param channelCount PCM channel count (1=mono, 2=stereo)
     * @param outputPath output M4A file path
     * @return true on success
     */
    private static boolean encodePcmToM4a(String pcmPath, int sampleRate, int channelCount,
                                          String outputPath) {
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        FileInputStream fis = null;

        try {
            // Create AAC encoder
            MediaFormat encoderFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE);
            encoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            // Wait for encoder output format (needed before creating muxer track)
            MediaFormat encoderOutputFormat = null;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long waitStart = System.currentTimeMillis();
            while (encoderOutputFormat == null) {
                int idx = encoder.dequeueOutputBuffer(info, 5000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    encoderOutputFormat = encoder.getOutputFormat();
                } else if (idx >= 0) {
                    // Release any premature output buffers
                    encoder.releaseOutputBuffer(idx, false);
                }
                if (System.currentTimeMillis() - waitStart > 5000) {
                    Log.e(TAG, "Timeout waiting for encoder output format");
                    encoder.stop();
                    encoder.release();
                    return false;
                }
            }

            // Create muxer and add track
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerTrack = muxer.addTrack(encoderOutputFormat);
            muxer.start();

            // Read PCM and feed to encoder
            fis = new FileInputStream(pcmPath);
            int bytesPerFrame = channelCount * 2; // 16-bit = 2 bytes per sample
            int frameSize = 4096 * bytesPerFrame; // ~4K samples per frame
            byte[] readBuffer = new byte[frameSize];
            long bytesPerSecond = (long) sampleRate * bytesPerFrame;
            long totalRead = 0;

            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                // Feed PCM data to encoder
                if (!inputDone) {
                    int inIdx = encoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = encoder.getInputBuffer(inIdx);
                        int bytesRead = fis.read(readBuffer);
                        if (bytesRead <= 0) {
                            encoder.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            inBuf.clear();
                            inBuf.put(readBuffer, 0, bytesRead);
                            // Calculate presentation timestamp based on PCM position
                            long ptsUs = (totalRead * 1000000L) / bytesPerSecond;
                            encoder.queueInputBuffer(inIdx, 0, bytesRead, ptsUs, 0);
                            totalRead += bytesRead;
                        }
                    }
                }

                // Drain encoder output to muxer
                if (!outputDone) {
                    int outIdx = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Already handled
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // No output available yet
                    } else if (outIdx >= 0) {
                        ByteBuffer outBuf = encoder.getOutputBuffer(outIdx);
                        if (info.size > 0) {
                            // Skip codec-config (CSD) buffers — muxer already has them
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                outBuf.position(info.offset);
                                outBuf.limit(info.offset + info.size);
                                muxer.writeSampleData(muxerTrack, outBuf, info);
                            }
                        }
                        encoder.releaseOutputBuffer(outIdx, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true;
                        }
                    }
                }
            }

            // Cleanup in order
            muxer.stop();
            muxer.release();
            muxer = null;
            encoder.stop();
            encoder.release();
            encoder = null;
            fis.close();
            fis = null;

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Encode error", e);
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
        }
    }

    /**
     * Quick check if a file is likely already a proper MP3 (MP3 frame sync or ID3 tag).
     * Returns true if the file starts with FF FB/F3/F2 or ID3 header.
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
     * Detect audio format by file extension and magic bytes.
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
        if (lower.endsWith(".m4s")) return "m4s/fragment";
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
            // WebM (EBML)
            if (header[0] == 0x1A && header[1] == 0x45 && header[2] == 0xDF && header[3] == 0xA3) return "webm";
            // moof (fragmented MP4 — common for Bilibili m4s downloads)
            if (header[0] == (byte) 0x30 && header[4] == 'm' && header[5] == 'o'
                    && header[6] == 'o' && header[7] == 'f') return "m4s/fragment";
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }

    /**
     * Check if a file needs transcoding (i.e., is NOT a standard MP3/M4A/AAC file
     * that ExoPlayer can already play directly).
     */
    public static boolean needsTranscoding(String filePath) {
        String format = guessAudioFormat(filePath);
        // These formats are already playable by ExoPlayer directly
        if (format.equals("mp3") || format.equals("m4a/aac")) {
            return false;
        }
        return true;
    }

    /**
     * Find the best audio track in a MediaExtractor.
     * Prefers higher bitrate tracks.
     */
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
}
