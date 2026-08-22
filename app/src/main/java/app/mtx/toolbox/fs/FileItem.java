package app.mtx.toolbox.fs;

import app.mtx.toolbox.core.Kv;
import app.mtx.toolbox.core.Native;

import java.io.File;

/** One row in a file pane, built from the native {@code listDir} payload. */
public final class FileItem {

    public final String name;
    public final String path;
    public final boolean isDir;
    public final long size;
    public final long mtime;
    public final int mode;
    public final boolean isLink;
    public final boolean readable;
    public final boolean writable;

    /** Resolved lazily by the pane, only for visible rows. */
    public String kind;

    public FileItem(String parent, String name, boolean isDir, long size, long mtime,
                    int mode, boolean isLink, boolean readable, boolean writable) {
        this.name = name;
        this.path = parent.endsWith("/") ? parent + name : parent + "/" + name;
        this.isDir = isDir;
        this.size = size;
        this.mtime = mtime;
        this.mode = mode;
        this.isLink = isLink;
        this.readable = readable;
        this.writable = writable;
    }

    public static FileItem parse(String parent, String row) {
        String[] f = Kv.fields(row);
        if (f.length < 8) return null;
        try {
            return new FileItem(parent, f[0], "1".equals(f[1]), Long.parseLong(f[2]),
                    Long.parseLong(f[3]), Integer.parseInt(f[4]), "1".equals(f[5]),
                    "1".equals(f[6]), "1".equals(f[7]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Reads a single path through the native stat, so permissions match the engine's view. */
    public static FileItem of(String path) {
        String row = Native.statPath(path);
        if (row == null) return null;
        File f = new File(path);
        String parent = f.getParent();
        return parse(parent == null ? "/" : parent, row);
    }

    public String extension() {
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase();
    }

    public boolean isHidden() { return name.startsWith("."); }

    public File file() { return new File(path); }

    @Override
    public boolean equals(Object o) {
        return o instanceof FileItem && ((FileItem) o).path.equals(path);
    }

    @Override
    public int hashCode() { return path.hashCode(); }
}
