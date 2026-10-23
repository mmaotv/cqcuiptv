package com.miaomiao.qinglive;

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
    private final int accent;
    private final List<Group> data = new ArrayList<Group>();
    private int selected = 0;

    public GroupAdapter(Context ctx) {
        inflater = LayoutInflater.from(ctx);
        accent = ctx.getResources().getColor(R.color.accent);
    }

    public void setData(List<Group> groups) {
        data.clear();
        if (groups != null) {
            data.addAll(groups);
        }
        notifyDataSetChanged();
    }

    /** 记住当前分类：触摸操作没有焦点，只能靠 selected 状态给出高亮。 */
    public void setSelected(int index) {
        selected = index;
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
        boolean on = position == selected;
        TextView name = (TextView) v.findViewById(R.id.group_name);
        name.setText(g.name);
        name.setTextColor(on ? accent : 0xFFEDEFF2);
        ((TextView) v.findViewById(R.id.group_count)).setText(String.valueOf(g.indices.size()));
        v.setSelected(on);
        return v;
    }
}
