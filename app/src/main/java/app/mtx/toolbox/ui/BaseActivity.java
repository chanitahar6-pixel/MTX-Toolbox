package app.mtx.toolbox.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.LocaleHelper;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.ThemeManager;

/** Shared plumbing: locale, theme, the generic tool shell, dialogs. */
public abstract class BaseActivity extends AppCompatActivity {

    protected RecyclerView recycler;
    protected SimpleAdapter adapter;
    protected LinearLayout headerBox;
    protected LinearLayout toolbarActions;
    protected TextView statusView;
    protected TextView titleView;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
    }

    /** Builds the shared tool screen (toolbar + header + list + status). */
    protected void setupToolScreen(String title) {
        setContentView(R.layout.activity_generic);
        titleView = findViewById(R.id.title);
        headerBox = findViewById(R.id.header);
        toolbarActions = findViewById(R.id.toolbar_actions);
        statusView = findViewById(R.id.status);
        recycler = findViewById(R.id.recycler);

        titleView.setText(title);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        adapter = new SimpleAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        recycler.setAdapter(adapter);

        if (!Native.isAvailable()) setStatus(getString(R.string.native_core_missing));
    }

    protected Button addToolbarAction(String label, View.OnClickListener listener) {
        Button b = new Button(this, null, android.R.attr.buttonBarButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        toolbarActions.addView(b);
        return b;
    }

    protected View addHeaderView(View v) {
        headerBox.addView(v);
        return v;
    }

    protected void setStatus(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (statusView != null) statusView.setText(text);
            }
        });
    }

    protected void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    protected void showError(String title, String detail) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(detail)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    protected void showText(String title, String body) {
        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextIsSelectable(true);
        tv.setPadding(24, 24, 24, 24);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12f);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    protected interface InputCallback {
        void onInput(String value);
    }

    protected void promptInput(String title, String initial, final InputCallback cb) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.name_hint);
        if (initial != null) {
            input.setText(initial);
            input.setSelection(initial.length());
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        cb.onInput(input.getText().toString().trim());
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    protected void confirm(String message, final Runnable onYes) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) { onYes.run(); }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
