package com.qinghe.music163pro.manager;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.qinghe.music163pro.api.BilibiliApiHelper;
import com.qinghe.music163pro.api.MusicApiHelper;
import com.qinghe.music163pro.model.Song;
import com.qinghe.music163pro.util.AudioTranscoder;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages downloading songs to /sdcard/163Music/Download/
 * Each song is saved in its own subfolder with song.mp3 and info.json.
 */
public class DownloadManager {

    private static final String TAG = "DownloadManager";
    private static final String DOWNLOAD_DIR = "163Music/Download";
    private static final String INFO_FILE = "info.json";
    private static final String SONG_FILE = "song.mp3";
    private static final String LYRICS_FILE = "lyrics.lrc";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Progress info for an active download task. */
    public static class DownloadProgress {
        public final Song song;
        public long downloadedBytes;
        public long totalBytes;
        public int percent;
        public String status; // "downloading", "transcoding", "complete", "error"
        public String errorMsg;
        public long startTime;
        public long lastUpdateTime;
        public long lastDownloadedBytes;
        public int speedBytesPerSec;

        public DownloadProgress(Song song) {
            this.song = song;
            this.status = "downloading";
            this.startTime = System.currentTimeMillis();
            this.lastUpdateTime = this.startTime;
        }

        public String getFormattedSize() {
            return formatFileSize(downloadedBytes);
        }

        public String getTotalFormattedSize() {
            return formatFileSize(totalBytes);
        }

        public String getSpeedText() {
            if (status.equals("complete") || status.equals("error")) return "";
            return formatFileSize(speedBytesPerSec) + "/s";
        }

        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + "B";
            if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
            return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
        }

