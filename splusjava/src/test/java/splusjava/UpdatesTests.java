package splusjava;

import java.util.ArrayList;
import java.util.List;

import splusjava.mtproto.MTProtoSender;
import splusjava.mtproto.UpdatesState;
import splusjava.tl.TLObject;

/**
 * Exercises {@link UpdatesTracker} against a fake {@link SoroushClient} that answers
 * {@code updates.getState}/{@code updates.getDifference} from an in-memory script instead of the
 * network, so the pts/seq gap-detection decisions can be checked offline (there is no reference
 * Python output to compare against here - SPlusthon's own update-gap handling is not exposed as a
 * pure function - so this checks internal consistency instead: every delivered update is exactly the
 * ones expected, in order, with no duplicates and no drops for a scripted gap).
 */
final class UpdatesTests {
    private UpdatesTests() {
    }

    static void run() {
        T.section("UpdatesTracker: in-order updates need no difference fetch");
        {
            Fake fake = new Fake();
            UpdatesState state = new UpdatesState();
            UpdatesTracker tracker = new UpdatesTracker(fake.client, state);
            List<String> delivered = new ArrayList<String>();
            tracker.setDownstream(collector(delivered));
            state.set(100, 0, 1000, 5);

            tracker.onUpdate(shortMessage(101, 1, "hi"));
            T.eq(Integer.valueOf(101), Integer.valueOf(state.getPts()), "pts advances on in-order update");
            T.eq(Integer.valueOf(1), Integer.valueOf(delivered.size()), "in-order update delivered");
            T.eq(Integer.valueOf(0), Integer.valueOf(fake.getDifferenceCalls), "no getDifference call needed");
        }

        T.section("UpdatesTracker: duplicate pts is dropped, not re-delivered");
        {
            Fake fake = new Fake();
            UpdatesState state = new UpdatesState();
            UpdatesTracker tracker = new UpdatesTracker(fake.client, state);
            List<String> delivered = new ArrayList<String>();
            tracker.setDownstream(collector(delivered));
            state.set(100, 0, 1000, 5);

            tracker.onUpdate(shortMessage(101, 1, "hi"));
            tracker.onUpdate(shortMessage(101, 1, "hi")); // exact duplicate
            tracker.onUpdate(shortMessage(100, 1, "old")); // older than local pts
            T.eq(Integer.valueOf(1), Integer.valueOf(delivered.size()), "duplicates are not delivered twice");
            T.eq(Integer.valueOf(101), Integer.valueOf(state.getPts()), "pts unchanged by duplicates");
        }

        T.section("UpdatesTracker: a pts gap triggers updates.getDifference and delivers the recovered messages");
        {
            Fake fake = new Fake();
            fake.differences.add(differenceSlice(150, 1200, "recovered-1"));
            fake.differences.add(differenceFinal(200, 1300, 9, "recovered-2"));
            UpdatesState state = new UpdatesState();
            UpdatesTracker tracker = new UpdatesTracker(fake.client, state);
            List<String> delivered = new ArrayList<String>();
            tracker.setDownstream(collector(delivered));
            state.set(100, 0, 1000, 5);

            // pts jumps from 100 straight to 250 with pts_count=1 -> implies a base of 249, a clear gap
            tracker.onUpdate(shortMessage(250, 1, "far ahead"));

            T.eq(Integer.valueOf(2), Integer.valueOf(fake.getDifferenceCalls), "getDifference called once per slice");
            T.eq(Integer.valueOf(200), Integer.valueOf(state.getPts()), "pts ends up at the final difference's state.pts");
            T.eq(Integer.valueOf(9), Integer.valueOf(state.getSeq()), "seq updated from the final difference's state");
            // recovered-1 and recovered-2 delivered as updateNewMessage; the original 250 update was
            // superseded by the fetch and is intentionally not separately re-delivered
            T.eq(Integer.valueOf(2), Integer.valueOf(delivered.size()), "both slice's worth of recovered messages delivered");
            T.ok(delivered.get(0).indexOf("recovered-1") >= 0, "first recovered message delivered in order");
            T.ok(delivered.get(1).indexOf("recovered-2") >= 0, "second recovered message delivered in order");
        }

        T.section("UpdatesTracker: seq gap on an 'updates' container also triggers a resync");
        {
            Fake fake = new Fake();
            fake.differences.add(differenceFinal(300, 1400, 20, "resynced"));
            UpdatesState state = new UpdatesState();
            UpdatesTracker tracker = new UpdatesTracker(fake.client, state);
            List<String> delivered = new ArrayList<String>();
            tracker.setDownstream(collector(delivered));
            state.set(100, 0, 1000, 5);

            TLObject container = TLObject.of("updates", "updates", java.util.Collections.emptyList(),
                    "users", java.util.Collections.emptyList(), "chats", java.util.Collections.emptyList(),
                    "date", Integer.valueOf(1050), "seq", Integer.valueOf(50)); // seq jumps from 5 to 50: a gap
            tracker.onUpdate(container);

            T.eq(Integer.valueOf(1), Integer.valueOf(fake.getDifferenceCalls), "seq gap triggers exactly one getDifference");
            T.eq(Integer.valueOf(20), Integer.valueOf(state.getSeq()), "seq resynced from the difference");
        }

        T.section("UpdatesTracker: uninitialized state seeds instead of guessing");
        {
            Fake fake = new Fake();
            fake.stateAnswer = TLObject.of("updates.state", "pts", Integer.valueOf(42), "qts", Integer.valueOf(0),
                    "date", Integer.valueOf(1234), "seq", Integer.valueOf(7), "unread_count", Integer.valueOf(0));
            UpdatesState state = new UpdatesState();
            UpdatesTracker tracker = new UpdatesTracker(fake.client, state);
            List<String> delivered = new ArrayList<String>();
            tracker.setDownstream(collector(delivered));

            T.ok(!state.isInitialized(), "state starts uninitialized");
            tracker.onUpdate(shortMessage(999, 1, "first ever update")); // no baseline yet: deliver, don't fetch
            T.eq(Integer.valueOf(1), Integer.valueOf(delivered.size()), "delivered even though state was not seeded yet");
            T.ok(!state.isInitialized(), "onUpdate alone does not seed the state");

            tracker.seed();
            T.ok(state.isInitialized(), "seed() initializes the state");
            T.eq(Integer.valueOf(42), Integer.valueOf(state.getPts()), "seed() reads pts from updates.getState");
        }
    }

