package com.miaomiao.qinglive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 频道分类：名字 + 该分类下频道在总列表里的下标（保证台号仍是全局的）。 */
public final class Group {

    public final String name;
    public final List<Integer> indices = new ArrayList<Integer>();

    Group(String name) {
        this.name = name;
    }

    /** 按 M3U 里的 group-title 分组；“全部”永远排第一，其余按出现顺序。 */
    public static List<Group> build(List<Channel> channels) {
        Map<String, Group> map = new LinkedHashMap<String, Group>();
        Group all = new Group("全部");
        for (int i = 0; i < channels.size(); i++) {
            all.indices.add(i);
            String g = channels.get(i).group;
            if (g == null || g.length() == 0) {
                g = "未分类";
            }
            Group group = map.get(g);
            if (group == null) {
                group = new Group(g);
                map.put(g, group);
            }
            group.indices.add(i);
        }
        List<Group> out = new ArrayList<Group>();
        out.add(all);
        out.addAll(map.values());
        return out;
    }
}
