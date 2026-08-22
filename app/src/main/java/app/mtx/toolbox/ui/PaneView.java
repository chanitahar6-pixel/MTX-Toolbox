package app.mtx.toolbox.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Fmt;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.OpResult;
import app.mtx.toolbox.core.Prefs;
import app.mtx.toolbox.fs.FileItem;
import app.mtx.toolbox.fs.FileSorter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One independent file pane: its own path, its own selection, its own history.
 * Directory listing always happens on a background thread through the native
 * engine, so a slow or huge folder never freezes the UI.
 */
public class PaneView extends LinearLayout implements FileAdapter.Listener {

    public interface Callbacks {
        void onFileActivated(PaneView pane, FileItem item);
        void onSelectionChanged(PaneView pane);
        void onPaneFocused(PaneView pane);
        void onPathChanged(PaneView pane, String path);
        void onLoadError(PaneView pane, String path, String detail);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger loadToken = new AtomicInteger();

    private LinearLayout crumbs;
    private HorizontalScrollView crumbScroll;
    private RecyclerView list;
    private SwipeRefreshLayout refresh;
    private TextView empty;
    private TextView footer;

    private FileAdapter adapter;
    private Callbacks callbacks;
    private String path = "/";
    private boolean active;

    public PaneView(Context context) { super(context); init(); }

    public PaneView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setOrientation(VERTICAL);
        inflate(getContext(), R.layout.view_pane, this);
        crumbs = findViewById(R.id.crumbs);
        crumbScroll = findViewById(R.id.crumb_scroll);
        list = findViewById(R.id.list);
        refresh = findViewById(R.id.refresh);
        empty = findViewById(R.id.empty);
        footer = findViewById(R.id.footer);

        adapter = new FileAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(getContext()));
        list.setAdapter(adapter);
        list.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                if (callbacks != null) callbacks.onPaneFocused(PaneView.this);
                return false;
            }
        });
        refresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() { reload(); }
        });
        setBackgroundResource(R.drawable.bg_pane_idle);
    }

    public void setCallbacks(Callbacks cb) { this.callbacks = cb; }

    public String path() { return path; }

    public FileAdapter adapter() { return adapter; }

    public List<FileItem> selection() { return adapter.selection(); }

    public void clearSelection() { adapter.clearSelection(); }

    public void selectAll() { adapter.selectAll(); }

    public void setActive(boolean value) {
        active = value;
        setBackgroundResource(value ? R.drawable.bg_pane_active : R.drawable.bg_pane_idle);
    }

    public boolean isActive() { return active; }

    public boolean goUp() {
        File parent = new File(path).getParentFile();
        if (parent == null) return false;
        navigate(parent.getAbsolutePath());
        return true;
    }

    public void reload() { navigate(path); }

    public void navigate(final String target) {
        final String clean = target == null || target.isEmpty() ? "/" : target;
        final int token = loadToken.incrementAndGet();
        refresh.setRefreshing(true);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<FileItem> result = new ArrayList<>();
                String error = null;

                if (!Native.isAvailable()) {
                    error = Native.loadError();
                } else {
                    String[] rows = Native.listDir(clean);
                    if (rows == null) {
                        error = OpResult.safeLastError();
                    } else {
                        boolean hidden = Prefs.showHidden(getContext());
                        for (String row : rows) {
                            FileItem item = FileItem.parse(clean, row);
                            if (item == null) continue;
                            if (!hidden && item.isHidden()) continue;
                            result.add(item);
                        }
                        FileSorter.sort(result, Prefs.sortBy(getContext()),
                                Prefs.sortAscending(getContext()), Prefs.foldersFirst(getContext()));
                    }
                }

                final String err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != loadToken.get()) return;   // a newer navigation won
                        refresh.setRefreshing(false);
                        if (err != null) {
                            if (callbacks != null) callbacks.onLoadError(PaneView.this, clean, err);
                            return;
                        }
                        path = clean;
                        Prefs.setPanePath(getContext(), isLeftPane(), path);
                        adapter.setItems(result);
                        buildCrumbs();
                        updateFooter(result);
                        empty.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                        if (callbacks != null) callbacks.onPathChanged(PaneView.this, path);
                    }
                });
            }
        }, "mtx-list").start();
    }

    private boolean isLeftPane() { return getId() == R.id.pane_left; }

    private void updateFooter(List<FileItem> items) {
        int dirs = 0, files = 0;
        long bytes = 0;
        for (FileItem i : items) {
            if (i.isDir) dirs++;
            else { files++; if (i.size > 0) bytes += i.size; }
        }
        footer.setText(dirs + " \u25b8  " + files + " \u25ab  " + Fmt.bytes(bytes));
    }

    private void buildCrumbs() {
        crumbs.removeAllViews();
        List<String> parts = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        File f = new File(path);
        while (f != null) {
            parts.add(0, f.getName().isEmpty() ? "/" : f.getName());
            paths.add(0, f.getAbsolutePath());
            f = f.getParentFile();
        }
        for (int i = 0; i < parts.size(); i++) {
            final String target = paths.get(i);
            TextView tv = new TextView(getContext());
            tv.setText(parts.get(i));
            tv.setTextSize(13f);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setBackgroundResource(R.drawable.bg_crumb);
            tv.setPadding(14, 8, 14, 8);
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.setMargins(3, 0, 3, 0);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callbacks != null) callbacks.onPaneFocused(PaneView.this);
                    navigate(target);
                }
            });
            crumbs.addView(tv);
        }
        crumbScroll.post(new Runnable() {
            @Override
            public void run() { crumbScroll.fullScroll(View.FOCUS_RIGHT); }
        });
    }

    // ---- FileAdapter.Listener ---------------------------------------------
    @Override
    public void onItemClick(FileItem item) {
        if (callbacks != null) callbacks.onPaneFocused(this);
        if (item.isDir) navigate(item.path);
        else if (callbacks != null) callbacks.onFileActivated(this, item);
    }

    @Override
    public void onItemLongClick(FileItem item) {
        if (callbacks != null) callbacks.onPaneFocused(this);
        adapter.toggle(item);
    }

    @Override
    public void onSelectionChanged() {
        if (callbacks != null) callbacks.onSelectionChanged(this);
    }
}
