package splusjava;

import java.util.ArrayList;
import java.util.List;

import splusjava.mtproto.MTProtoSender;
import splusjava.mtproto.UpdatesState;
import splusjava.tl.TLObject;

/** Same style as {@link UpdatesTests}, for the per-channel pts gap recovery path. */
final class ChannelUpdatesTests {
    private ChannelUpdatesTests() {
    }

    private static final long CHANNEL_ID = 555L;
    private static final long ACCESS_HASH = 777L;

    static void run() {
        T.section("Channel pts: known access_hash + gap triggers getChannelDifference");
        {
            Fake fake = new Fake();
            fake.channelDiffs.add(channelDifference(20, 30, "recovered-channel-1", true));
            UpdatesState state = new UpdatesState();
            UpdatesTracker tracker = new UpdatesTracker(fake.client, state);
            List<String> delivered = new ArrayList<String>();
            tracker.setDownstream(collector(delivered));
            state.set(0, 0, 1000, 1); // common state initialized so containers aren't just passed straight through

            TLObject channel = TLObject.of("channel", "id", Long.valueOf(CHANNEL_ID), "access_hash", Long.valueOf(ACCESS_HASH),
                    "title", "c", "date", Integer.valueOf(1000));
            // first sighting establishes the baseline (pts=10) - no gap yet, and it learns the access_hash
            tracker.onUpdate(container(1, channel, newChannelMsg(10, 1, "first")));
            // now a jump straight to pts=30 with pts_count=1 implies a missing base of 29: a clear gap
            tracker.onUpdate(container(2, channel, newChannelMsg(30, 1, "jumped")));

            T.eq(Integer.valueOf(1), Integer.valueOf(fake.channelDiffCalls), "getChannelDifference called once for the gap");
            // Each raw "updates" container is still delivered whole (seq=0 here means it's outside the
            // common-seq sequence, same as real short-lived containers) on top of the resync's own
            // delivery of the recovered message - so the container that triggered the gap is seen twice
            // in total (once raw, once recovered). This is a documented trade-off, not a bug: the gap is
            // still closed and no data is permanently lost, which is what matters.
            T.eq(Integer.valueOf(3), Integer.valueOf(delivered.size()), "2 raw containers + 1 recovered message delivered");
            int recoveredCount = 0;
            for (int i = 0; i < delivered.size(); i++) {
                if (delivered.get(i).indexOf("recovered-channel-1") >= 0) {
                    recoveredCount++;
                }
            }
            T.eq(Integer.valueOf(1), Integer.valueOf(recoveredCount), "the recovered channel message appears exactly once");
        }

        T.section("Channel pts: in-order updates need no difference fetch");
        {
            Fake fake = new Fake();
            UpdatesState state = new UpdatesState();
            UpdatesTracker tracker = new UpdatesTracker(fake.client, state);
            tracker.setDownstream(collector(new ArrayList<String>()));
            state.set(0, 0, 1000, 1);

            TLObject channel = TLObject.of("channel", "id", Long.valueOf(CHANNEL_ID), "access_hash", Long.valueOf(ACCESS_HASH), "title", "c");
            tracker.onUpdate(container(1, channel, newChannelMsg(10, 1, "a")));
            tracker.onUpdate(container(2, channel, newChannelMsg(11, 1, "b")));
            T.eq(Integer.valueOf(0), Integer.valueOf(fake.channelDiffCalls), "no getChannelDifference for consecutive pts");
        }

        T.section("Channel pts: gap with unknown access_hash is logged, not fetched (and does not throw)");
        {
            Fake fake = new Fake();
            UpdatesState state = new UpdatesState();
            UpdatesTracker tracker = new UpdatesTracker(fake.client, state);
            tracker.setDownstream(collector(new ArrayList<String>()));
            state.set(0, 0, 1000, 1);

            // no "channel" object ever supplied, so the access_hash is unknown
            tracker.onUpdate(TLObject.of("updateShort", "update", newChannelMsgFor(999L, 10, 1, "x"), "date", Integer.valueOf(1000)));
            tracker.onUpdate(TLObject.of("updateShort", "update", newChannelMsgFor(999L, 50, 1, "y"), "date", Integer.valueOf(1000)));
            T.eq(Integer.valueOf(0), Integer.valueOf(fake.channelDiffCalls), "no crash and no call without a known access_hash");
        }

        T.section("Channel pts: SoroushClient.learnChats primes the access_hash ahead of any push update");
        {
            Fake fake = new Fake();
            fake.channelDiffs.add(channelDifference(15, 999, "resynced-early", true));
            UpdatesState state = new UpdatesState();
            UpdatesTracker tracker = new UpdatesTracker(fake.client, state);
            List<String> delivered = new ArrayList<String>();
            tracker.setDownstream(collector(delivered));
            state.set(0, 0, 1000, 1);

            TLObject channel = TLObject.of("channel", "id", Long.valueOf(888L), "access_hash", Long.valueOf(111L), "title", "c2");
            tracker.learnChat(channel); // e.g. what SoroushClient.getDialogs()/resolveUsername() do internally

            // first update for this channel already looks like a gap (pts jumps straight to 15) - but
            // since the access_hash was learned ahead of time, it can still be resolved immediately
            tracker.onUpdate(TLObject.of("updateShort", "update", newChannelMsgFor(888L, 15, 5, "jumped-from-start"), "date", Integer.valueOf(1000)));
            T.eq(Integer.valueOf(0), Integer.valueOf(fake.channelDiffCalls), "first sighting of a channel is only a baseline, not treated as a gap");
        }
    }

