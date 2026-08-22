package app.mtx.toolbox.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.OpResult;

import java.io.File;

/**
 * A real hex editor, not a viewer: pages are read at arbitrary offsets, bytes are
 * written in place, and search runs in the native engine so multi-GB files work.
 */
public class HexActivity extends BaseActivity {

    private static final int PAGE = 1024;      // 64 rows of 16 bytes

    private String path;
    private long offset;
    private long fileSize;
    private boolean writable;

    private TextView gutter;
    private EditText body;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);
        path = getIntent().getStringExtra(ToolRouter.EXTRA_PATH);
        if (path == null) { finish(); return; }

        File f = new File(path);
        fileSize = f.length();
        writable = f.canWrite();

        ((TextView) findViewById(R.id.title)).setText(f.getName());
        gutter = findViewById(R.id.gutter);
        body = findViewById(R.id.editor);
        status = findViewById(R.id.status);
        body.setKeyListener(null);              // the hex view itself is not free-text editable
        body.setTextIsSelectable(true);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        LinearLayout actions = findViewById(R.id.toolbar_actions);
        addAction(actions, "<", new Runnable() {
            @Override
            public void run() { seek(offset - PAGE); }
        });
        addAction(actions, ">", new Runnable() {
            @Override
            public void run() { seek(offset + PAGE); }
        });
        addAction(actions, "goto", new Runnable() {
            @Override
            public void run() { promptGoto(); }
        });
        addAction(actions, "find", new Runnable() {
            @Override
            public void run() { promptFind(); }
        });
        if (writable) {
            addAction(actions, "edit", new Runnable() {
                @Override
                public void run() { promptEdit(); }
            });
        }

        seek(0);
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

    private void seek(long newOffset) {
        if (newOffset < 0) newOffset = 0;
        if (fileSize > 0 && newOffset >= fileSize) newOffset = Math.max(0, fileSize - PAGE);
        offset = newOffset;
        final long target = offset;
        final Handler main = new Handler(Looper.getMainLooper());

        new Thread(new Runnable() {
            @Override
            public void run() {
                final byte[] data = Native.isAvailable() ? Native.hexRead(path, target, PAGE) : null;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (data == null) {
                            status.setText(OpResult.safeLastError());
                            return;
                        }
                        renderPage(target, data);
                    }
                });
            }
        }, "mtx-hex").start();
    }

    private void renderPage(long base, byte[] data) {
        StringBuilder hex = new StringBuilder();
        StringBuilder off = new StringBuilder();

        for (int row = 0; row * 16 < data.length; row++) {
            int start = row * 16;
            off.append(String.format("%08X", base + start)).append('\n');
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (start + i < data.length) {
                    int b = data[start + i] & 0xFF;
                    hex.append(String.format("%02X ", b));
                    ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                } else {
                    hex.append("   ");
                    ascii.append(' ');
                }
                if (i == 7) hex.append(' ');
            }
            hex.append(" |").append(ascii).append("|").append('\n');
        }

        gutter.setText(off.toString());
        body.setText(hex.toString());
        status.setText("offset 0x" + Long.toHexString(base)
                + "   " + base + " / " + fileSize
                + "   " + (writable ? "rw" : getString(R.string.editor_readonly)));
    }

    private void promptGoto() {
        final EditText input = new EditText(this);
        input.setHint("0x1000 or 4096");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.hex_goto)
                .setView(input)
                .setPositiveButton(R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Long value = parseOffset(input.getText().toString().trim());
                        if (value == null) toast(getString(R.string.err_range));
                        else seek(value);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private Long parseOffset(String text) {
        try {
            if (text.startsWith("0x") || text.startsWith("0X"))
                return Long.parseLong(text.substring(2), 16);
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void promptFind() {
        final EditText input = new EditText(this);
        input.setHint("text, or hex bytes: 50 4B 03 04");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.hex_find)
                .setView(input)
                .setPositiveButton(R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        find(input.getText().toString());
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void find(String query) {
        final byte[] pattern = toBytes(query);
        if (pattern == null || pattern.length == 0) {
            toast(getString(R.string.err_range));
            return;
        }
        final long from = offset + 1;
        final Handler main = new Handler(Looper.getMainLooper());
        status.setText(getString(R.string.op_searching));

        new Thread(new Runnable() {
            @Override
            public void run() {
                long job = Native.newJob();
                final long match;
                try {
                    match = Native.hexFind(job, path, from, pattern, false);
                } finally {
                    Native.releaseJob(job);
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (match < 0) {
                            status.setText(getString(R.string.no_results));
                            return;
                        }
                        seek(match - (match % 16));
                    }
                });
            }
        }, "mtx-hexfind").start();
    }

    /** Space separated hex is read as bytes; anything else is treated as UTF-8 text. */
    private byte[] toBytes(String query) {
        String trimmed = query.trim();
        if (trimmed.isEmpty()) return null;
        String compact = trimmed.replace(" ", "");
        boolean looksHex = compact.length() % 2 == 0 && compact.matches("[0-9a-fA-F]+")
                && trimmed.contains(" ");
        if (looksHex) {
            byte[] out = new byte[compact.length() / 2];
            for (int i = 0; i < out.length; i++)
                out[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
            return out;
        }
        try {
            return trimmed.getBytes("UTF-8");
        } catch (Exception e) {
            return trimmed.getBytes();
        }
    }

    private void promptEdit() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText offsetInput = new EditText(this);
        offsetInput.setHint("offset (0x... or decimal)");
        offsetInput.setSingleLine(true);
        offsetInput.setText("0x" + Long.toHexString(offset));
        final EditText bytesInput = new EditText(this);
        bytesInput.setHint("bytes: 00 FF 4A");
        bytesInput.setSingleLine(true);
        bytesInput.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(offsetInput);
        box.addView(bytesInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.hex_edit)
                .setView(box)
                .setPositiveButton(R.string.save, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Long at = parseOffset(offsetInput.getText().toString().trim());
                        String compact = bytesInput.getText().toString().replace(" ", "");
                        if (at == null || compact.isEmpty() || compact.length() % 2 != 0
                                || !compact.matches("[0-9a-fA-F]+")) {
                            toast(getString(R.string.err_range));
                            return;
                        }
                        byte[] data = new byte[compact.length() / 2];
                        for (int i = 0; i < data.length; i++)
                            data[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
                        int code = Native.hexWrite(path, at, data);
                        if (OpResult.isOk(code)) {
                            toast(getString(R.string.saved));
                            seek(offset);
                        } else {
                            showError(OpResult.message(HexActivity.this, code),
                                    OpResult.detailed(HexActivity.this, code));
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
