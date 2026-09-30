package splusjava;

import java.util.List;

import splusjava.mtproto.AuthKey;
import splusjava.mtproto.Authenticator;
import splusjava.mtproto.MTProtoSender;
import splusjava.mtproto.MTProtoState;
import splusjava.net.SoroushConnection;
import splusjava.tl.TLObject;
import splusjava.tl.TLSchema;
import splusjava.util.Log;

/**
 * Entry point of the library. Connects to Soroush Plus, performs the key exchange the first time
 * (reusing it afterwards through {@link Session}), and lets you call any API method by name through
 * {@link #invoke(TLObject)} - there is one Java class for the whole API surface rather than one per
 * TL method, since the schema ({@value TLSchema#LAYER}) is interpreted at runtime (see {@link TLObject}).
 * <pre>
 *   SoroushClient client = new SoroushClient(new Session());
 *   client.connect();
 *   client.invoke(TLObject.of("auth.sendCode", ...));
 * </pre>
 * All methods block the calling thread; call them from a background thread on Android.
 */
public final class SoroushClient implements RequestInvoker {
    /** Device info sent with every connection; override the fields you care about before {@link #connect()}. */
    public static final class DeviceInfo {
        public String deviceModel = "Java";
        public String systemVersion = System.getProperty("java.vm.name", "unknown");
        public String appVersion = "1.0";
        public String langCode = "fa";
        public String systemLangCode = "fa";
    }

    private static final int API_ID = 1030400;
    private static final String API_HASH = "6edb16cf88714a4e9a805e928c39c937";
    private static final long RECONNECT_INITIAL_DELAY_MS = 1000;
    private static final long RECONNECT_MAX_DELAY_MS = 30000;

    private final Session session;
    private final DeviceInfo device = new DeviceInfo();
    private final splusjava.mtproto.UpdatesState updatesState = new splusjava.mtproto.UpdatesState();
    private final UpdatesTracker tracker = new UpdatesTracker(this, updatesState);
    private MTProtoSender sender;
    private volatile boolean autoReconnect = true;
    private volatile boolean userDisconnected = true;
    private volatile MTProtoSender.DisconnectListener appDisconnectListener;
    private Thread reconnectThread;

    public SoroushClient(Session session) {
        this.session = session;
    }

    public Session getSession() {
        return session;
    }

    public DeviceInfo getDeviceInfo() {
        return device;
    }

    /** Where the library tracks pts/qts/date/seq; mainly for diagnostics ({@code getPts()} etc). */
    public splusjava.mtproto.UpdatesState getUpdatesState() {
        return updatesState;
    }

    /** Forces an immediate resync with the server's update stream (also done automatically after a reconnect). */
    public void syncUpdates() {
        tracker.fetchDifference();
    }

    /**
     * Whether to reconnect automatically (reusing the existing auth key - no new login) after an
     * unexpected disconnect, with exponential backoff. Default: {@code true}. Once reconnected,
     * {@link #syncUpdates()} runs automatically so no updates are silently lost.
     */
    public void setAutoReconnect(boolean enabled) {
        this.autoReconnect = enabled;
    }

    public void setUpdateListener(MTProtoSender.UpdateListener listener) {
        tracker.setDownstream(listener);
    }

    /** Notified on every disconnect (including ones {@link #setAutoReconnect} will retry). */
    public void setDisconnectListener(MTProtoSender.DisconnectListener listener) {
        appDisconnectListener = listener;
    }

    public boolean isConnected() {
        return sender != null && sender.isRunning();
    }

    /** Connects, performing the key exchange only if the session has no auth key yet. */
    public synchronized void connect() {
        userDisconnected = false;
        if (isConnected()) {
            return;
        }
        SoroushConnection connection = new SoroushConnection(session.getHost(), session.getPort());
        AuthKey authKey;
        int timeOffset = 0;
        if (session.getAuthKey() != null) {
            authKey = new AuthKey(session.getAuthKey());
            connection.connect();
        } else {
            connection.connect();
            Authenticator.Result r = Authenticator.authenticate(new PlainSenderImpl(connection));
            authKey = r.authKey;
            timeOffset = r.timeOffset;
            session.setAuthKey(authKey.getKey());
        }
        sender = new MTProtoSender(connection, authKey);
        sender.getState().setTimeOffset(timeOffset);
        sender.setUpdateListener(tracker);
        sender.setDisconnectListener(internalDisconnectListener);
        sender.start();
        initConnection();
        if (updatesState.isInitialized()) {
            tracker.fetchDifference(); // reconnect: catch up on whatever happened while we were down
        } else {
            tracker.seed(); // first connection ever for this session: just record the current state
        }
    }

    public synchronized void disconnect() {
        userDisconnected = true;
        if (reconnectThread != null) {
            reconnectThread.interrupt();
        }
        if (sender != null) {
            sender.stop();
        }
    }

