package com.qinghe.music163pro.model;

import java.io.Serializable;

/**
 * Cloud item shown in the music cloud screen.
 */
public class CloudItem implements Serializable {
    private static final long serialVersionUID = 1L;

    private long cloudSongId;
    private long songId;
    private String songName;
    private String artist;
    private String album;
    private String fileName;
    private String fileExtension;
    private long fileSize;
    private boolean music;
    private String downloadUrl;

    public long getCloudSongId() {
        return cloudSongId;
    }

    public void setCloudSongId(long cloudSongId) {
        this.cloudSongId = cloudSongId;
    }

    public long getSongId() {
        return songId;
    }

    public void setSongId(long songId) {
        this.songId = songId;
    }

    public String getSongName() {
        return songName;
    }

    public void setSongName(String songName) {
        this.songName = songName;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileExtension() {
        return fileExtension;
    }

    public void setFileExtension(String fileExtension) {
        this.fileExtension = fileExtension;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public boolean isMusic() {
        return music;
    }

    public void setMusic(boolean music) {
        this.music = music;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public Song toSong() {
        Song song = new Song(songId > 0 ? songId : cloudSongId,
                getDisplayName(), artist != null ? artist : "", album != null ? album : "");
        if (downloadUrl != null && !downloadUrl.isEmpty()) {
            song.setUrl(downloadUrl);
        }
        return song;
    }

    public String getDisplayName() {
        if (songName != null && !songName.isEmpty()) {
            return songName;
        }
        if (fileName != null && !fileName.isEmpty()) {
            return fileName;
        }
        return "未命名";
    }

    public String getSubtitle() {
        StringBuilder builder = new StringBuilder();
        if (music) {
            if (artist != null && !artist.isEmpty()) {
                builder.append(artist);
            }
            if (album != null && !album.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append(" · ");
                }
                builder.append(album);
            }
        } else {
            if (fileExtension != null && !fileExtension.isEmpty()) {
                builder.append(fileExtension.toUpperCase());
            }
            String sizeText = formatSize(fileSize);
            if (!sizeText.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append(" · ");
                }
                builder.append(sizeText);
            }
        }
        return builder.toString();
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.getDefault(), "%.1fKB", bytes / 1024f);
        }
        return String.format(java.util.Locale.getDefault(), "%.1fMB", bytes / 1024f / 1024f);
    }
}
