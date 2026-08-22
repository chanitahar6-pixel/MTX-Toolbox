package app.mtx.toolbox.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.MtxLog;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.Prefs;
import app.mtx.toolbox.core.ThemeManager;
import app.mtx.toolbox.core.Workspace;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Theme, language and file-manager preferences. Changes apply immediately. */
public class SettingsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupToolScreen(getString(R.string.title_settings));
        render();
    }

    private void render() {
        List<SimpleAdapter.Row> rows = new ArrayList<>();

        rows.add(new SimpleAdapter.Row(getString(R.string.settings_appearance), null));

        rows.add(new SimpleAdapter.Row(getString(R.string.settings_theme), themeLabel(), null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { chooseTheme(); }
                }));

        rows.add(new SimpleAdapter.Row(getString(R.string.settings_language), languageLabel(), null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { chooseLanguage(); }
                }));

        rows.add(new SimpleAdapter.Row(getString(R.string.settings_general), null));

        rows.add(toggle(getString(R.string.show_hidden), Prefs.showHidden(this), new Runnable() {
            @Override
            public void run() {
                Prefs.setShowHidden(SettingsActivity.this, !Prefs.showHidden(SettingsActivity.this));
                render();
            }
        }));
        rows.add(toggle(getString(R.string.folders_first), Prefs.foldersFirst(this), new Runnable() {
            @Override
            public void run() {
                Prefs.setFoldersFirst(SettingsActivity.this, !Prefs.foldersFirst(SettingsActivity.this));
                render();
            }
        }));
        rows.add(toggle(getString(R.string.settings_confirm_overwrite), Prefs.confirmOverwrite(this),
                new Runnable() {
                    @Override
                    public void run() {
                        Prefs.setConfirmOverwrite(SettingsActivity.this,
                                !Prefs.confirmOverwrite(SettingsActivity.this));
                        render();
                    }
                }));

        rows.add(new SimpleAdapter.Row(getString(R.string.settings_workspace), null));
        rows.add(new SimpleAdapter.Row(Workspace.root(this).getAbsolutePath(),
                getString(R.string.settings_workspace)));

        rows.add(new SimpleAdapter.Row(getString(R.string.logs), latestLogName(), null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { openLatestLog(); }
                }));

        rows.add(new SimpleAdapter.Row(getString(R.string.native_core),
                Native.isAvailable() ? Native.coreVersion() : Native.loadError()));

        adapter.setRows(rows);
    }

    private SimpleAdapter.Row toggle(String label, boolean value, final Runnable action) {
        return new SimpleAdapter.Row(label, value ? "\u2713 " + getString(R.string.ok) : "\u2014",
                null, new View.OnClickListener() {
            @Override
            public void onClick(View v) { action.run(); }
        });
    }

    private String themeLabel() {
        String t = Prefs.theme(this);
        if (Prefs.THEME_LIGHT.equals(t)) return getString(R.string.settings_theme_light);
        if (Prefs.THEME_DARK.equals(t)) return getString(R.string.settings_theme_dark);
        return getString(R.string.settings_theme_system);
    }

    private String languageLabel() {
        String l = Prefs.language(this);
        if (Prefs.LANG_AR.equals(l)) return getString(R.string.settings_language_ar);
        if (Prefs.LANG_EN.equals(l)) return getString(R.string.settings_language_en);
        return getString(R.string.settings_language_system);
    }

    private void chooseTheme() {
        final String[] values = {Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK};
        String[] labels = {
                getString(R.string.settings_theme_system),
                getString(R.string.settings_theme_light),
                getString(R.string.settings_theme_dark)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_theme)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        Prefs.setTheme(SettingsActivity.this, values[which]);
                        ThemeManager.apply(SettingsActivity.this);
                        recreate();
                    }
                })
                .show();
    }

    private void chooseLanguage() {
        final String[] values = {Prefs.LANG_SYSTEM, Prefs.LANG_AR, Prefs.LANG_EN};
        String[] labels = {
                getString(R.string.settings_language_system),
                getString(R.string.settings_language_ar),
                getString(R.string.settings_language_en)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_language)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        Prefs.setLanguage(SettingsActivity.this, values[which]);
                        // Restart the task so every screen picks up the new locale (and RTL).
                        Intent i = new Intent(SettingsActivity.this, MainActivity.class);
                        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                    }
                })
                .show();
    }

    private File latestLog() {
        File[] files = MtxLog.logDir(this).listFiles();
        File newest = null;
        if (files != null) {
            for (File f : files) {
                if (!f.isFile()) continue;
                if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
            }
        }
        return newest;
    }

    private String latestLogName() {
        File f = latestLog();
        return f == null ? MtxLog.logDir(this).getAbsolutePath() : f.getName();
    }

    private void openLatestLog() {
        File f = latestLog();
        if (f == null) {
            toast(getString(R.string.no_results));
            return;
        }
        Intent i = new Intent(this, TextEditorActivity.class);
        i.putExtra(ToolRouter.EXTRA_PATH, f.getAbsolutePath());
        i.putExtra(ToolRouter.EXTRA_READONLY, true);
        startActivity(i);
    }
}
