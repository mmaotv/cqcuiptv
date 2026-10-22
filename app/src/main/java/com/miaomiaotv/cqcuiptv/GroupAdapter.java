package com.miaomiaotv.cqcuiptv;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 分类列表适配器：左栏的分类名 + 该分类频道数。 */
public final class GroupAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<Group> data = new ArrayList<Group>();

    public GroupAdapter(Context ctx) {
        inflater = LayoutInflater.from(ctx);
    }

    public void setData(List<Group> groups) {
        data.clear();
        if (groups != null) {
            data.addAll(groups);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Group getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = inflater.inflate(R.layout.item_group, parent, false);
        }
        Group g = data.get(position);
        ((TextView) v.findViewById(R.id.group_name)).setText(g.name);
        ((TextView) v.findViewById(R.id.group_count)).setText(String.valueOf(g.indices.size()));
        return v;
    }
}
