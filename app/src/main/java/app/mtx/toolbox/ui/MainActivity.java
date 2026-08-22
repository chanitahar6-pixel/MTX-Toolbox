package app.mtx.toolbox.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Fmt;
import app.mtx.toolbox.core.MtxLog;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.OpResult;
import app.mtx.toolbox.core.Prefs;
import app.mtx.toolbox.core.Workspace;
import app.mtx.toolbox.fs.FileItem;
import app.mtx.toolbox.fs.FileOps;
import app.mtx.toolbox.ops.Operation;
import app.mtx.toolbox.ops.OperationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The dual-pane file manager: the home of the app and the entry point to every tool. */
public class MainActivity extends BaseActivity
        implements PaneView.Callbacks, OperationManager.Listener {

    private static final int REQ_STORAGE = 41;

    private DrawerLayout drawer;
    private PaneView left;
    private PaneView right;
    private PaneView activePane;
    private HorizontalScrollView actionBar;
    private LinearLayout actionContainer;
    private TextView opsStatus;
    private TextView toolbarTitle;
    private Button switchPaneButton;
    private boolean wideLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        drawer = findViewById(R.id.drawer);
        left = findViewById(R.id.pane_left);
        right = findViewById(R.id.pane_right);
        actionBar = findViewById(R.id.action_bar);
        actionContainer = findViewById(R.id.action_container);
        opsStatus = findViewById(R.id.ops_status);
        toolbarTitle = findViewById(R.id.toolbar_title);
        switchPaneButton = findViewById(R.id.btn_switch_pane);

        left.setCallbacks(this);
        right.setCallbacks(this);
        activePane = left;
        left.setActive(true);

        findViewById(R.id.btn_menu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { drawer.openDrawer(GravityCompatStart()); }
        });
        findViewById(R.id.btn_search).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { openSearch(activePane.path()); }
        });
        findViewById(R.id.btn_overflow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showOverflow(v); }
        });
        findViewById(R.id.btn_ops).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, OperationsActivity.class)); }
        });
        switchPaneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { setActivePane(activePane == left ? right : left); }
        });

        buildDrawer();
        applyLayoutMode(getResources().getConfiguration());
        OperationManager.get(this).addListener(this);

        if (hasStorageAccess()) startPanes();
        else requestStorageAccess();
    }

    private int GravityCompatStart() { return androidx.core.view.GravityCompat.START; }

    @Override
    protected void onDestroy() {
        OperationManager.get(this).removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyLayoutMode(newConfig);
    }

    /** Both panes side by side on tablets and landscape; one at a time on phones. */
    private void applyLayoutMode(Configuration config) {
        int widthDp = config.screenWidthDp;
        wideLayout = widthDp >= 720 || config.smallestScreenWidthDp >= 600;
        if (wideLayout) {
            left.setVisibility(View.VISIBLE);
            right.setVisibility(View.VISIBLE);
            switchPaneButton.setVisibility(View.GONE);
        } else {
            switchPaneButton.setVisibility(View.VISIBLE);
            left.setVisibility(activePane == left ? View.VISIBLE : View.GONE);
            right.setVisibility(activePane == right ? View.VISIBLE : View.GONE);
        }
    }

    private void startPanes() {
        String internal = Environment.getExternalStorageDirectory().getAbsolutePath();
        String mtx = Workspace.root(this).getAbsolutePath();
        left.navigate(Prefs.panePath(this, true, internal));
        right.navigate(Prefs.panePath(this, false, mtx));
    }

    private void setActivePane(PaneView pane) {
        activePane = pane;
        left.setActive(pane == left);
        right.setActive(pane == right);
        if (!wideLayout) {
            left.setVisibility(pane == left ? View.VISIBLE : View.GONE);
            right.setVisibility(pane == right ? View.VISIBLE : View.GONE);
        }
        toolbarTitle.setText(new File(pane.path()).getName().isEmpty() ? "/" : new File(pane.path()).getName());
        refreshActionBar();
    }

    private PaneView otherPane() { return activePane == left ? right : left; }

    // ---- storage permission ------------------------------------------------
    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_needed)
                .setMessage(R.string.storage_permission_rationale)
                .setCancelable(false)
                .setPositiveButton(R.string.grant_permission, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                i.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            } catch (Throwable t) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            }
                        } else {
                            ActivityCompat.requestPermissions(MainActivity.this, new String[]{
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        // Still usable inside app-private storage.
                        startPanes();
                    }
                })
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) startPanes();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (left.path() == null || "/".equals(left.path())) startPanes();
        else { left.reload(); right.reload(); }
    }

    // ---- drawer ------------------------------------------------------------
    private void buildDrawer() {
        LinearLayout box = findViewById(R.id.drawer_items);
        box.removeAllViews();

        addDrawerHeader(box, getString(R.string.app_name));
        addDrawerItem(box, getString(R.string.menu_files), new Runnable() {
            @Override
            public void run() { closeDrawer(); }
        });
        addDrawerItem(box, getString(R.string.menu_apk_tools), new Runnable() {
            @Override
            public void run() {
                closeDrawer();
                pickAndOpen(getString(R.string.menu_apk_tools),
                        Arrays.asList("apk", "apks", "apkm", "xapk", "aab"), ApkInfoActivity.class);
            }
        });
        addDrawerItem(box, getString(R.string.menu_installed_apps), new Runnable() {
            @Override
            public void run() { closeDrawer(); startActivity(new Intent(MainActivity.this, InstalledAppsActivity.class)); }
        });
        addDrawerItem(box, getString(R.string.menu_android_tools), new Runnable() {
            @Override
            public void run() { closeDrawer(); startActivity(new Intent(MainActivity.this, DeviceInfoActivity.class)); }
        });
        addDrawerItem(box, getString(R.string.menu_archive_tools), new Runnable() {
            @Override
            public void run() {
                closeDrawer();
                pickAndOpen(getString(R.string.menu_archive_tools),
                        Arrays.asList("zip", "apk", "jar", "apks", "xapk", "apkm", "aab"), ArchiveActivity.class);
            }
        });
        addDrawerItem(box, getString(R.string.menu_text_tools), new Runnable() {
            @Override
            public void run() {
                closeDrawer();
                pickAndOpen(getString(R.string.menu_text_tools),
                        Arrays.asList("txt", "xml", "json", "smali", "java", "cpp", "h", "c", "sh",
                                "js", "html", "css", "ini", "properties", "log", "md", "yml", "yaml"),
                        TextEditorActivity.class);
            }
        });
        addDrawerItem(box, getString(R.string.menu_hex_tools), new Runnable() {
            @Override
            public void run() {
                closeDrawer();
                pickAndOpen(getString(R.string.menu_hex_tools), null, HexActivity.class);
            }
        });
        addDrawerItem(box, getString(R.string.menu_search), new Runnable() {
            @Override
            public void run() { closeDrawer(); openSearch(activePane.path()); }
        });
        addDrawerItem(box, getString(R.string.menu_storage_analyzer), new Runnable() {
            @Override
            public void run() {
                closeDrawer();
                Intent i = new Intent(MainActivity.this, StorageAnalyzerActivity.class);
                i.putExtra(ToolRouter.EXTRA_PATH, activePane.path());
                startActivity(i);
            }
        });
        addDrawerItem(box, getString(R.string.menu_projects), new Runnable() {
            @Override
            public void run() {
                closeDrawer();
                activePane.navigate(Workspace.dir(MainActivity.this, Workspace.PROJECTS).getAbsolutePath());
            }
        });
        addDrawerItem(box, getString(R.string.menu_operations), new Runnable() {
            @Override
            public void run() { closeDrawer(); startActivity(new Intent(MainActivity.this, OperationsActivity.class)); }
        });
        addDrawerItem(box, getString(R.string.menu_settings), new Runnable() {
            @Override
            public void run() { closeDrawer(); startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        addDrawerItem(box, getString(R.string.menu_about), new Runnable() {
            @Override
            public void run() { closeDrawer(); showAbout(); }
        });

        addDrawerHeader(box, getString(R.string.internal_storage));
        for (final File root : storageRoots()) {
            addDrawerItem(box, root.getName().isEmpty() ? root.getAbsolutePath() : labelForRoot(root),
                    new Runnable() {
                        @Override
                        public void run() {
                            closeDrawer();
                            activePane.navigate(root.getAbsolutePath());
                        }
                    });
        }
        addDrawerItem(box, getString(R.string.mtx_folder), new Runnable() {
            @Override
            public void run() {
                closeDrawer();
                activePane.navigate(Workspace.root(MainActivity.this).getAbsolutePath());
            }
        });

        Set<String> marks = Prefs.bookmarks(this);
        if (!marks.isEmpty()) {
            addDrawerHeader(box, getString(R.string.bookmarks));
            for (final String mark : marks) {
                addDrawerItem(box, new File(mark).getName() + "  \u2014  " + mark, new Runnable() {
                    @Override
                    public void run() {
                        closeDrawer();
                        activePane.navigate(mark);
                    }
                });
            }
        }
    }

    private String labelForRoot(File root) {
        String path = root.getAbsolutePath();
        if (path.equals(Environment.getExternalStorageDirectory().getAbsolutePath()))
            return getString(R.string.internal_storage);
        if (path.startsWith("/storage/usb") || path.toLowerCase().contains("usb"))
            return getString(R.string.usb_storage) + "  (" + root.getName() + ")";
        return getString(R.string.sd_card) + "  (" + root.getName() + ")";
    }

    /** Internal storage plus any removable volume we can actually read. */
    private List<File> storageRoots() {
        List<File> roots = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        File internal = Environment.getExternalStorageDirectory();
        roots.add(internal);
        seen.add(internal.getAbsolutePath());

        File[] appDirs = getExternalFilesDirs(null);
        if (appDirs != null) {
            for (File dir : appDirs) {
                if (dir == null) continue;
                // .../Android/data/<pkg>/files -> volume root
                File volume = dir;
                for (int i = 0; i < 4 && volume != null; i++) volume = volume.getParentFile();
                if (volume == null) continue;
                String path = volume.getAbsolutePath();
                if (seen.contains(path)) continue;
                if (volume.canRead()) {
                    roots.add(volume);
                    seen.add(path);
                }
            }
        }
        return roots;
    }

    private void addDrawerHeader(LinearLayout box, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setAllCaps(true);
        tv.setTextSize(12f);
        tv.setPadding(24, 24, 24, 8);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        box.addView(tv);
    }

    private void addDrawerItem(LinearLayout box, String label, final Runnable action) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15f);
        tv.setPadding(24, 20, 24, 20);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tv.setBackgroundResource(android.R.drawable.list_selector_background);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { action.run(); }
        });
        box.addView(tv);
    }

    private void closeDrawer() { drawer.closeDrawer(GravityCompatStart()); }

    private void showAbout() {
        String core = Native.isAvailable() ? Native.coreVersion() : Native.loadError();
        String body = getString(R.string.about_text) + "\n\n"
                + getString(R.string.native_core) + ": " + core + "\n"
                + getString(R.string.workspace_path, Workspace.root(this).getAbsolutePath());
        showText(getString(R.string.menu_about), body);
    }

    /** Lists candidates in the active folder so a tool can be opened without leaving the app. */
    private void pickAndOpen(String title, final List<String> extensions, final Class<?> target) {
        List<FileItem> all = activePane.adapter().items();
        final List<FileItem> matches = new ArrayList<>();
        for (FileItem item : all) {
            if (item.isDir) continue;
            if (extensions == null || extensions.contains(item.extension())) matches.add(item);
        }
        if (matches.isEmpty()) {
            toast(getString(R.string.no_results) + " \u2014 " + activePane.path());
            return;
        }
        String[] labels = new String[matches.size()];
        for (int i = 0; i < matches.size(); i++)
            labels[i] = matches.get(i).name + "   (" + Fmt.bytes(matches.get(i).size) + ")";

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        Intent i = new Intent(MainActivity.this, target);
                        i.putExtra(ToolRouter.EXTRA_PATH, matches.get(which).path);
                        startActivity(i);
                    }
                })
                .show();
    }

    private void openSearch(String root) {
        Intent i = new Intent(this, SearchActivity.class);
        i.putExtra(ToolRouter.EXTRA_PATH, root);
        startActivity(i);
    }

    // ---- overflow menu -----------------------------------------------------
    private void showOverflow(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        Menu m = menu.getMenu();
        m.add(0, 1, 0, R.string.new_folder);
        m.add(0, 2, 1, R.string.new_file);
        m.add(0, 3, 2, R.string.refresh);
        m.add(0, 4, 3, R.string.bookmark);
        m.add(0, 5, 4, R.string.select_all);
        m.add(0, 10, 5, getString(R.string.sort) + ": " + getString(R.string.sort_name));
        m.add(0, 11, 6, getString(R.string.sort) + ": " + getString(R.string.sort_size));
        m.add(0, 12, 7, getString(R.string.sort) + ": " + getString(R.string.sort_date));
        m.add(0, 13, 8, getString(R.string.sort) + ": " + getString(R.string.sort_type));
        m.add(0, 14, 9, Prefs.sortAscending(this) ? R.string.descending : R.string.ascending);
        m.add(0, 15, 10, R.string.show_hidden).setCheckable(true).setChecked(Prefs.showHidden(this));
        m.add(0, 16, 11, R.string.folders_first).setCheckable(true).setChecked(Prefs.foldersFirst(this));

        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        promptInput(getString(R.string.new_folder), "", new InputCallback() {
                            @Override
                            public void onInput(String value) {
                                if (value.isEmpty()) return;
                                int code = FileOps.newFolder(activePane.path(), value);
                                report(code);
                                activePane.reload();
                            }
                        });
                        return true;
                    case 2:
                        promptInput(getString(R.string.new_file), "", new InputCallback() {
                            @Override
                            public void onInput(String value) {
                                if (value.isEmpty()) return;
                                int code = FileOps.newFile(activePane.path(), value);
                                report(code);
                                activePane.reload();
                            }
                        });
                        return true;
                    case 3:
                        activePane.reload();
                        return true;
                    case 4:
                        Prefs.toggleBookmark(MainActivity.this, activePane.path());
                        buildDrawer();
                        toast(getString(R.string.bookmark));
                        return true;
                    case 5:
                        activePane.selectAll();
                        return true;
                    case 10: setSort(Prefs.SORT_NAME); return true;
                    case 11: setSort(Prefs.SORT_SIZE); return true;
                    case 12: setSort(Prefs.SORT_DATE); return true;
                    case 13: setSort(Prefs.SORT_TYPE); return true;
                    case 14:
                        Prefs.setSortAscending(MainActivity.this, !Prefs.sortAscending(MainActivity.this));
                        reloadBoth();
                        return true;
                    case 15:
                        Prefs.setShowHidden(MainActivity.this, !Prefs.showHidden(MainActivity.this));
                        reloadBoth();
                        return true;
                    case 16:
                        Prefs.setFoldersFirst(MainActivity.this, !Prefs.foldersFirst(MainActivity.this));
                        reloadBoth();
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    private void setSort(int sort) {
        Prefs.setSortBy(this, sort);
        reloadBoth();
    }

    private void reloadBoth() {
        left.reload();
        right.reload();
    }

    private void report(int code) {
        if (OpResult.isOk(code)) toast(getString(R.string.err_ok));
        else showError(OpResult.message(this, code), OpResult.detailed(this, code));
    }

    // ---- selection actions -------------------------------------------------
    private void refreshActionBar() {
        final List<FileItem> selection = activePane.selection();
        actionContainer.removeAllViews();
        if (selection.isEmpty()) {
            actionBar.setVisibility(View.GONE);
            return;
        }
        actionBar.setVisibility(View.VISIBLE);

        addAction(getString(R.string.items_selected, selection.size()), null);
        addAction(getString(R.string.copy) + " \u2192", new Runnable() {
            @Override
            public void run() { transfer(selection, true); }
        });
        addAction(getString(R.string.move) + " \u2192", new Runnable() {
            @Override
            public void run() { transfer(selection, false); }
        });
        addAction(getString(R.string.delete), new Runnable() {
            @Override
            public void run() { deleteSelection(selection); }
        });
        if (selection.size() == 1) {
            final FileItem single = selection.get(0);
            addAction(getString(R.string.rename), new Runnable() {
                @Override
                public void run() {
                    promptInput(getString(R.string.rename), single.name, new InputCallback() {
                        @Override
                        public void onInput(String value) {
                            if (value.isEmpty()) return;
                            report(FileOps.rename(single, value));
                            activePane.clearSelection();
                            activePane.reload();
                        }
                    });
                }
            });
            addAction(getString(R.string.properties), new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(MainActivity.this, PropertiesActivity.class);
                    i.putExtra(ToolRouter.EXTRA_PATH, single.path);
                    startActivity(i);
                }
            });
        }
        if (selection.size() == 2) {
            addAction(getString(R.string.compare), new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(MainActivity.this, CompareActivity.class);
                    i.putExtra(ToolRouter.EXTRA_PATH, selection.get(0).path);
                    i.putExtra(ToolRouter.EXTRA_ENTRY, selection.get(1).path);
                    startActivity(i);
                }
            });
        }
        addAction(getString(R.string.hash), new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(MainActivity.this, HashActivity.class);
                i.putExtra(ToolRouter.EXTRA_PATH, selection.get(0).path);
                startActivity(i);
            }
        });
        addAction(getString(R.string.share), new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(Intent.createChooser(FileOps.shareIntent(MainActivity.this, selection),
                            getString(R.string.share)));
                } catch (Throwable t) {
                    showError(getString(R.string.err_unsupported), String.valueOf(t.getMessage()));
                }
            }
        });
        addAction(getString(R.string.deselect_all), new Runnable() {
            @Override
            public void run() { activePane.clearSelection(); }
        });
    }

    private void addAction(String label, final Runnable action) {
        Button b = new Button(this, null, android.R.attr.buttonBarButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        if (action == null) b.setEnabled(false);
        else b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { action.run(); }
        });
        actionContainer.addView(b);
    }

    private void transfer(final List<FileItem> selection, final boolean isCopy) {
        final String dest = otherPane().path();
        if (dest.equals(activePane.path())) {
            toast(getString(R.string.same_folder));
            return;
        }
        final String conflict = FileOps.firstConflict(dest, selection);
        if (conflict != null && Prefs.confirmOverwrite(this)) {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.overwrite_prompt, conflict))
                    .setPositiveButton(R.string.ok, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            startTransfer(selection, dest, isCopy, true);
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        startTransfer(selection, dest, isCopy, conflict != null);
    }

    private void startTransfer(List<FileItem> selection, String dest, boolean isCopy, boolean overwrite) {
        OperationManager.Completion done = new OperationManager.Completion() {
            @Override
            public void onFinished(Operation op) { onOperationFinished(op); }
        };
        if (isCopy) FileOps.copy(this, selection, dest, overwrite, done);
        else FileOps.move(this, selection, dest, overwrite, done);
        activePane.clearSelection();
        toast(getString(R.string.operation_started, isCopy
                ? getString(R.string.op_copying) : getString(R.string.op_moving)));
    }

    private void deleteSelection(final List<FileItem> selection) {
        String message = selection.size() == 1
                ? getString(R.string.confirm_delete, selection.get(0).name)
                : getString(R.string.confirm_delete_multi, selection.size());
        confirm(message, new Runnable() {
            @Override
            public void run() {
                FileOps.delete(MainActivity.this, selection, new OperationManager.Completion() {
                    @Override
                    public void onFinished(Operation op) { onOperationFinished(op); }
                });
                activePane.clearSelection();
            }
        });
    }

    private void onOperationFinished(Operation op) {
        reloadBoth();
        if (op.state() == Operation.State.DONE) {
            toast(getString(R.string.operation_done, op.title));
        } else if (op.state() == Operation.State.FAILED) {
            showError(getString(R.string.operation_failed, op.title),
                    OpResult.message(this, op.resultCode())
                            + (op.error() == null ? "" : "\n\n" + op.error()));
        }
    }

    // ---- PaneView.Callbacks ------------------------------------------------
    @Override
    public void onFileActivated(PaneView pane, FileItem item) {
        setActivePane(pane);
        ToolRouter.open(this, item);
    }

    @Override
    public void onSelectionChanged(PaneView pane) {
        if (pane == activePane) refreshActionBar();
    }

    @Override
    public void onPaneFocused(PaneView pane) {
        if (pane != activePane) setActivePane(pane);
    }

    @Override
    public void onPathChanged(PaneView pane, String path) {
        if (pane == activePane) {
            String name = new File(path).getName();
            toolbarTitle.setText(name.isEmpty() ? path : name);
        }
    }

    @Override
    public void onLoadError(PaneView pane, String path, String detail) {
        MtxLog.w(this, "list", path + " -> " + detail);
        setStatusToast(getString(R.string.err_permission) + ": " + path);
    }

    private void setStatusToast(String msg) { toast(msg); }

    // ---- OperationManager.Listener ----------------------------------------
    @Override
    public void onOperationsChanged() {
        OperationManager mgr = OperationManager.get(this);
        int active = mgr.activeCount();
        if (active == 0) {
            opsStatus.setText("");
            return;
        }
        Operation first = null;
        for (Operation op : mgr.operations()) {
            if (!op.isFinished()) { first = op; break; }
        }
        if (first == null) return;
        opsStatus.setText(first.title + "   " + Fmt.bar(first.done(), first.total(), 10)
                + "  " + Fmt.percent(first.done(), first.total())
                + "  " + Fmt.speed(first.speed())
                + (active > 1 ? "   (+" + (active - 1) + ")" : ""));
    }

    @Override
    public void onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompatStart())) {
            closeDrawer();
            return;
        }
        if (!activePane.selection().isEmpty()) {
            activePane.clearSelection();
            return;
        }
        File parent = new File(activePane.path()).getParentFile();
        if (parent != null && parent.canRead()) {
            activePane.goUp();
            return;
        }
        super.onBackPressed();
    }
}
