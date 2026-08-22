package app.mtx.toolbox.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Fmt;
import app.mtx.toolbox.core.MtxLog;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.OpResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;

/**
 * Text editor with line numbers, find/replace, go-to-line and a hard size guard.
 * Android binary XML is decoded through the native AXML parser and shown read-only.
 */
public class TextEditorActivity extends BaseActivity {

    private static final long EDIT_LIMIT = 8L * 1024 * 1024;
    private static final long VIEW_LIMIT = 24L * 1024 * 1024;

    private String path;
    private boolean readOnly;
    private boolean truncated;
    private boolean decoded;

    private EditText body;
    private TextView gutter;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);
        path = getIntent().getStringExtra(ToolRouter.EXTRA_PATH);
        readOnly = getIntent().getBooleanExtra(ToolRouter.EXTRA_READONLY, false);
        if (path == null) { finish(); return; }

        File f = new File(path);
        if (!f.canWrite() || f.length() > EDIT_LIMIT) readOnly = true;

        ((TextView) findViewById(R.id.title)).setText(f.getName());
        body = findViewById(R.id.editor);
        gutter = findViewById(R.id.gutter);
        status = findViewById(R.id.status);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        LinearLayout actions = findViewById(R.id.toolbar_actions);
        addAction(actions, getString(R.string.editor_find), new Runnable() {
            @Override
            public void run() { promptFind(); }
        });
        addAction(actions, getString(R.string.editor_goto_line), new Runnable() {
            @Override
            public void run() { promptGotoLine(); }
        });
        if (!readOnly) {
            addAction(actions, getString(R.string.editor_replace), new Runnable() {
                @Override
                public void run() { promptReplace(); }
            });
            addAction(actions, getString(R.string.save), new Runnable() {
                @Override
                public void run() { save(); }
            });
        }

        load();
    }

    private void addAction(LinearLayout box, String label, final Runnable action) {
        Button b = new Button(this, null, android.R.attr.buttonBarButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { action.run(); }
        });
        box.addView(b);
    }

    private void load() {
        final Handler main = new Handler(Looper.getMainLooper());
        status.setText(getString(R.string.op_scanning));

        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = null;
                String error = null;
                try {
                    File f = new File(path);
                    // Android binary XML: decode instead of showing raw bytes.
                    byte[] head = Native.isAvailable() ? Native.hexRead(path, 0, 4) : null;
                    boolean axml = head != null && head.length == 4 && head[0] == 0x03
                            && head[1] == 0x00 && head[2] == 0x08 && head[3] == 0x00;
                    if (axml) {
                        text = Native.axmlToXml(path);
                        decoded = true;
                        readOnly = true;
                        if (text == null) error = OpResult.safeLastError();
                    } else {
                        long limit = Math.min(f.length(), VIEW_LIMIT);
                        truncated = f.length() > limit;
                        byte[] buf = new byte[(int) limit];
                        InputStream in = new FileInputStream(f);
                        try {
                            int read = 0;
                            while (read < buf.length) {
                                int r = in.read(buf, read, buf.length - read);
                                if (r <= 0) break;
                                read += r;
                            }
                            text = new String(buf, 0, read, detectCharset(buf, read));
                        } finally {
                            in.close();
                        }
                    }
                } catch (Throwable t) {
                    error = String.valueOf(t.getMessage());
                    MtxLog.e(TextEditorActivity.this, "editor", "open failed: " + path, t);
                }

                final String finalText = text;
                final String finalError = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (finalError != null || finalText == null) {
                            status.setText(getString(R.string.err_encoding));
                            showError(getString(R.string.err_encoding), String.valueOf(finalError));
                            return;
                        }
                        body.setText(finalText);
                        if (readOnly) body.setKeyListener(null);
                        updateGutter();
                        StringBuilder s = new StringBuilder();
                        s.append(Fmt.bytes(new File(path).length()));
                        if (readOnly) s.append("   ").append(getString(R.string.editor_readonly));
                        if (decoded) s.append("   AXML decoded");
                        if (truncated) s.append("   ").append(getString(R.string.file_too_large_readonly));
                        status.setText(s.toString());
                    }
                });
            }
        }, "mtx-text").start();
    }

    private String detectCharset(byte[] buf, int len) {
        if (len >= 3 && (buf[0] & 0xFF) == 0xEF && (buf[1] & 0xFF) == 0xBB && (buf[2] & 0xFF) == 0xBF)
            return "UTF-8";
        if (len >= 2 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xFE) return "UTF-16LE";
        if (len >= 2 && (buf[0] & 0xFF) == 0xFE && (buf[1] & 0xFF) == 0xFF) return "UTF-16BE";
        return "UTF-8";
    }

    private void updateGutter() {
        String text = body.getText().toString();
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        StringBuilder sb = new StringBuilder(lines * 4);
        for (int i = 1; i <= lines; i++) sb.append(i).append('\n');
        gutter.setText(sb.toString());
    }

    private void promptFind() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_find)
                .setView(input)
                .setPositiveButton(R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String needle = input.getText().toString();
                        if (needle.isEmpty()) return;
                        int from = Math.max(0, body.getSelectionEnd());
                        int at = body.getText().toString().indexOf(needle, from);
                        if (at < 0) at = body.getText().toString().indexOf(needle);
                        if (at < 0) {
                            toast(getString(R.string.no_results));
                            return;
                        }
                        body.requestFocus();
                        body.setSelection(at, at + needle.length());
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void promptReplace() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText from = new EditText(this);
        from.setHint(R.string.editor_find);
        from.setSingleLine(true);
        final EditText to = new EditText(this);
        to.setHint(R.string.editor_replace);
        to.setSingleLine(true);
        box.addView(from);
        box.addView(to);

        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_replace)
                .setView(box)
                .setPositiveButton(R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String needle = from.getText().toString();
                        if (needle.isEmpty()) return;
                        String text = body.getText().toString();
                        int count = 0, idx = 0;
                        while ((idx = text.indexOf(needle, idx)) >= 0) { count++; idx += needle.length(); }
                        body.setText(text.replace(needle, to.getText().toString()));
                        updateGutter();
                        toast(count + " " + getString(R.string.editor_replace));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void promptGotoLine() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_goto_line)
                .setView(input)
                .setPositiveButton(R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        int line;
                        try {
                            line = Integer.parseInt(input.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            return;
                        }
                        String text = body.getText().toString();
                        int pos = 0, current = 1;
                        while (current < line) {
                            int nl = text.indexOf('\n', pos);
                            if (nl < 0) break;
                            pos = nl + 1;
                            current++;
                        }
                        body.requestFocus();
                        body.setSelection(Math.min(pos, text.length()));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void save() {
        if (readOnly || truncated) {
            toast(getString(R.string.editor_readonly));
            return;
        }
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(path, false), "UTF-8");
            w.write(body.getText().toString());
            w.flush();
            toast(getString(R.string.saved));
            status.setText(Fmt.bytes(new File(path).length()) + "   " + getString(R.string.saved));
        } catch (Throwable t) {
            MtxLog.e(this, "editor", "save failed: " + path, t);
            showError(getString(R.string.err_io), String.valueOf(t.getMessage()));
        } finally {
            if (w != null) try { w.close(); } catch (Throwable ignored) {}
        }
    }
}
