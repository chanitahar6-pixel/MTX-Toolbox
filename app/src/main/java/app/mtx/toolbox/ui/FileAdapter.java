package app.mtx.toolbox.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Fmt;
import app.mtx.toolbox.fs.FileItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** File pane rows with multi-selection. Icons are chosen without touching disk. */
public class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {

    public interface Listener {
        void onItemClick(FileItem item);
        void onItemLongClick(FileItem item);
        void onSelectionChanged();
    }

    private final List<FileItem> items = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Listener listener;
    private boolean selectionMode;

    public FileAdapter(Listener listener) { this.listener = listener; }

    public void setItems(List<FileItem> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        // Drop selections that no longer exist.
        Set<String> alive = new LinkedHashSet<>();
        for (FileItem i : items) if (selected.contains(i.path)) alive.add(i.path);
        selected.clear();
        selected.addAll(alive);
        if (selected.isEmpty()) selectionMode = false;
        notifyDataSetChanged();
    }

    public List<FileItem> items() { return new ArrayList<>(items); }

    public boolean selectionMode() { return selectionMode; }

    public List<FileItem> selection() {
        List<FileItem> out = new ArrayList<>();
        for (FileItem i : items) if (selected.contains(i.path)) out.add(i);
        return out;
    }

    public int selectionCount() { return selected.size(); }

    public void toggle(FileItem item) {
        if (!selected.remove(item.path)) selected.add(item.path);
        selectionMode = !selected.isEmpty();
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged();
    }

    public void selectAll() {
        for (FileItem i : items) selected.add(i.path);
        selectionMode = !selected.isEmpty();
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged();
    }

    public void clearSelection() {
        selected.clear();
        selectionMode = false;
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final FileItem item = items.get(position);
        h.name.setText(item.name);

        StringBuilder meta = new StringBuilder();
        if (item.isDir) meta.append("<dir>");
        else meta.append(Fmt.bytes(item.size));
        meta.append("   ").append(Fmt.date(item.mtime));
        if (item.isLink) meta.append("   \u2192 link");
        if (!item.readable) meta.append("   \u26a0");
        h.meta.setText(meta.toString());

        h.icon.setImageResource(iconFor(item));

        boolean isSelected = selected.contains(item.path);
        h.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        h.check.setChecked(isSelected);
        h.itemView.setBackgroundColor(isSelected
                ? h.itemView.getResources().getColor(R.color.selection)
                : 0x00000000);

        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectionMode) toggle(item);
                else if (listener != null) listener.onItemClick(item);
            }
        });
        h.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) listener.onItemLongClick(item);
                return true;
            }
        });
    }

    private int iconFor(FileItem item) {
        if (item.isDir) return R.drawable.ic_folder;
        String ext = item.extension();
        switch (ext) {
            case "apk":
            case "apks":
            case "apkm":
            case "xapk":
            case "aab":
                return R.drawable.ic_apk;
            case "zip":
            case "jar":
            case "7z":
            case "rar":
            case "tar":
            case "gz":
            case "bz2":
            case "xz":
            case "zst":
                return R.drawable.ic_archive;
            case "png":
            case "jpg":
            case "jpeg":
            case "gif":
            case "webp":
            case "bmp":
                return R.drawable.ic_image;
            case "so":
            case "dex":
            case "bin":
            case "arsc":
                return R.drawable.ic_binary;
            default:
                return R.drawable.ic_file;
        }
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView meta;
        final CheckBox check;

        VH(View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            name = v.findViewById(R.id.name);
            meta = v.findViewById(R.id.meta);
            check = v.findViewById(R.id.check);
        }
    }
}
