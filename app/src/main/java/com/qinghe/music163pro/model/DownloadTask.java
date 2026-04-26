package com.qinghe.music163pro.model;

/**
 * Model class for tracking download task status
 */
public class DownloadTask {
    private Song song;
    private long totalBytes;
    private long downloadedBytes;
    private int progress; // 0-100
    private String status; // pending, downloading, completed, error
    private String errorMessage;
    private long taskId;

    public DownloadTask(Song song, long taskId) {
        this.song = song;
        this.taskId = taskId;
        this.totalBytes = 0;
        this.downloadedBytes = 0;
        this.progress = 0;
        this.status = "pending";
        this.errorMessage = "";
    }

    public Song getSong() {
        return song;
    }

    public void setSong(Song song) {
        this.song = song;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public long getDownloadedBytes() {
        return downloadedBytes;
    }

    public void setDownloadedBytes(long downloadedBytes) {
        this.downloadedBytes = downloadedBytes;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getTaskId() {
        return taskId;
    }

    public void setTaskId(long taskId) {
        this.taskId = taskId;
    }

    public void updateProgress(long downloaded, long total) {
        this.downloadedBytes = downloaded;
        this.totalBytes = total;
        if (total > 0) {
            this.progress = (int) ((downloaded * 100) / total);
        }
    }
}
