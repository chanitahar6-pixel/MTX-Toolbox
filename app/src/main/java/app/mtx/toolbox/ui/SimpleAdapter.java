package app.mtx.toolbox.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import app.mtx.toolbox.R;

import java.util.ArrayList;
import java.util.List;

/** Two-line list used by every tool screen. Rows carry their own click action. */
public class SimpleAdapter extends RecyclerView.Adapter<SimpleAdapter.VH> {

    public static final class Row {
        public final String title;
        public final String subtitle;
        public final Object tag;
        public final View.OnClickListener onClick;
        public final View.OnLongClickListener onLongClick;

        public Row(String title, String subtitle) { this(title, subtitle, null, null, null); }

        public Row(String title, String subtitle, Object tag, View.OnClickListener onClick) {
            this(title, subtitle, tag, onClick, null);
        }

        public Row(String title, String subtitle, Object tag, View.OnClickListener onClick,
                   View.OnLongClickListener onLongClick) {
            this.title = title;
            this.subtitle = subtitle;
            this.tag = tag;
            this.onClick = onClick;
            this.onLongClick = onLongClick;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    public void setRows(List<Row> newRows) {
        rows.clear();
        if (newRows != null) rows.addAll(newRows);
        notifyDataSetChanged();
    }

    public void add(Row row) {
        rows.add(row);
        notifyItemInserted(rows.size() - 1);
    }

    public void clear() {
        rows.clear();
        notifyDataSetChanged();
    }

    public int size() { return rows.size(); }

    public Row get(int position) { return rows.get(position); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_two_line, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Row row = rows.get(position);
        holder.title.setText(row.title);
        if (row.subtitle == null || row.subtitle.isEmpty()) {
            holder.subtitle.setVisibility(View.GONE);
        } else {
            holder.subtitle.setVisibility(View.VISIBLE);
            holder.subtitle.setText(row.subtitle);
        }
        holder.itemView.setOnClickListener(row.onClick);
        holder.itemView.setOnLongClickListener(row.onLongClick);
        holder.itemView.setClickable(row.onClick != null);
    }

    @Override
    public int getItemCount() { return rows.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;

        VH(View v) {
            super(v);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
        }
    }
}
