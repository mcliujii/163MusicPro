package com.qinghe.music163pro.model;

import java.io.Serializable;

/**
 * Model for playlist info (used in search results, favorites, etc.)
 */
public class PlaylistInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    private String name;
    private int trackCount;
    private String creator;

    public PlaylistInfo() {}

    public PlaylistInfo(long id, String name, int trackCount, String creator) {
        this.id = id;
        this.name = name;
        this.trackCount = trackCount;
        this.creator = creator;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getTrackCount() { return trackCount; }
    public void setTrackCount(int trackCount) { this.trackCount = trackCount; }

    public String getCreator() { return creator; }
    public void setCreator(String creator) { this.creator = creator; }
}
