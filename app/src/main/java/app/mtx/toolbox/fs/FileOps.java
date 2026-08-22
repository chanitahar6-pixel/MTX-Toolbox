package app.mtx.toolbox.fs;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.OpResult;
import app.mtx.toolbox.core.Workspace;
import app.mtx.toolbox.ops.Operation;
import app.mtx.toolbox.ops.OperationManager;
import app.mtx.toolbox.ops.OperationService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Every file-manager action, expressed as an operation on the native engine.
 * These are the internal entry points the UI calls; they are also usable from
 * anywhere else in the app.
 */
public final class FileOps {

    private FileOps() {}

    public static Operation copy(Context ctx, final List<FileItem> items, final String destDir,
                                 final boolean overwrite, OperationManager.Completion done) {
        return run(ctx, "copy", ctx.getString(R.string.op_copying) + " \u2192 " + destDir,
                new Operation.Body() {
                    @Override
                    public int execute(Operation op) {
                        for (FileItem item : items) {
                            if (op.isCancelRequested()) return OpResult.E_CANCELLED;
                            op.addLog("copy " + item.path);
                            int code = Native.copyPath(op.jobId(), item.path, destDir, overwrite, op);
                            if (!OpResult.isOk(code)) return code;
                        }
                        return OpResult.OK;
                    }
                }, done);
    }

    public static Operation move(Context ctx, final List<FileItem> items, final String destDir,
                                 final boolean overwrite, OperationManager.Completion done) {
        return run(ctx, "move", ctx.getString(R.string.op_moving) + " \u2192 " + destDir,
                new Operation.Body() {
                    @Override
                    public int execute(Operation op) {
                        for (FileItem item : items) {
                            if (op.isCancelRequested()) return OpResult.E_CANCELLED;
                            op.addLog("move " + item.path);
                            int code = Native.movePath(op.jobId(), item.path, destDir, overwrite, op);
                            if (!OpResult.isOk(code)) return code;
                        }
                        return OpResult.OK;
                    }
                }, done);
    }

    public static Operation delete(Context ctx, final List<FileItem> items,
                                   OperationManager.Completion done) {
        return run(ctx, "delete", ctx.getString(R.string.op_deleting), new Operation.Body() {
            @Override
            public int execute(Operation op) {
                for (FileItem item : items) {
                    if (op.isCancelRequested()) return OpResult.E_CANCELLED;
                    op.addLog("delete " + item.path);
                    int code = Native.deletePath(op.jobId(), item.path, op);
                    if (!OpResult.isOk(code)) return code;
                }
                return OpResult.OK;
            }
        }, done);
    }

    public static Operation extractArchive(Context ctx, final String archive, final String outDir,
                                          OperationManager.Completion done) {
        return run(ctx, "extract", ctx.getString(R.string.op_extracting) + ": " + new File(archive).getName(),
                new Operation.Body() {
                    @Override
                    public int execute(Operation op) {
                        op.addLog("extract " + archive + " -> " + outDir);
                        op.setOutput(outDir);
                        return Native.zipExtract(op.jobId(), archive, "", outDir, op);
                    }
                }, done);
    }

    public static Operation extractEntry(Context ctx, final String archive, final String entry,
                                         final String outDir, OperationManager.Completion done) {
        return run(ctx, "extract", ctx.getString(R.string.op_extracting) + ": " + entry,
                new Operation.Body() {
                    @Override
                    public int execute(Operation op) {
                        op.setOutput(outDir);
                        return Native.zipExtract(op.jobId(), archive, entry, outDir, op);
                    }
                }, done);
    }

    public static Operation testArchive(Context ctx, final String archive,
                                        OperationManager.Completion done) {
        return run(ctx, "test", ctx.getString(R.string.op_testing_archive), new Operation.Body() {
            @Override
            public int execute(Operation op) {
                String result = Native.zipTest(op.jobId(), archive, op);
                if (result == null) return OpResult.E_CORRUPT;
                op.setOutput(result);
                return OpResult.OK;
            }
        }, done);
    }

    public static Operation compare(Context ctx, final String a, final String b,
                                    OperationManager.Completion done) {
        return run(ctx, "compare", ctx.getString(R.string.op_comparing), new Operation.Body() {
            @Override
            public int execute(Operation op) {
                long[] r = Native.compareFiles(op.jobId(), a, b, op);
                if (r == null) return OpResult.E_IO;
                op.setOutput(r);
                return OpResult.OK;
            }
        }, done);
    }

    /** Quick metadata actions run inline: they are single syscalls. */
    public static int rename(FileItem item, String newName) {
        File parent = item.file().getParentFile();
        if (parent == null) return OpResult.E_NOENT;
        return Native.renamePath(item.path, new File(parent, Workspace.sanitizeName(newName)).getAbsolutePath());
    }

    public static int newFolder(String parentDir, String name) {
        return Native.mkdirs(new File(parentDir, Workspace.sanitizeName(name)).getAbsolutePath());
    }

    public static int newFile(String parentDir, String name) {
        return Native.createFile(new File(parentDir, Workspace.sanitizeName(name)).getAbsolutePath());
    }

    public static boolean existsInDestination(String destDir, List<FileItem> items) {
        for (FileItem item : items) if (new File(destDir, item.name).exists()) return true;
        return false;
    }

    public static String firstConflict(String destDir, List<FileItem> items) {
        for (FileItem item : items) if (new File(destDir, item.name).exists()) return item.name;
        return null;
    }

    // ---- framework interop (this is where Java is genuinely required) -------
    public static Uri uriFor(Context ctx, File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", file);
        }
        return Uri.fromFile(file);
    }

    public static Intent shareIntent(Context ctx, List<FileItem> items) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (FileItem item : items) {
            if (item.isDir) continue;
            uris.add(uriFor(ctx, item.file()));
        }
        Intent intent;
        if (uris.size() == 1) {
            intent = new Intent(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    public static Intent openExternalIntent(Context ctx, FileItem item, String mime) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uriFor(ctx, item.file()),
                mime == null || mime.isEmpty() ? "*/*" : mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private static Operation run(Context ctx, String kind, String title, Operation.Body body,
                                 OperationManager.Completion done) {
        Operation op = OperationManager.get(ctx).submit(kind, title, body, done);
        OperationService.ensureRunning(ctx);
        return op;
    }
}
