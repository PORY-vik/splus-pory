package splusjava;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import splusjava.mtproto.MTProtoSender;
import splusjava.mtproto.UpdatesState;
import splusjava.tl.TLObject;
import splusjava.util.Log;

/**
 * Sits between {@link MTProtoSender} and the application's {@link MTProtoSender.UpdateListener}: keeps
 * {@link UpdatesState} (the common pts/qts/date/seq) up to date and calls {@code updates.getDifference}
 * to fill a gap whenever an incoming update implies one was missed (for example after a reconnect),
 * instead of silently losing messages. It does the same for each channel/supergroup's own, independent
 * pts sequence via {@code updates.getChannelDifference} (see {@link #handleChannelItem}).
 * <p>
 * Channel gap recovery needs the channel's {@code access_hash}, which is only known once a
 * {@code Channel} object carrying it has been seen - either bundled with an update (the common case:
 * Soroush/Telegram servers attach the relevant chats to updates about them) or learned from a
 * {@code updates.[channel]difference} response. If a gap is detected for a channel whose access_hash
 * is not yet known, it is logged and left unresolved until the channel is seen; this is the one
 * remaining known gap (see the README).
 */
final class UpdatesTracker implements MTProtoSender.UpdateListener {
    private static final int CHANNEL_DIFFERENCE_LIMIT = 100;
    private static final int MAX_SLICES = 50;

    private final RequestInvoker client;
    private final UpdatesState state;
    private volatile MTProtoSender.UpdateListener downstream;
    private final Object gapLock = new Object();

    private final Object channelLock = new Object();
    private final Map<Long, Integer> channelPts = new HashMap<Long, Integer>();
    private final Map<Long, Long> channelAccessHash = new HashMap<Long, Long>();

    UpdatesTracker(RequestInvoker client, UpdatesState state) {
        this.client = client;
        this.state = state;
    }

    void setDownstream(MTProtoSender.UpdateListener listener) {
        this.downstream = listener;
    }

    /** Fetches the current server state; call once after connecting, before relying on gap detection. */
    void seed() {
        try {
            TLObject s = (TLObject) client.invoke(TLObject.of("updates.getState"));
            state.set(s.getInt("pts"), s.getInt("qts"), s.getInt("date"), s.getInt("seq"));
        } catch (RuntimeException e) {
            Log.w("Could not seed the updates state", e);
        }
    }