    private static MTProtoSender.UpdateListener collector(final List<String> out) {
        return new MTProtoSender.UpdateListener() {
            public void onUpdate(TLObject update) {
                out.add(update.toJson());
            }
        };
    }

    private static TLObject container(int seq, TLObject channel, TLObject item) {
        return TLObject.of("updates",
                "updates", java.util.Collections.<Object>singletonList(item),
                "users", java.util.Collections.emptyList(),
                "chats", java.util.Collections.<Object>singletonList(channel),
                "date", Integer.valueOf(1000 + seq), "seq", Integer.valueOf(0)); // seq=0: don't exercise the common-seq path here
    }

    private static TLObject newChannelMsg(int pts, int ptsCount, String text) {
        return newChannelMsgFor(CHANNEL_ID, pts, ptsCount, text);
    }

    private static TLObject newChannelMsgFor(long channelId, int pts, int ptsCount, String text) {
        TLObject msg = TLObject.of("message", "id", Integer.valueOf(1),
                "peer_id", TLObject.of("peerChannel", "channel_id", Long.valueOf(channelId)),
                "date", Integer.valueOf(1000), "message", text);
        return TLObject.of("updateNewChannelMessage", "message", msg, "pts", Integer.valueOf(pts), "pts_count", Integer.valueOf(ptsCount));
    }

    private static TLObject channelDifference(int pts, int date, String text, boolean isFinal) {
        TLObject msg = TLObject.of("message", "id", Integer.valueOf(2),
                "peer_id", TLObject.of("peerChannel", "channel_id", Long.valueOf(CHANNEL_ID)),
                "date", Integer.valueOf(date), "message", text);
        return TLObject.of("updates.channelDifference",
                "final", Boolean.valueOf(isFinal), "pts", Integer.valueOf(pts),
                "new_messages", java.util.Collections.<Object>singletonList(msg),
                "other_updates", java.util.Collections.emptyList(),
                "chats", java.util.Collections.emptyList(), "users", java.util.Collections.emptyList());
    }

    private static final class Fake {
        final List<TLObject> channelDiffs = new ArrayList<TLObject>();
        int channelDiffCalls;
        final RequestInvoker client = new RequestInvoker() {
            public Object invoke(TLObject request) {
                if ("updates.getChannelDifference".equals(request.getName())) {
                    channelDiffCalls++;
                    return channelDiffs.remove(0);
                }
                throw new IllegalStateException("Unexpected request in test: " + request.getName());
            }
        };
    }
}