        public void updateSpeed() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastUpdateTime;
            if (elapsed >= 500) {
                long deltaBytes = downloadedBytes - lastDownloadedBytes;
                speedBytesPerSec = (int) (deltaBytes * 1000 / elapsed);
                lastUpdateTime = now;
                lastDownloadedBytes = downloadedBytes;
            }
        }
    }

    /** Listener for download progress changes (called on main thread). */
    public interface OnDownloadProgressListener {
        void onProgressChanged(Map<String, DownloadProgress> activeDownloads);
        void onDownloadAdded(DownloadProgress progress);
        void onDownloadRemoved(String songKey);
    }

    private static final CopyOnWriteArrayList<OnDownloadProgressListener> progressListeners = new CopyOnWriteArrayList<>();
    /** Active download tasks, keyed by song key (bilibili_<cid> or netease_<id>). */
    private static final LinkedHashMap<String, DownloadProgress> activeDownloads = new LinkedHashMap<>();

    public interface DownloadCallback {
        void onSuccess(String filePath);
        void onError(String message);
    }

    public interface BatchDownloadCallback {
        void onProgress(int current, int total, String songName);
        void onAllComplete(int successCount, int skipCount, int failCount);
        void onSingleError(String songName, String message);
    }

    private static volatile boolean batchCancelled = false;

    /** Register a progress listener. */
    public static void addProgressListener(OnDownloadProgressListener listener) {
        if (listener != null && !progressListeners.contains(listener)) {
            progressListeners.add(listener);
        }
    }

    /** Unregister a progress listener. */
    public static void removeProgressListener(OnDownloadProgressListener listener) {
        progressListeners.remove(listener);
    }

    /** Get all active download tasks (for initial UI state). */
    public static Map<String, DownloadProgress> getActiveDownloads() {
        return new LinkedHashMap<>(activeDownloads);
    }

    /** Generate a unique key for a song download task. */
    private static String getSongKey(Song song) {
        if (song.isBilibili()) {
            return "bilibili_" + song.getCid();
        }
        return "netease_" + song.getId();
    }

    private static void notifyProgressChanged() {
        mainHandler.post(() -> {
            Map<String, DownloadProgress> snapshot = new LinkedHashMap<>(activeDownloads);
            for (OnDownloadProgressListener l : progressListeners) {
                l.onProgressChanged(snapshot);
            }
        });
    }

    private static void notifyDownloadAdded(DownloadProgress progress) {
        mainHandler.post(() -> {
            for (OnDownloadProgressListener l : progressListeners) {
                l.onDownloadAdded(progress);
            }
        });
    }

    private static void notifyDownloadRemoved(String songKey) {
        mainHandler.post(() -> {
            for (OnDownloadProgressListener l : progressListeners) {
                l.onDownloadRemoved(songKey);
            }
        });
    }

    /**
     * Download a song to /sdcard/163Music/Download/<folder>/
     * Saves both song.mp3 and info.json (with song id, name, artist, album).
     */
    public static void downloadSong(Song song, String cookie, DownloadCallback callback) {
        executor.execute(() -> {
            try {
                String songKey = getSongKey(song);
                // Remove completed/error entries for same song
                activeDownloads.remove(songKey);

                DownloadProgress progress = new DownloadProgress(song);
                activeDownloads.put(songKey, progress);
                notifyDownloadAdded(progress);

                if (song.isBilibili()) {
                    BilibiliApiHelper.getAudioStreamUrl(song.getBvid(), song.getCid(), cookie,
                            new BilibiliApiHelper.AudioStreamCallback() {
                                @Override
                                public void onResult(String url) {
                                    executor.execute(() -> {
                                        doDownload(song, url, true, callback, songKey, progress);
                                    });
                                }

                                @Override
                                public void onError(String message) {
                                    progress.status = "error";
                                    progress.errorMsg = message;
                                    notifyProgressChanged();
                                    mainHandler.post(() -> callback.onError("获取下载链接失败: " + message));
                                }
                            });
                    return;
                }
                // First get a fresh URL
                MusicApiHelper.getSongUrl(song.getId(), cookie, new MusicApiHelper.UrlCallback() {
                    @Override
                    public void onResult(String url) {
                        executor.execute(() -> {
                            doDownload(song, url, false, callback, songKey, progress);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        progress.status = "error";
                        progress.errorMsg = message;
                        notifyProgressChanged();
                        mainHandler.post(() -> callback.onError("获取下载链接失败: " + message));
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("下载失败: " + e.getMessage()));
            }
        });
    }

    private static void doDownload(Song song, String urlStr, boolean bilibili,
                                   DownloadCallback callback, String songKey,
                                   DownloadProgress progress) {
        try {
            File songDir = getSongDir(song);
            if (!songDir.exists()) {
                if (!songDir.mkdirs()) {
                    progress.status = "error";
                    progress.errorMsg = "无法创建下载目录";
                    notifyProgressChanged();
                    mainHandler.post(() -> callback.onError("无法创建下载目录"));
                    return;
                }
            }

            // For Bilibili: download to temp file first, then transcode
            File outputFile;
            File tempFile = null;
            if (bilibili) {
                tempFile = new File(songDir, "song.tmp");
                outputFile = new File(songDir, SONG_FILE);
            } else {
                outputFile = new File(songDir, SONG_FILE);
            }
            File downloadTarget = bilibili ? tempFile : outputFile;

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (bilibili) {
                conn.setRequestProperty("Referer", "https://www.bilibili.com");
            }

            try {
                int contentLength = conn.getContentLength();
                progress.totalBytes = contentLength > 0 ? contentLength : -1;

                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(downloadTarget);
                byte[] buffer = new byte[8192];
                int len;
                long totalRead = 0;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                    totalRead += len;

                    progress.downloadedBytes = totalRead;
                    if (progress.totalBytes > 0) {
                        progress.percent = (int) (totalRead * 100 / progress.totalBytes);
                    }
                    progress.updateSpeed();
                    notifyProgressChanged();
                }
                fos.flush();
                fos.close();
                is.close();

                // For Bilibili: transcode MP4 audio to proper M4A/AAC
                if (bilibili && tempFile.exists()) {
                    boolean needsTranscode = AudioTranscoder.needsTranscoding(tempFile.getAbsolutePath());
                    if (needsTranscode) {
                        Log.i(TAG, "Bilibili audio needs transcoding: " + tempFile.getAbsolutePath());
                        progress.status = "transcoding";
                        progress.percent = -1; // indeterminate
                        notifyProgressChanged();

                        boolean transcodeOk = AudioTranscoder.transcodeToMp3Sync(
                                tempFile.getAbsolutePath(), outputFile.getAbsolutePath());

                        if (transcodeOk) {
                            // Transcode success, delete temp file
                            tempFile.delete();
                            Log.i(TAG, "Bilibili audio transcoded successfully: " + song.getName());
                        } else {
                            // Transcode failed, rename temp as fallback
                            Log.w(TAG, "Bilibili transcode failed, keeping original for " + song.getName());
                            if (!outputFile.exists()) {
                                tempFile.renameTo(outputFile);
                            } else {
                                tempFile.delete();
                            }
                        }
                    } else {
                        // Already playable format, just rename
                        tempFile.renameTo(outputFile);
                    }
                }

                // Save song info JSON
                saveSongInfo(songDir, song);

                // Download lyrics (NetEase only)
                if (!bilibili) {
                    downloadLyrics(songDir, song);
                }

                progress.downloadedBytes = totalRead;
                progress.totalBytes = totalRead;
                progress.percent = 100;
                progress.status = "complete";
                notifyProgressChanged();

                // Delay removal so UI can show "complete" briefly
                final String filePath = outputFile.getAbsolutePath();
                mainHandler.postDelayed(() -> {
                    activeDownloads.remove(songKey);
                    notifyDownloadRemoved(songKey);
                }, 2000);

                mainHandler.post(() -> callback.onSuccess(filePath));
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Download error", e);
            progress.status = "error";
            progress.errorMsg = e.getMessage();
            notifyProgressChanged();
            mainHandler.postDelayed(() -> {
                activeDownloads.remove(songKey);
                notifyDownloadRemoved(songKey);
            }, 3000);
            mainHandler.post(() -> callback.onError("下载失败: " + e.getMessage()));
        }
    }

    /**
     * Save song metadata to info.json in the song's download folder.
     */
    private static void saveSongInfo(File songDir, Song song) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", song.getId());
            obj.put("name", song.getName());
            obj.put("artist", song.getArtist());
            obj.put("album", song.getAlbum());
            obj.put("source", song.getSource());
            obj.put("bvid", song.getBvid());
            obj.put("cid", song.getCid());

            File infoFile = new File(songDir, INFO_FILE);
            FileOutputStream fos = new FileOutputStream(infoFile);
            OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(obj.toString(2));
            writer.flush();
            writer.close();
            fos.close();
        } catch (Exception e) {
            Log.w(TAG, "Error saving song info", e);
        }
    }

    /**
     * Download lyrics for a song and save as lyrics.lrc in the song's download folder.
     * Also downloads translated lyrics as tlyrics.lrc if available.
     */
    private static void downloadLyrics(File songDir, Song song) {
        if (song.getId() <= 0) return;
        try {
            String lrcText = MusicApiHelper.fetchLyricsSync(song.getId(), null);
            if (lrcText != null && !lrcText.isEmpty()) {
                File lrcFile = new File(songDir, LYRICS_FILE);
                try (FileOutputStream fos = new FileOutputStream(lrcFile);
                     OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8")) {
                    writer.write(lrcText);
                    writer.flush();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error downloading lyrics", e);
        }
        // Also download translated lyrics
        try {
            String tlyricText = MusicApiHelper.fetchTranslatedLyricsSync(song.getId(), null);
            if (tlyricText != null && !tlyricText.isEmpty()) {
                File tlyricFile = new File(songDir, "tlyrics.lrc");
                try (FileOutputStream fos = new FileOutputStream(tlyricFile);
                     OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8")) {
                    writer.write(tlyricText);
                    writer.flush();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error downloading translated lyrics", e);
        }
    }

    /**
     * Load song metadata from info.json in a download folder.
     * @return Song with real id, name, artist, album set; or null on failure
     */
    public static Song loadSongInfo(File songDir) {
        File infoFile = new File(songDir, INFO_FILE);
        if (!infoFile.exists()) return null;
        try {
            FileInputStream fis = new FileInputStream(infoFile);
            InputStreamReader reader = new InputStreamReader(fis, "UTF-8");
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            reader.close();
            fis.close();

            JSONObject obj = new JSONObject(sb.toString());
            Song song = new Song(
                    obj.optLong("id", 0),
                    obj.optString("name", ""),
                    obj.optString("artist", ""),
                    obj.optString("album", "")
            );
            song.setSource(obj.optString("source", null));
            song.setBvid(obj.optString("bvid", ""));
            song.setCid(obj.optLong("cid", 0));
            // Set URL to the local mp3 path
            File mp3 = new File(songDir, SONG_FILE);
            if (mp3.exists()) {
                song.setUrl(mp3.getAbsolutePath());
            }
            return song;
        } catch (Exception e) {
            Log.w(TAG, "Error loading song info from " + songDir, e);
            return null;
        }
    }

    /**
     * Download a list of songs sequentially, skipping already-downloaded ones.
     * Runs on a background thread; all callbacks are posted to the main thread.
     * @param songs list of songs to download
     * @param cookie authentication cookie (NetEase or Bilibili)
     * @param callback batch progress and completion callback
     */
    public static void batchDownloadSongs(List<Song> songs, String cookie, BatchDownloadCallback callback) {
        if (songs == null || songs.isEmpty()) {
            if (callback != null) {
                mainHandler.post(() -> callback.onAllComplete(0, 0, 0));
            }
            return;
        }
        batchCancelled = false;
        executor.execute(() -> {
            int successCount = 0;
            int skipCount = 0;
            int failCount = 0;
            int total = songs.size();

            for (int i = 0; i < total; i++) {
                if (batchCancelled) break;

                Song song = songs.get(i);
                final String displayName = song.getName();
                final int progress = i + 1;
                mainHandler.post(() -> {
                    if (callback != null) callback.onProgress(progress, total, displayName);
                });

                // Skip already downloaded
                if (isDownloaded(song)) {
                    skipCount++;
                    continue;
                }

                // Use a synchronous wrapper for each song download
                boolean ok = downloadSingleSongSync(song, cookie);
                if (ok) {
                    successCount++;
                } else {
                    failCount++;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onSingleError(displayName, "下载失败");
                    });
                }
            }

            final int s = successCount, sk = skipCount, f = failCount;
            mainHandler.post(() -> {
                if (callback != null) callback.onAllComplete(s, sk, f);
            });
        });
    }

    /**
     * Cancel the current batch download.
     */
    public static void cancelBatchDownload() {
        batchCancelled = true;
    }

    /**
     * Check if a batch download is in progress.
     */
    public static boolean isBatchDownloading() {
        // Check if any active download is in progress
        for (DownloadProgress p : activeDownloads.values()) {
            if ("downloading".equals(p.status) || "transcoding".equals(p.status)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the count of currently active downloads.
     */
    public static int getActiveDownloadCount() {
        return activeDownloads.size();
    }

    /**
     * Get a summary of active downloads for UI display.
     * Returns null if no active downloads.
     */
    public static String getActiveDownloadSummary() {
        if (activeDownloads.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (DownloadProgress p : activeDownloads.values()) {
            if (sb.length() > 0) sb.append("\n");
            if ("downloading".equals(p.status)) {
                sb.append(p.song.getName()).append(" ").append(p.percent).append("%");
            } else if ("transcoding".equals(p.status)) {
                sb.append(p.song.getName()).append(" 转码中...");
            } else if ("complete".equals(p.status)) {
                sb.append(p.song.getName()).append(" 下载完成");
            } else if ("error".equals(p.status)) {
                sb.append(p.song.getName()).append(" 失败");
            }
        }
        return sb.toString();
    }

    /**
     * Synchronous single-song download (blocks caller thread).
     * Used internally by batchDownloadSongs.
     * Returns true on success, false on failure.
     */
    private static boolean downloadSingleSongSync(Song song, String cookie) {
        try {
            if (batchCancelled) return false;
            if (song.isBilibili()) {
                // Synchronous Bilibili URL fetch
                String[] result = new String[1];
                Exception[] error = new Exception[1];
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                BilibiliApiHelper.getAudioStreamUrl(song.getBvid(), song.getCid(), cookie,
                        new BilibiliApiHelper.AudioStreamCallback() {
                            @Override
                            public void onResult(String url) {
                                result[0] = url;
                                latch.countDown();
                            }

                            @Override
                            public void onError(String message) {
                                error[0] = new Exception(message);
                                latch.countDown();
                            }
                        });
                latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
                if (batchCancelled) return false;
                if (result[0] == null) return false;
                return doDownloadSync(song, result[0], true);
            }

            // NetEase: synchronous URL fetch
            String[] urlResult = new String[1];
            Exception[] urlError = new Exception[1];
            java.util.concurrent.CountDownLatch urlLatch = new java.util.concurrent.CountDownLatch(1);
            MusicApiHelper.getSongUrl(song.getId(), cookie, new MusicApiHelper.UrlCallback() {
                @Override
                public void onResult(String url) {
                    urlResult[0] = url;
                    urlLatch.countDown();
                }

                @Override
                public void onError(String message) {
                    urlError[0] = new Exception(message);
                    urlLatch.countDown();
                }
            });
            urlLatch.await(15, java.util.concurrent.TimeUnit.SECONDS);
            if (batchCancelled) return false;
            if (urlResult[0] == null) return false;
            return doDownloadSync(song, urlResult[0], false);
        } catch (Exception e) {
            Log.w(TAG, "Sync download error for " + song.getName(), e);
            return false;
        }
    }

    /**
     * Synchronous file download (blocks caller thread).
     * Returns true on success.
     * Also updates progress tracking for batch downloads.
     * For Bilibili songs: downloads to temp file, then transcodes MP4→M4A.
     */
    private static boolean doDownloadSync(Song song, String urlStr, boolean bilibili) {
        String songKey = getSongKey(song);
        // Clean up previous entry for same song
        activeDownloads.remove(songKey);

        DownloadProgress progress = new DownloadProgress(song);
        activeDownloads.put(songKey, progress);
        notifyDownloadAdded(progress);

        try {
            File songDir = getSongDir(song);
            if (!songDir.exists()) {
                if (!songDir.mkdirs()) {
                    progress.status = "error";
                    progress.errorMsg = "无法创建下载目录";
                    notifyProgressChanged();
                    return false;
                }
            }

            // For Bilibili: download to temp file first, then transcode
            File outputFile;
            File tempFile = null;
            if (bilibili) {
                tempFile = new File(songDir, "song.tmp");
                outputFile = new File(songDir, SONG_FILE);
            } else {
                outputFile = new File(songDir, SONG_FILE);
            }
            File downloadTarget = bilibili ? tempFile : outputFile;

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (bilibili) {
                conn.setRequestProperty("Referer", "https://www.bilibili.com");
            }

            try {
                int contentLength = conn.getContentLength();
                progress.totalBytes = contentLength > 0 ? contentLength : -1;

                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(downloadTarget);
                byte[] buffer = new byte[8192];
                int len;
                long totalRead = 0;
                while ((len = is.read(buffer)) != -1) {
                    if (batchCancelled) {
                        fos.close();
                        is.close();
                        downloadTarget.delete();
                        progress.status = "error";
                        progress.errorMsg = "已取消";
                        notifyProgressChanged();
                        activeDownloads.remove(songKey);
                        notifyDownloadRemoved(songKey);
                        return false;
                    }
                    fos.write(buffer, 0, len);
                    totalRead += len;

                    progress.downloadedBytes = totalRead;
                    if (progress.totalBytes > 0) {
                        progress.percent = (int) (totalRead * 100 / progress.totalBytes);
                    }
                    progress.updateSpeed();
                    // Throttle UI notifications to reduce overhead
                    if (progress.percent % 3 == 0 || progress.downloadedBytes == progress.totalBytes) {
                        notifyProgressChanged();
                    }
                }
                fos.flush();
                fos.close();
                is.close();

                // For Bilibili: transcode MP4 audio to proper M4A/AAC
                if (bilibili && tempFile != null && tempFile.exists()) {
                    boolean needsTranscode = AudioTranscoder.needsTranscoding(tempFile.getAbsolutePath());
                    if (needsTranscode) {
                        Log.i(TAG, "Bilibili audio needs transcoding: " + song.getName());
                        progress.status = "transcoding";
                        progress.percent = -1;
                        notifyProgressChanged();

                        boolean transcodeOk = AudioTranscoder.transcodeToMp3Sync(
                                tempFile.getAbsolutePath(), outputFile.getAbsolutePath());

                        if (transcodeOk) {
                            tempFile.delete();
                            Log.i(TAG, "Bilibili audio transcoded: " + song.getName());
                        } else {
                            Log.w(TAG, "Bilibili transcode failed, keeping original: " + song.getName());
                            if (!outputFile.exists()) {
                                tempFile.renameTo(outputFile);
                            } else {
                                tempFile.delete();
                            }
                        }
                    } else {
                        // Already playable, just rename
                        tempFile.renameTo(outputFile);
                    }
                }

                saveSongInfo(songDir, song);
                if (!bilibili) {
                    downloadLyrics(songDir, song);
                }

                progress.downloadedBytes = totalRead;
                progress.totalBytes = totalRead;
                progress.percent = 100;
                progress.status = "complete";
                notifyProgressChanged();

                // Remove completed entry after a short delay
                mainHandler.postDelayed(() -> {
                    activeDownloads.remove(songKey);
                    notifyDownloadRemoved(songKey);
                }, 1500);

                return true;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Download error for " + song.getName(), e);
            progress.status = "error";
            progress.errorMsg = e.getMessage();
            notifyProgressChanged();
            mainHandler.postDelayed(() -> {
                activeDownloads.remove(songKey);
                notifyDownloadRemoved(songKey);
            }, 3000);
            return false;
        }
    }

    /**
     * Get the subfolder for a song inside the download directory.
     */
    private static File getSongDir(Song song) {
        File dir = new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR);
        if (song.isBilibili()) {
            String safeVideoTitle = sanitizeFileName(
                    !TextUtils.isEmpty(song.getAlbum()) ? song.getAlbum() : song.getName());
            String safePartTitle = sanitizeFileName(
                    !TextUtils.isEmpty(song.getName()) ? song.getName() : song.getAlbum());
            String folderName = safeVideoTitle;
            if (!safePartTitle.isEmpty() && !safePartTitle.equals(safeVideoTitle)) {
                folderName = safeVideoTitle + "_" + safePartTitle;
            }
            if (folderName.isEmpty()) {
                folderName = "bilibili_audio_" + song.getCid();
            }
            return new File(dir, folderName);
        }
        String safeName = song.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        String safeArtist = song.getArtist().replaceAll("[\\\\/:*?\"<>|]", "_");
        String folderName = safeName + " - " + safeArtist;
        return new File(dir, folderName);
    }

    private static String sanitizeFileName(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /**
     * Get list of downloaded song directories from /sdcard/163Music/Download/
     * Each directory should contain song.mp3 and info.json.
     * Also supports legacy flat .mp3 files for backward compatibility.
     */
    public static List<File> getDownloadedSongDirs() {
        List<File> dirs = new ArrayList<>();
        File dir = new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR);
        if (dir.exists() && dir.isDirectory()) {
            File[] listing = dir.listFiles();
            if (listing != null) {
                for (File f : listing) {
                    if (f.isDirectory() && new File(f, SONG_FILE).exists()) {
                        dirs.add(f);
                    }
                }
            }
        }
        return dirs;
    }

    /**
     * Get list of downloaded song files (legacy flat .mp3 files).
     * Kept for backward compatibility.
     */
    public static List<File> getDownloadedFiles() {
        List<File> files = new ArrayList<>();
        File dir = new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR);
        if (dir.exists() && dir.isDirectory()) {
            File[] listing = dir.listFiles();
            if (listing != null) {
                for (File f : listing) {
                    if (f.isFile() && f.getName().endsWith(".mp3")) {
                        files.add(f);
                    }
                }
            }
        }
        return files;
    }

    /**
     * Get the download directory path
     */
    public static String getDownloadDirPath() {
        return new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR).getAbsolutePath();
    }

    /**
     * Get the mp3 file path for a downloaded song.
     * Returns null if not downloaded.
     */
    public static String getDownloadedMp3Path(Song song) {
        File songDir = getSongDir(song);
        File mp3 = new File(songDir, SONG_FILE);
        if (mp3.exists()) return mp3.getAbsolutePath();
        // Legacy flat file fallback
        String safeName = song.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        String safeArtist = song.getArtist().replaceAll("[\\\\/:*?\"<>|]", "_");
        String fileName = safeName + " - " + safeArtist + ".mp3";
        File dir = new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR);
        File legacy = new File(dir, fileName);
        if (legacy.exists()) return legacy.getAbsolutePath();
        return null;
    }

    /**
     * Check if a song is already downloaded
     */
    public static boolean isDownloaded(Song song) {
        return getDownloadedMp3Path(song) != null;
    }

    /**
     * Delete a downloaded song (removes the entire song directory or legacy file).
     * @return true if deletion was successful
     */
    public static boolean deleteDownload(Song song) {
        // Try new subfolder format first
        String safeName = song.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        String safeArtist = song.getArtist().replaceAll("[\\\\/:*?\"<>|]", "_");
        String folderName = safeName + " - " + safeArtist;
        File dir = new File(Environment.getExternalStorageDirectory(), DOWNLOAD_DIR);
        File songDir = new File(dir, folderName);
        if (songDir.exists() && songDir.isDirectory()) {
            return deleteDir(songDir);
        }
        // Try legacy flat file
        String fileName = safeName + " - " + safeArtist + ".mp3";
        File legacy = new File(dir, fileName);
        if (legacy.exists()) {
            return legacy.delete();
        }
        return false;
    }

    /**
     * Recursively delete a directory and its contents.
     */
    private static boolean deleteDir(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDir(child);
                }
            }
        }
        return dir.delete();
    }
}