    private static MTProtoSender.UpdateListener collector(final List<String> out) {
        return new MTProtoSender.UpdateListener() {
            public void onUpdate(TLObject update) {
                out.add(update.toJson());
            }
        };
    }

    private static TLObject shortMessage(int pts, int ptsCount, String text) {
        return TLObject.of("updateShortMessage", "id", Integer.valueOf(1), "user_id", Long.valueOf(1),
                "message", text, "pts", Integer.valueOf(pts), "pts_count", Integer.valueOf(ptsCount), "date", Integer.valueOf(1000));
    }

    private static TLObject differenceSlice(int pts, int date, String text) {
        TLObject msg = TLObject.of("message", "id", Integer.valueOf(1), "peer_id", TLObject.of("peerUser", "user_id", Long.valueOf(1)),
                "date", Integer.valueOf(date), "message", text);
        return TLObject.of("updates.differenceSlice",
                "new_messages", java.util.Collections.<Object>singletonList(msg),
                "new_encrypted_messages", java.util.Collections.emptyList(),
                "other_updates", java.util.Collections.emptyList(),
                "chats", java.util.Collections.emptyList(), "users", java.util.Collections.emptyList(),
                "intermediate_state", TLObject.of("updates.state", "pts", Integer.valueOf(pts), "qts", Integer.valueOf(0),
                        "date", Integer.valueOf(date), "seq", Integer.valueOf(6), "unread_count", Integer.valueOf(0)));
    }

    private static TLObject differenceFinal(int pts, int date, int seq, String text) {
        TLObject msg = TLObject.of("message", "id", Integer.valueOf(2), "peer_id", TLObject.of("peerUser", "user_id", Long.valueOf(1)),
                "date", Integer.valueOf(date), "message", text);
        return TLObject.of("updates.difference",
                "new_messages", java.util.Collections.<Object>singletonList(msg),
                "new_encrypted_messages", java.util.Collections.emptyList(),
                "other_updates", java.util.Collections.emptyList(),
                "chats", java.util.Collections.emptyList(), "users", java.util.Collections.emptyList(),
                "state", TLObject.of("updates.state", "pts", Integer.valueOf(pts), "qts", Integer.valueOf(0),
                        "date", Integer.valueOf(date), "seq", Integer.valueOf(seq), "unread_count", Integer.valueOf(0)));
    }

    /** Minimal stand-in for SoroushClient.invoke(...) that scripts updates.getState/getDifference answers. */
    private static final class Fake {
        final List<TLObject> differences = new ArrayList<TLObject>();
        TLObject stateAnswer;
        int getDifferenceCalls;
        final RequestInvoker client = new RequestInvoker() {
            public Object invoke(TLObject request) {
                if ("updates.getState".equals(request.getName())) {
                    return stateAnswer;
                }
                if ("updates.getDifference".equals(request.getName())) {
                    getDifferenceCalls++;
                    return differences.remove(0);
                }
                throw new IllegalStateException("Unexpected request in test: " + request.getName());
            }
        };
    }
}
