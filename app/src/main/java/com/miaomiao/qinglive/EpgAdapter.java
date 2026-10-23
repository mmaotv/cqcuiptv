package com.miaomiao.qinglive;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 节目单适配器：时间段 + 节目名，正在播出的加亮。 */
public final class EpgAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<Epg.Programme> data = new ArrayList<Epg.Programme>();

    public EpgAdapter(Context ctx) {
        inflater = LayoutInflater.from(ctx);
    }

    public void setData(List<Epg.Programme> list) {
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
    public Epg.Programme getItem(int position) {
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
            v = inflater.inflate(R.layout.item_epg, parent, false);
        }
        Epg.Programme p = data.get(position);
        long now = System.currentTimeMillis();
        boolean playing = p.start <= now && now < p.stop;

        TextView time = (TextView) v.findViewById(R.id.epg_time);
        TextView title = (TextView) v.findViewById(R.id.epg_title);
        time.setText(Epg.formatClock(p.start) + "-" + Epg.formatClock(p.stop));
        title.setText(p.title);
        if (playing) {
            time.setTextColor(0xFFF2B24C);
            title.setTextColor(Color.WHITE);
        } else if (p.stop < now) {
            time.setTextColor(0xFF6F7883);
            title.setTextColor(0xFF7E8794);
        } else {
            time.setTextColor(0xFF9AA3AE);
            title.setTextColor(0xFFD6DCE3);
        }
        return v;
    }
}