    private final MTProtoSender.DisconnectListener internalDisconnectListener = new MTProtoSender.DisconnectListener() {
        public void onDisconnected(Throwable error) {
            MTProtoSender.DisconnectListener l = appDisconnectListener;
            if (l != null) {
                l.onDisconnected(error);
            }
            if (error != null && autoReconnect && !userDisconnected) {
                startReconnectThread();
            }
        }
    };

    private synchronized void startReconnectThread() {
        if (reconnectThread != null && reconnectThread.isAlive()) {
            return;
        }
        reconnectThread = new Thread(new Runnable() {
            public void run() {
                long delay = RECONNECT_INITIAL_DELAY_MS;
                while (!userDisconnected) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (userDisconnected) {
                        return;
                    }
                    try {
                        connect();
                        Log.i("Reconnected");
                        return;
                    } catch (RuntimeException e) {
                        Log.w("Reconnect attempt failed, retrying in " + (delay * 2) + "ms", e);
                        delay = Math.min(delay * 2, RECONNECT_MAX_DELAY_MS);
                    }
                }
            }
        }, "splusjava-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private void initConnection() {
        TLObject init = TLObject.of("initConnection",
                "api_id", Integer.valueOf(API_ID),
                "device_model", device.deviceModel,
                "system_version", device.systemVersion,
                "app_version", device.appVersion,
                "lang_code", device.langCode,
                "system_lang_code", device.systemLangCode,
                "lang_pack", "",
                "query", TLObject.of("help.getConfig"));
        TLObject wrapped = TLObject.of("invokeWithLayer", "layer", Integer.valueOf(TLSchema.LAYER), "query", init);
        try {
            sender.invoke(wrapped);
        } catch (RuntimeException e) {
            Log.w("initConnection failed (continuing; the server may still accept plain requests)", e);
        }
    }

    /** Sends any API request and returns its result (a {@link TLObject}, a List, or a boxed primitive). */
    public Object invoke(TLObject request) {
        requireConnected();
        return sender.invoke(request);
    }

    public Object invoke(TLObject request, long timeoutMs) {
        requireConnected();
        return sender.invoke(request, timeoutMs);
    }

    private void requireConnected() {
        if (sender == null || !sender.isRunning()) {
            throw new TransportException("Not connected: call connect() first");
        }
    }

    // ------------------------------------------------------------------ high level convenience API
    //
    // Everything below is a thin wrapper over invoke(TLObject) for the calls people reach for most
    // often. None of it is required - build any request by hand with TLObject.of(...) for anything
    // not covered here.

    /** Starts login with a phone number; returns the {@code phone_code_hash} needed by {@link #signIn}. */
    public String sendCode(String phoneNumber) {
        TLObject sent = (TLObject) invoke(TLObject.of("auth.sendCode",
                "phone_number", phoneNumber, "api_id", Integer.valueOf(API_ID), "api_hash", API_HASH,
                "settings", TLObject.of("codeSettings")));
        return sent.getString("phone_code_hash");
    }

    /**
     * Completes login with the code the user received. If the account has two-step verification
     * enabled this throws {@link RpcException} with {@code is("SESSION_PASSWORD_NEEDED")} true; call
     * {@link #checkPassword(String)} next in that case.
     */
    public TLObject signIn(String phoneNumber, String phoneCodeHash, String phoneCode) {
        return (TLObject) invoke(TLObject.of("auth.signIn",
                "phone_number", phoneNumber, "phone_code_hash", phoneCodeHash, "phone_code", phoneCode));
    }

    /** Finishes login for an account with two-step verification (SRP), after {@link #signIn} asked for it. */
    public TLObject checkPassword(String password) {
        TLObject accountPassword = (TLObject) invoke(TLObject.of("account.getPassword"));
        TLObject proof = splusjava.crypto.SrpPassword.computeCheck(accountPassword, password);
        return (TLObject) invoke(TLObject.of("auth.checkPassword", "password", proof));
    }

    /** Looks a public username up; use {@link Peers#inputPeerFromLists} on the result to get an InputPeer. */
    public TLObject resolveUsername(String username) {
        TLObject resolved = (TLObject) invoke(TLObject.of("contacts.resolveUsername", "username", username));
        learnChats(resolved.getList("chats"));
        return resolved;
    }

    /** Resolves {@code username} straight to an {@code InputPeer} you can pass to {@link #sendMessage}. */
    public TLObject resolvePeer(String username) {
        TLObject resolved = resolveUsername(username);
        TLObject peer = Peers.inputPeerFromLists(resolved.getObject("peer"), resolved.getList("users"), resolved.getList("chats"));
        if (peer == null) {
            throw new SoroushException("Could not resolve '" + username + "' to a peer");
        }
        return peer;
    }

    /** Sends a text message; picks a random {@code random_id} for you. */
    public TLObject sendMessage(TLObject peer, String text) {
        return sendMessage(peer, text, null);
    }

