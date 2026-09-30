package splusjava;

import java.util.Arrays;
import java.util.List;

import splusjava.tl.TLObject;

final class HighLevelTests {
    private HighLevelTests() {
    }

    static void run() {
        T.section("Peers helpers");
        TLObject self = TLObject.of("user", "id", 5L, "access_hash", 0L, "self", Boolean.TRUE);
        T.eq("inputPeerSelf", Peers.inputPeer(self).getName(), "inputPeer(self user)");

        TLObject other = TLObject.of("user", "id", 7L, "access_hash", 999L);
        TLObject ip = Peers.inputPeer(other);
        T.eq("inputPeerUser", ip.getName(), "inputPeer(user) name");
        T.eq(Long.valueOf(7L), Long.valueOf(ip.getLong("user_id")), "inputPeer(user) id");
        T.eq(Long.valueOf(999L), Long.valueOf(ip.getLong("access_hash")), "inputPeer(user) access_hash");

        TLObject chat = TLObject.of("chat", "id", 42L, "title", "g");
        T.eq("inputPeerChat", Peers.inputPeer(chat).getName(), "inputPeer(chat) name");

        TLObject channel = TLObject.of("channel", "id", 100L, "access_hash", 55L, "title", "c");
        TLObject icp = Peers.inputPeer(channel);
        T.eq("inputPeerChannel", icp.getName(), "inputPeer(channel) name");
        T.eq(Long.valueOf(55L), Long.valueOf(icp.getLong("access_hash")), "inputPeer(channel) access_hash");

        try {
            Peers.inputPeer(TLObject.of("updateShortMessage", "id", 1, "user_id", 1L, "message", "x", "pts", 1, "pts_count", 1, "date", 1));
            T.ok(false, "unrelated object must be rejected");
        } catch (SoroushException e) {
            T.ok(true, "unrelated object rejected");
        }

        List<Object> users = Arrays.<Object>asList(other);
        List<Object> chats = Arrays.<Object>asList(channel);
        TLObject fromPeerUser = TLObject.of("peerUser", "user_id", 7L);
        TLObject resolved = Peers.inputPeerFromLists(fromPeerUser, users, chats);
        T.ok(resolved != null && resolved.getLong("user_id") == 7L, "inputPeerFromLists(peerUser)");
        TLObject fromPeerChannel = TLObject.of("peerChannel", "channel_id", 100L);
        TLObject resolvedC = Peers.inputPeerFromLists(fromPeerChannel, users, chats);
        T.ok(resolvedC != null && resolvedC.getLong("channel_id") == 100L, "inputPeerFromLists(peerChannel)");
        TLObject missing = Peers.inputPeerFromLists(TLObject.of("peerUser", "user_id", 999L), users, chats);
        T.eq(null, missing, "inputPeerFromLists(not found) returns null");

        T.section("Files helpers");
        TLObject doc = TLObject.of("document", "id", 1L, "access_hash", 2L, "date", 0, "mime_type", "x",
                "size", 0L, "dc_id", 1, "attributes", Arrays.asList(), "file_reference", new byte[] {9, 9});
        TLObject loc = Files.locationForDocument(doc);
        T.eq("inputDocumentFileLocation", loc.getName(), "locationForDocument name");
        T.eq(Long.valueOf(1L), Long.valueOf(loc.getLong("id")), "locationForDocument id");
    }
}
