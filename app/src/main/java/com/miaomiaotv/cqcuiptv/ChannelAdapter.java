package com.miaomiaotv.cqcuiptv;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 频道列表适配器：全局台号 + 频道名 + 正在播出的节目。 */
public final class ChannelAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<Channel> data = new ArrayList<Channel>();
    private int[] numbers = new int[0];
    private Epg epg;

    public ChannelAdapter(Context ctx) {
        inflater = LayoutInflater.from(ctx);
    }

    /** numbers[i] 是该行在总列表里的下标，用来显示全局台号。 */
    public void setData(List<Channel> list, int[] numbers, Epg epg) {
        data.clear();
        this.numbers = numbers != null ? numbers : new int[0];
        this.epg = epg;
        if (list != null) {
            data.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void setEpg(Epg epg) {
        this.epg = epg;
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
        int number = position < numbers.length ? numbers[position] + 1 : position + 1;
        ((TextView) v.findViewById(R.id.no)).setText(String.valueOf(number));
        ((TextView) v.findViewById(R.id.name)).setText(c.name);

        TextView sub = (TextView) v.findViewById(R.id.tag);
        String text = "";
        if (epg != null) {
            String id = c.tvgId.length() > 0 ? c.tvgId : c.name;
            Epg.Programme now = epg.now(id);
            if (now != null) {
                text = now.title;
            } else {
                Epg.Programme next = epg.next(id);
                if (next != null) {
                    text = Epg.formatClock(next.start) + " " + next.title;
                }
            }
        }
        if (text.length() > 0) {
            sub.setText(text);
            sub.setVisibility(View.VISIBLE);
        } else {
            sub.setVisibility(View.GONE);
        }
        return v;
    }
}
