package app.mtx.toolbox.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Kv;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.fs.FileItem;
import app.mtx.toolbox.fs.FileOps;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which tool opens a file. The decision comes from the native file type
 * analyzer (magic bytes first), and when several tools legitimately apply the user
 * gets to choose instead of being forced into one.
 */
public final class ToolRouter {

    public static final String EXTRA_PATH = "mtx.path";
    public static final String EXTRA_ARCHIVE = "mtx.archive";
    public static final String EXTRA_ENTRY = "mtx.entry";
    public static final String EXTRA_READONLY = "mtx.readonly";

    private static final long TEXT_EDIT_LIMIT = 8L * 1024 * 1024;

    private ToolRouter() {}

    public static void open(final Activity activity, final FileItem item) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String raw = Native.isAvailable() ? Native.analyzeType(item.path) : null;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (raw == null) {
                            // Analysis failed: hex still works on any readable file.
                            openHex(activity, item.path);
                            return;
                        }
                        route(activity, item, Kv.parse(raw));
                    }
                });
            }
        }, "mtx-type").start();
    }

    private static void route(final Activity activity, final FileItem item, Kv kv) {
        List<String> tools = kv.all("tool");
        final String mime = kv.get("mime", "*/*");
        if (tools.isEmpty()) tools.add("hex");

        if (tools.size() == 1) {
            launch(activity, item, tools.get(0), mime);
            return;
        }

        final List<String> options = new ArrayList<>(tools);
        options.add("external");
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) labels[i] = label(activity, options.get(i));

        new AlertDialog.Builder(activity)
                .setTitle(R.string.choose_tool)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        launch(activity, item, options.get(which), mime);
                    }
                })
                .show();
    }

    private static String label(Activity a, String tool) {
        switch (tool) {
            case "apk": return a.getString(R.string.open_as_apk);
            case "archive": return a.getString(R.string.open_as_archive);
            case "text":
            case "json":
            case "xml":
            case "smali": return a.getString(R.string.open_as_text);
            case "elf": return a.getString(R.string.open_as_elf);
            case "external": return a.getString(R.string.open_external);
            case "hex":
            default: return a.getString(R.string.open_as_hex);
        }
    }

    private static void launch(Activity activity, FileItem item, String tool, String mime) {
        switch (tool) {
            case "apk": {
                Intent i = new Intent(activity, ApkInfoActivity.class);
                i.putExtra(EXTRA_PATH, item.path);
                activity.startActivity(i);
                return;
            }
            case "archive": {
                Intent i = new Intent(activity, ArchiveActivity.class);
                i.putExtra(EXTRA_PATH, item.path);
                activity.startActivity(i);
                return;
            }
            case "elf": {
                Intent i = new Intent(activity, ElfActivity.class);
                i.putExtra(EXTRA_PATH, item.path);
                activity.startActivity(i);
                return;
            }
            case "axml": {
                Intent i = new Intent(activity, TextEditorActivity.class);
                i.putExtra(EXTRA_PATH, item.path);
                i.putExtra(EXTRA_READONLY, true);
                activity.startActivity(i);
                return;
            }
            case "text":
            case "json":
            case "xml":
            case "smali": {
                Intent i = new Intent(activity, TextEditorActivity.class);
                i.putExtra(EXTRA_PATH, item.path);
                i.putExtra(EXTRA_READONLY, item.size > TEXT_EDIT_LIMIT || !item.writable);
                activity.startActivity(i);
                return;
            }
            case "image":
            case "media":
            case "external": {
                try {
                    activity.startActivity(FileOps.openExternalIntent(activity, item, mime));
                } catch (Throwable t) {
                    openHex(activity, item.path);
                }
                return;
            }
            case "binary":
            case "dex":
            case "hex":
            default:
                openHex(activity, item.path);
        }
    }

    public static void openHex(Activity activity, String path) {
        Intent i = new Intent(activity, HexActivity.class);
        i.putExtra(EXTRA_PATH, path);
        activity.startActivity(i);
    }
}
