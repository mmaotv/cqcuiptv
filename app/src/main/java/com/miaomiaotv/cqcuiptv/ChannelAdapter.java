package com.miaomiaotv.cqcuiptv;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 频道列表适配器：序号 + 频道名 + 分组。 */
public final class ChannelAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<Channel> data = new ArrayList<Channel>();

    public ChannelAdapter(Context ctx) {
        inflater = LayoutInflater.from(ctx);
    }

    public void setData(List<Channel> list) {
        data.clear();
        if (list != null) {
            data.addAll(list);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Channel getItem(int position) {
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
            v = inflater.inflate(R.layout.item_channel, parent, false);
        }
        Channel c = data.get(position);
        ((TextView) v.findViewById(R.id.no)).setText(String.valueOf(position + 1));
        ((TextView) v.findViewById(R.id.name)).setText(c.name);
        TextView tag = (TextView) v.findViewById(R.id.tag);
        if (c.group.length() > 0) {
            tag.setText(c.group);
            tag.setVisibility(View.VISIBLE);
        } else {
            tag.setVisibility(View.GONE);
        }
        return v;
    }
}