    /** Same as {@link #sendMessage(TLObject, String)}, replying to {@code replyToMsgId} (0/negative = no reply). */
    public TLObject sendMessage(TLObject peer, String text, Integer replyToMsgId) {
        TLObject req = TLObject.of("messages.sendMessage",
                "peer", peer, "message", text, "random_id", Long.valueOf(new java.util.Random().nextLong()));
        if (replyToMsgId != null && replyToMsgId.intValue() > 0) {
            req.set("reply_to", TLObject.of("inputReplyToMessage", "reply_to_msg_id", replyToMsgId));
        }
        return (TLObject) invoke(req);
    }

    /** Resolves {@code username} and sends it a text message in one call. */
    public TLObject sendMessage(String username, String text) {
        return sendMessage(resolvePeer(username), text);
    }

    /** Uploads {@code data} and sends it as a document (file, image, audio... - whatever mimeType says). */
    public TLObject sendDocument(TLObject peer, byte[] data, String fileName, String mimeType, String caption) {
        TLObject file = Files.upload(this, data, fileName);
        TLObject media = TLObject.of("inputMediaUploadedDocument",
                "file", file, "mime_type", mimeType,
                "attributes", java.util.Collections.<Object>singletonList(
                        TLObject.of("documentAttributeFilename", "file_name", fileName)));
        return (TLObject) invoke(TLObject.of("messages.sendMedia",
                "peer", peer, "media", media, "message", caption == null ? "" : caption,
                "random_id", Long.valueOf(new java.util.Random().nextLong())));
    }

    /** Your first 100 chats, newest activity first. For more, page with {@code messages.getDialogs} directly. */
    public TLObject getDialogs() {
        return getDialogs(100);
    }

    public TLObject getDialogs(int limit) {
        TLObject dialogs = (TLObject) invoke(TLObject.of("messages.getDialogs",
                "offset_date", Integer.valueOf(0), "offset_id", Integer.valueOf(0), "offset_peer", TLObject.of("inputPeerEmpty"),
                "limit", Integer.valueOf(limit), "hash", Long.valueOf(0)));
        learnChats(dialogs.getList("chats"));
        return dialogs;
    }

    /** Makes a channel's access_hash known to the update-gap recovery, so a future pts gap on it can be
     * resynced even before any push update happens to mention it. Called automatically by
     * {@link #getDialogs} and {@link #resolveUsername}; call directly if you obtained a Channel object
     * some other way (e.g. from a raw {@code invoke()} call). */
    public void learnChats(List<Object> chats) {
        for (int i = 0; i < chats.size(); i++) {
            if (chats.get(i) instanceof TLObject) {
                tracker.learnChat((TLObject) chats.get(i));
            }
        }
    }

    /** The most recent {@code limit} messages in a chat, newest first. */
    public TLObject getHistory(TLObject peer, int limit) {
        return (TLObject) invoke(TLObject.of("messages.getHistory",
                "peer", peer, "offset_id", Integer.valueOf(0), "offset_date", Integer.valueOf(0),
                "add_offset", Integer.valueOf(0), "limit", Integer.valueOf(limit),
                "max_id", Integer.valueOf(0), "min_id", Integer.valueOf(0), "hash", Long.valueOf(0)));
    }

    /** Marks every message up to {@code maxId} (0 = everything) as read. */
    public TLObject markRead(TLObject peer, int maxId) {
        return (TLObject) invoke(TLObject.of("messages.readHistory", "peer", peer, "max_id", Integer.valueOf(maxId)));
    }

    /** Bridges the (encrypted-session) sender machinery to the plain requests the key exchange needs. */
    private static final class PlainSenderImpl implements Authenticator.PlainSender {
        private final SoroushConnection connection;
        private long lastMsgId;

        PlainSenderImpl(SoroushConnection connection) {
            this.connection = connection;
        }

        public TLObject send(TLObject request) {
            long msgId = nextMsgId();
            byte[] body = request.toBytes();
            byte[] packet = splusjava.util.Bytes.concat(new byte[8], // auth_key_id = 0 for plain requests
                    splusjava.util.Bytes.longToBytesLE(msgId), splusjava.util.Bytes.intToBytesLE(body.length), body);
            connection.sendPacket(packet);
            byte[] answer = connection.receivePacket();
            if (answer.length < 20) {
                throw new TransportException("Plain answer too short");
            }
            int length = splusjava.util.Bytes.readIntLE(answer, 16);
            byte[] answerBody = splusjava.util.Bytes.sub(answer, 20, Math.min(length, answer.length - 20));
            Object obj = splusjava.tl.TLCodec.deserialize(answerBody);
            if (!(obj instanceof TLObject)) {
                throw new TransportException("Unexpected plain answer");
            }
            return (TLObject) obj;
        }

        private synchronized long nextMsgId() {
            long nowMs = System.currentTimeMillis();
            long id = ((nowMs / 1000L) << 32) | (((nowMs % 1000L) * 4194304L / 1000L) << 2);
            if (id <= lastMsgId) {
                id = lastMsgId + 4;
            }
            lastMsgId = id;
            return id;
        }
    }
}
