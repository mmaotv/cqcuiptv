package com.miaomiaotv.cqcuiptv;

/** 一个频道：名字、播放地址、分组，以及可选的 EPG id / 台标。 */
public final class Channel {

    public final String name;
    public final String url;
    public final String group;
    public final String tvgId;
    public final String logo;

    public Channel(String name, String url, String group, String tvgId, String logo) {
        this.name = name;
        this.url = url;
        this.group = group == null ? "" : group;
        this.tvgId = tvgId == null ? "" : tvgId;
        this.logo = logo == null ? "" : logo;
    }
}
