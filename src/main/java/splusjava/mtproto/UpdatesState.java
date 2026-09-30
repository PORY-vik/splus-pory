package splusjava.mtproto;

/**
 * The four numbers that define "how much of the update stream has been applied": {@code pts} covers
 * ordinary messages/chat updates, {@code qts} covers secret-chat/encrypted updates, {@code seq} covers
 * the update containers themselves, and {@code date} is the server time of the last applied update.
 * See <a href="https://core.telegram.org/api/updates">core.telegram.org/api/updates</a> - Soroush
 * Plus reuses the same scheme.
 * <p>
 * <b>Known simplification:</b> like {@code pts}/{@code qts}/{@code seq} above, each channel/supergroup
 * also has its own independent {@code pts} sequence that would need its own gap tracking
 * (per-channel {@code updates.getChannelDifference}) for full correctness; this class only tracks the
 * single "common" sequence, which covers private chats and small groups.
 */
public final class UpdatesState {
    private int pts;
    private int qts;
    private int date;
    private int seq;
    private volatile boolean initialized;

    public synchronized void set(int pts, int qts, int date, int seq) {
        this.pts = pts;
        this.qts = qts;
        this.date = date;
        this.seq = seq;
        this.initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public synchronized int getPts() {
        return pts;
    }

    public synchronized int getQts() {
        return qts;
    }

    public synchronized int getDate() {
        return date;
    }

    public synchronized int getSeq() {
        return seq;
    }

    public synchronized void setPts(int pts) {
        this.pts = pts;
    }

    public synchronized void setSeq(int seq) {
        this.seq = seq;
    }

    public synchronized void setDate(int date) {
        this.date = date;
    }
}
