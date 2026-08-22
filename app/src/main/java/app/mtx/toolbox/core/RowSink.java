package app.mtx.toolbox.core;

/** Streaming row callback used by search, strings extraction and DEX pools. */
public interface RowSink {
    void onRow(String a, String b, long n1, long n2);
}
