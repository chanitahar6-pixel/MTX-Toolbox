package app.mtx.toolbox.fs;

import app.mtx.toolbox.core.Prefs;

import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Sorting for the file panes. Uses a Collator so Arabic names order correctly. */
public final class FileSorter {

    private FileSorter() {}

    public static void sort(List<FileItem> items, int sortBy, boolean ascending, boolean foldersFirst) {
        final Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.SECONDARY);
        final int mult = ascending ? 1 : -1;

        Comparator<FileItem> cmp = new Comparator<FileItem>() {
            @Override
            public int compare(FileItem a, FileItem b) {
                if (foldersFirst && a.isDir != b.isDir) return a.isDir ? -1 : 1;
                int r;
                switch (sortBy) {
                    case Prefs.SORT_SIZE:
                        r = Long.compare(a.size, b.size);
                        break;
                    case Prefs.SORT_DATE:
                        r = Long.compare(a.mtime, b.mtime);
                        break;
                    case Prefs.SORT_TYPE:
                        r = collator.compare(a.extension(), b.extension());
                        break;
                    default:
                        r = 0;
                        break;
                }
                if (r == 0) r = collator.compare(a.name, b.name);
                return r * mult;
            }
        };
        Collections.sort(items, cmp);
    }
}