    @Override
    public void onUpdate(TLObject update) {
        String name = update.getName();
        if ("updatesTooLong".equals(name)) {
            fetchDifference();
            return;
        }
        if ("updateShortMessage".equals(name) || "updateShortChatMessage".equals(name) || "updateShortSentMessage".equals(name)) {
            handlePts(update.getInt("pts"), update.getInt("pts_count"), update);
            return;
        }
        if ("updates".equals(name) || "updatesCombined".equals(name)) {
            learnChats(update.getList("chats"));
            List<Object> items = update.getList("updates");
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof TLObject) {
                    handleChannelItem((TLObject) items.get(i));
                }
            }
            int seqStart = "updatesCombined".equals(name) ? update.getInt("seq_start") : update.getInt("seq");
            handleSeq(seqStart, update.getInt("seq"), update);
            return;
        }
        if ("updateShort".equals(name)) {
            TLObject inner = update.getObject("update");
            if (inner != null) {
                handleChannelItem(inner);
            }
            deliver(update);
            return;
        }
        // Anything else (typing/status notices, etc.): no ordering to check, deliver as-is.
        deliver(update);
    }

    private void handlePts(int pts, int ptsCount, TLObject update) {
        synchronized (gapLock) {
            if (!state.isInitialized()) {
                deliver(update);
                return;
            }
            int localPts = state.getPts();
            int expectedBase = pts - ptsCount;
            if (expectedBase > localPts) {
                Log.d("pts gap detected (local=" + localPts + ", update requires base=" + expectedBase + "); fetching difference");
                fetchDifferenceLocked();
                return;
            }
            if (pts <= localPts) {
                return; // already applied (duplicate/out-of-order retransmit)
            }
            state.setPts(pts);
        }
        deliver(update);
    }

    private void handleSeq(int seqStart, int seq, TLObject update) {
        synchronized (gapLock) {
            if (!state.isInitialized()) {
                deliver(update);
                return;
            }
            int localSeq = state.getSeq();
            if (seqStart > localSeq + 1) {
                Log.d("seq gap detected (local=" + localSeq + ", update seq_start=" + seqStart + "); fetching difference");
                fetchDifferenceLocked();
                return;
            }
            if (seq != 0 && seq <= localSeq) {
                return; // duplicate
            }
            if (seq != 0) {
                state.setSeq(seq);
            }
            int date = update.getInt("date");
            if (date != 0) {
                state.setDate(date);
            }
        }
        deliver(update);
    }

    /** Forces a resync now (also useful right after a reconnect, in case updates were missed while down). */
    void fetchDifference() {
        synchronized (gapLock) {
            fetchDifferenceLocked();
        }
    }

    private void fetchDifferenceLocked() {
        if (!state.isInitialized()) {
            seed();
            return;
        }
        int guard = 0;
        while (guard++ < MAX_SLICES) { // a slice loop that never converges would otherwise spin forever
            TLObject diff;
            try {
                diff = (TLObject) client.invoke(TLObject.of("updates.getDifference",
                        "pts", Integer.valueOf(state.getPts()), "date", Integer.valueOf(state.getDate()),
                        "qts", Integer.valueOf(state.getQts())));
            } catch (RuntimeException e) {
                Log.w("updates.getDifference failed; will retry on the next gap/reconnect", e);
                return;
            }
            String name = diff.getName();
            if ("updates.differenceEmpty".equals(name)) {
                state.setDate(diff.getInt("date"));
                state.setSeq(diff.getInt("seq"));
                return;
            }
            if ("updates.differenceTooLong".equals(name)) {
                // Too far behind to replay incrementally; only option without full local-cache
                // invalidation logic is to jump the pointer forward and accept the gap.
                Log.w("updates.differenceTooLong: skipping ahead to pts=" + diff.getInt("pts") + ", some updates were lost");
                state.setPts(diff.getInt("pts"));
                return;
            }
            learnChats(diff.getList("chats"));
            deliverDifference(diff);
            boolean isSlice = "updates.differenceSlice".equals(name);
            TLObject newState = diff.getObject(isSlice ? "intermediate_state" : "state");
            if (newState != null) {
                state.set(newState.getInt("pts"), newState.getInt("qts"), newState.getInt("date"), newState.getInt("seq"));
            }
            if (!isSlice) {
                return;
            }
            // updates.differenceSlice: more to fetch, loop with the updated pts/qts/date
        }
        Log.w("updates.getDifference did not converge after " + MAX_SLICES + " slices; stopping to avoid an infinite loop");
    }

    /** Re-delivers the messages/updates bundled in an updates.Difference, individually, like a live update. */
    private void deliverDifference(TLObject diff) {
        List<Object> newMessages = diff.getList("new_messages");
        for (int i = 0; i < newMessages.size(); i++) {
            Object m = newMessages.get(i);
            if (m instanceof TLObject) {
                deliver(TLObject.of("updateNewMessage", "message", m, "pts", Integer.valueOf(0), "pts_count", Integer.valueOf(0)));
            }
        }
        List<Object> other = diff.getList("other_updates");
        for (int i = 0; i < other.size(); i++) {
            Object u = other.get(i);
            if (u instanceof TLObject) {
                deliver((TLObject) u);
            }
        }
    }

    // ------------------------------------------------------------------ per-channel pts

    /** Remembers each channel's access_hash whenever we see one; needed to call getChannelDifference. */
    private void learnChats(List<Object> chats) {
        for (int i = 0; i < chats.size(); i++) {
            Object c = chats.get(i);
            if (c instanceof TLObject) {
                learnChat((TLObject) c);
            }
        }
    }

    /** Same as {@link #learnChats}, for a single chat (also usable directly, e.g. from getDialogs results). */
    void learnChat(TLObject chat) {
        if ("channel".equals(chat.getName()) && chat.has("access_hash")) {
            synchronized (channelLock) {
                channelAccessHash.put(Long.valueOf(chat.getLong("id")), Long.valueOf(chat.getLong("access_hash")));
            }
        }
    }

    /** Checks one Update item for a channel-specific pts and reacts to a gap; ignores everything else. */
    private void handleChannelItem(TLObject item) {
        String name = item.getName();
        long channelId;
        int pts;
        int ptsCount;
        if ("updateNewChannelMessage".equals(name) || "updateEditChannelMessage".equals(name)) {
            TLObject message = item.getObject("message");
            TLObject peer = message == null ? null : message.getObject("peer_id");
            if (peer == null || !"peerChannel".equals(peer.getName())) {
                return;
            }
            channelId = peer.getLong("channel_id");
            pts = item.getInt("pts");
            ptsCount = item.getInt("pts_count");
        } else if ("updateDeleteChannelMessages".equals(name) || "updatePinnedChannelMessages".equals(name)) {
            channelId = item.getLong("channel_id");
            pts = item.getInt("pts");
            ptsCount = item.getInt("pts_count");
        } else if ("updateChannelTooLong".equals(name)) {
            fetchChannelDifference(item.getLong("channel_id"));
            return;
        } else {
            return; // not a channel-pts-bearing update (e.g. updateChannelMessageViews, updateChannel)
        }

        synchronized (channelLock) {
            Long key = Long.valueOf(channelId);
            Integer localPtsBoxed = channelPts.get(key);
            if (localPtsBoxed == null) {
                channelPts.put(key, Integer.valueOf(pts)); // first sighting for this channel: just record the baseline
                return;
            }
            int localPts = localPtsBoxed.intValue();
            int expectedBase = pts - ptsCount;
            if (expectedBase > localPts) {
                Log.d("channel " + channelId + " pts gap detected (local=" + localPts + ", requires base=" + expectedBase + ")");
                fetchChannelDifferenceLocked(channelId);
                return;
            }
            if (pts > localPts) {
                channelPts.put(key, Integer.valueOf(pts));
            }
            // pts <= localPts: duplicate/out-of-order retransmit of a channel update, nothing to do -
            // the item itself was already delivered as part of its container by the caller.
        }
    }

    void fetchChannelDifference(long channelId) {
        synchronized (channelLock) {
            fetchChannelDifferenceLocked(channelId);
        }
    }

    private void fetchChannelDifferenceLocked(long channelId) {
        Long accessHash = channelAccessHash.get(Long.valueOf(channelId));
        if (accessHash == null) {
            Log.w("pts gap on channel " + channelId + " but its access_hash is not known yet; cannot resync until "
                    + "the channel is seen again (e.g. via getDialogs/resolveUsername)");
            return;
        }
        Integer localPtsBoxed = channelPts.get(Long.valueOf(channelId));
        int pts = localPtsBoxed != null ? localPtsBoxed.intValue() : 1;
        TLObject inputChannel = TLObject.of("inputChannel", "channel_id", Long.valueOf(channelId), "access_hash", accessHash);

        int guard = 0;
        while (guard++ < MAX_SLICES) {
            TLObject diff;
            try {
                diff = (TLObject) client.invoke(TLObject.of("updates.getChannelDifference",
                        "channel", inputChannel, "filter", TLObject.of("channelMessagesFilterEmpty"),
                        "pts", Integer.valueOf(pts), "limit", Integer.valueOf(CHANNEL_DIFFERENCE_LIMIT)));
            } catch (RuntimeException e) {
                Log.w("updates.getChannelDifference failed for channel " + channelId, e);
                return;
            }
            String name = diff.getName();
            if ("updates.channelDifferenceEmpty".equals(name)) {
                channelPts.put(Long.valueOf(channelId), Integer.valueOf(diff.getInt("pts")));
                return;
            }
            if ("updates.channelDifferenceTooLong".equals(name)) {
                learnChats(diff.getList("chats"));
                TLObject dialog = diff.getObject("dialog");
                if (dialog != null && dialog.has("pts")) {
                    channelPts.put(Long.valueOf(channelId), Integer.valueOf(dialog.getInt("pts")));
                }
                Log.w("updates.channelDifferenceTooLong for channel " + channelId + "; some channel messages were lost");
                return;
            }
            // updates.channelDifference
            learnChats(diff.getList("chats"));
            List<Object> newMessages = diff.getList("new_messages");
            for (int i = 0; i < newMessages.size(); i++) {
                Object m = newMessages.get(i);
                if (m instanceof TLObject) {
                    deliver(TLObject.of("updateNewChannelMessage", "message", m, "pts", Integer.valueOf(0), "pts_count", Integer.valueOf(0)));
                }
            }
            List<Object> other = diff.getList("other_updates");
            for (int i = 0; i < other.size(); i++) {
                Object u = other.get(i);
                if (u instanceof TLObject) {
                    deliver((TLObject) u);
                }
            }
            pts = diff.getInt("pts");
            channelPts.put(Long.valueOf(channelId), Integer.valueOf(pts));
            if (diff.getBool("final")) {
                return;
            }
            // not final: more to fetch, loop with the updated pts
        }
        Log.w("updates.getChannelDifference did not converge after " + MAX_SLICES + " iterations for channel " + channelId);
    }

    private void deliver(TLObject update) {
        MTProtoSender.UpdateListener l = downstream;
        if (l != null) {
            try {
                l.onUpdate(update);
            } catch (RuntimeException e) {
                Log.w("Update listener threw", e);
            }
        }
    }
}
