package com.qinghe.music163pro.manager;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.qinghe.music163pro.model.DownloadTask;
import com.qinghe.music163pro.model.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages batch download operations with progress tracking
 */
public class BatchDownloadManager {
    private static final String TAG = "BatchDownloadManager";
    private static BatchDownloadManager instance;

    private final ConcurrentHashMap<Long, DownloadTask> downloadTasks;
    private final AtomicLong taskIdCounter;
    private final Handler mainHandler;
    private String cookie;
    private DownloadProgressListener progressListener;

    private BatchDownloadManager() {
        this.downloadTasks = new ConcurrentHashMap<>();
        this.taskIdCounter = new AtomicLong(0);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized BatchDownloadManager getInstance() {
        if (instance == null) {
            instance = new BatchDownloadManager();
        }
        return instance;
    }

    public void setCookie(String cookie) {
        this.cookie = cookie;
    }

    public void setProgressListener(DownloadProgressListener listener) {
        this.progressListener = listener;
    }

    /**
     * Download multiple songs
     */
    public void downloadSongs(List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            Log.w(TAG, "No songs to download");
            return;
        }

        for (Song song : songs) {
            // Check if song is already downloaded
            if (DownloadManager.isDownloaded(song)) {
                Log.d(TAG, "Song already downloaded: " + song.getName());
                continue;
            }

            // Check if song is already in download queue
            boolean alreadyQueued = false;
            for (DownloadTask task : downloadTasks.values()) {
                if (task.getSong().getName().equals(song.getName()) &&
                    task.getSong().getArtist().equals(song.getArtist())) {
                    alreadyQueued = true;
                    break;
                }
            }

            if (alreadyQueued) {
                Log.d(TAG, "Song already in download queue: " + song.getName());
                continue;
            }

            // Create download task
            long taskId = taskIdCounter.incrementAndGet();
            DownloadTask task = new DownloadTask(song, taskId);
            downloadTasks.put(taskId, task);

            // Start download
            startDownload(task);
        }
    }

    /**
     * Download a single song
     */
    public void downloadSong(Song song) {
        if (song == null) {
            Log.w(TAG, "Song is null");
            return;
        }

        // Check if song is already downloaded
        if (DownloadManager.isDownloaded(song)) {
            Log.d(TAG, "Song already downloaded: " + song.getName());
            if (progressListener != null) {
                mainHandler.post(() -> progressListener.onAlreadyDownloaded(song));
            }
            return;
        }

        // Check if song is already in download queue
        for (DownloadTask task : downloadTasks.values()) {
            if (task.getSong().getName().equals(song.getName()) &&
                task.getSong().getArtist().equals(song.getArtist())) {
                Log.d(TAG, "Song already in download queue: " + song.getName());
                if (progressListener != null) {
                    mainHandler.post(() -> progressListener.onAlreadyQueued(song));
                }
                return;
            }
        }

        // Create download task
        long taskId = taskIdCounter.incrementAndGet();
        DownloadTask task = new DownloadTask(song, taskId);
        downloadTasks.put(taskId, task);

        // Start download
        startDownload(task);
    }

    private void startDownload(DownloadTask task) {
        DownloadManager.downloadSongWithProgress(
            task.getSong(),
            cookie,
            task.getTaskId(),
            new DownloadManager.DownloadProgressCallback() {
                @Override
                public void onProgress(long taskId, Song song, int progress, long downloaded, long total) {
                    DownloadTask task = downloadTasks.get(taskId);
                    if (task != null) {
                        task.updateProgress(downloaded, total);
                        task.setProgress(progress);
                        if (progressListener != null) {
                            mainHandler.post(() -> progressListener.onProgress(task));
                        }
                    }
                }

                @Override
                public void onStatusChange(long taskId, Song song, String status) {
                    DownloadTask task = downloadTasks.get(taskId);
                    if (task != null) {
                        task.setStatus(status);
                        if (progressListener != null) {
                            mainHandler.post(() -> progressListener.onStatusChange(task));
                        }
                    }
                }

                @Override
                public void onError(long taskId, Song song, String message) {
                    DownloadTask task = downloadTasks.get(taskId);
                    if (task != null) {
                        task.setStatus("error");
                        task.setErrorMessage(message);
                        if (progressListener != null) {
                            mainHandler.post(() -> progressListener.onError(task, message));
                        }
                    }
                }

                @Override
                public void onSuccess(long taskId, Song song, String filePath) {
                    DownloadTask task = downloadTasks.get(taskId);
                    if (task != null) {
                        task.setStatus("completed");
                        task.setProgress(100);
                        if (progressListener != null) {
                            mainHandler.post(() -> progressListener.onSuccess(task, filePath));
                        }
                    }
                }
            }
        );
    }

    /**
     * Cancel a download task
     */
    public void cancelDownload(long taskId) {
        DownloadTask task = downloadTasks.get(taskId);
        if (task != null) {
            task.setStatus("cancelled");
            downloadTasks.remove(taskId);
            if (progressListener != null) {
                mainHandler.post(() -> progressListener.onCancelled(task));
            }
        }
    }

    /**
     * Cancel all downloads
     */
    public void cancelAllDownloads() {
        List<Long> taskIds = new ArrayList<>(downloadTasks.keySet());
        for (Long taskId : taskIds) {
            cancelDownload(taskId);
        }
    }

    /**
     * Get all download tasks
     */
    public List<DownloadTask> getAllTasks() {
        return new ArrayList<>(downloadTasks.values());
    }

    /**
     * Get active (downloading/pending) tasks
     */
    public List<DownloadTask> getActiveTasks() {
        List<DownloadTask> activeTasks = new ArrayList<>();
        for (DownloadTask task : downloadTasks.values()) {
            String status = task.getStatus();
            if ("pending".equals(status) || "downloading".equals(status)) {
                activeTasks.add(task);
            }
        }
        return activeTasks;
    }

    /**
     * Get completed tasks
     */
    public List<DownloadTask> getCompletedTasks() {
        List<DownloadTask> completedTasks = new ArrayList<>();
        for (DownloadTask task : downloadTasks.values()) {
            if ("completed".equals(task.getStatus())) {
                completedTasks.add(task);
            }
        }
        return completedTasks;
    }

    /**
     * Get failed tasks
     */
    public List<DownloadTask> getFailedTasks() {
        List<DownloadTask> failedTasks = new ArrayList<>();
        for (DownloadTask task : downloadTasks.values()) {
            if ("error".equals(task.getStatus())) {
                failedTasks.add(task);
            }
        }
        return failedTasks;
    }

    /**
     * Clear completed tasks
     */
    public void clearCompletedTasks() {
        List<Long> toRemove = new ArrayList<>();
        for (DownloadTask task : downloadTasks.values()) {
            if ("completed".equals(task.getStatus()) || "error".equals(task.getStatus())) {
                toRemove.add(task.getTaskId());
            }
        }
        for (Long taskId : toRemove) {
            downloadTasks.remove(taskId);
        }
    }

    /**
     * Listener interface for download progress
     */
    public interface DownloadProgressListener {
        void onProgress(DownloadTask task);
        void onStatusChange(DownloadTask task);
        void onSuccess(DownloadTask task, String filePath);
        void onError(DownloadTask task, String message);
        void onCancelled(DownloadTask task);
        void onAlreadyDownloaded(Song song);
        void onAlreadyQueued(Song song);
    }
}
