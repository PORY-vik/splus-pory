package splusjava;

import splusjava.tl.TLObject;

/**
 * Turns the {@code User}/{@code Chat}/{@code Channel} objects the server gives you (from
 * {@code contacts.resolveUsername}, {@code messages.getDialogs}, an update, ...) into the
 * {@code InputPeer}/{@code InputUser}/{@code InputChannel} objects most API calls expect. Soroush -
 * like Telegram - requires the {@code access_hash} that came with an object the server sent you; you
 * cannot construct a valid peer from just an id.
 */
public final class Peers {
    private Peers() {
    }

    /** {@code inputPeerSelf}, {@code inputPeerUser}, {@code inputPeerChat} or {@code inputPeerChannel}. */
    public static TLObject inputPeer(TLObject userChatOrChannel) {
        String name = userChatOrChannel.getName();
        if ("user".equals(name)) {
            if (userChatOrChannel.getBool("self")) {
                return TLObject.of("inputPeerSelf");
            }
            return TLObject.of("inputPeerUser", "user_id", Long.valueOf(userChatOrChannel.getLong("id")),
                    "access_hash", Long.valueOf(userChatOrChannel.getLong("access_hash")));
        }
        if ("chat".equals(name) || "chatForbidden".equals(name)) {
            return TLObject.of("inputPeerChat", "chat_id", Long.valueOf(userChatOrChannel.getLong("id")));
        }
        if ("channel".equals(name) || "channelForbidden".equals(name)) {
            return TLObject.of("inputPeerChannel", "channel_id", Long.valueOf(userChatOrChannel.getLong("id")),
                    "access_hash", Long.valueOf(userChatOrChannel.getLong("access_hash")));
        }
        throw new SoroushException("Cannot build an InputPeer from a '" + name + "'");
    }

    public static TLObject inputUser(TLObject user) {
        if (user.getBool("self")) {
            return TLObject.of("inputUserSelf");
        }
        return TLObject.of("inputUser", "user_id", Long.valueOf(user.getLong("id")),
                "access_hash", Long.valueOf(user.getLong("access_hash")));
    }

    public static TLObject inputChannel(TLObject channel) {
        return TLObject.of("inputChannel", "channel_id", Long.valueOf(channel.getLong("id")),
                "access_hash", Long.valueOf(channel.getLong("access_hash")));
    }

    /**
     * Finds the {@code User}/{@code Chat}/{@code Channel} matching {@code peer} (a {@code Peer}: e.g.
     * a message's {@code from_id}/{@code peer_id}) inside the {@code users}/{@code chats} lists a
     * response bundled alongside it, and returns its {@code InputPeer}. Returns null if not found
     * (the id was not in either list).
     */
    public static TLObject inputPeerFromLists(TLObject peer, java.util.List<Object> users, java.util.List<Object> chats) {
        String name = peer.getName();
        if ("peerUser".equals(name)) {
            TLObject u = findById(users, peer.getLong("user_id"));
            return u == null ? null : inputPeer(u);
        }
        if ("peerChat".equals(name)) {
            return TLObject.of("inputPeerChat", "chat_id", Long.valueOf(peer.getLong("chat_id")));
        }
        if ("peerChannel".equals(name)) {
            TLObject c = findById(chats, peer.getLong("channel_id"));
            return c == null ? null : inputPeer(c);
        }
        return null;
    }

    private static TLObject findById(java.util.List<Object> list, long id) {
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o instanceof TLObject && ((TLObject) o).getLong("id") == id) {
                return (TLObject) o;
            }
        }
        return null;
    }
}
