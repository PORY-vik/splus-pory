package splusjava.mtproto;

import java.util.LinkedList;

import splusjava.TransportException;
import splusjava.crypto.AesIge;
import splusjava.util.Bytes;

/**
 * Holds everything needed to talk to the server inside one MTProto session: the auth key, session id,
 * server salt, sequence numbers and time offset, and implements the MTProto 2.0 message encryption.
 */
public final class MTProtoState {
    /** A decrypted server message envelope. */
    public static final class Incoming {
        public final long msgId;
        public final int seqNo;
        public final byte[] body;

        Incoming(long msgId, int seqNo, byte[] body) {
            this.msgId = msgId;
            this.seqNo = seqNo;
            this.body = body;
        }
    }

    private static final int MAX_RECENT_MSG_IDS = 500;
    private static final int MSG_TOO_NEW_DELTA = 30;
    private static final int MSG_TOO_OLD_DELTA = 300;
    private static final int MAX_CONSECUTIVE_IGNORED = 10;
    private static final int BAD_SERVER_SALT_ID = 0xedab447b;
    private static final int BAD_MSG_NOTIFICATION_ID = 0xa7eff811;

    private final AuthKey authKey;
    private long sessionId;
    private long salt;
    private int sequence;
    private long lastMsgId;
    private int timeOffset;
    private final LinkedList<Long> recentRemoteIds = new LinkedList<Long>();
    private long highestRemoteId;
    private int ignoreCount;

    public MTProtoState(AuthKey authKey) {
        this.authKey = authKey;
        reset();
    }

    public AuthKey getAuthKey() {
        return authKey;
    }

    /** Starts a new session (new random session id, sequence numbers back to zero). The salt is kept. */
    public synchronized void reset() {
        sessionId = Bytes.randomLong();
        sequence = 0;
        lastMsgId = 0;
        recentRemoteIds.clear();
        highestRemoteId = 0;
        ignoreCount = 0;
    }

    public synchronized long getSalt() {
        return salt;
    }

    public synchronized void setSalt(long salt) {
        this.salt = salt;
    }

    public synchronized long getSessionId() {
        return sessionId;
    }

    public synchronized void setSessionId(long id) {
        this.sessionId = id;
    }

    public synchronized int getTimeOffset() {
        return timeOffset;
    }

    public synchronized void setTimeOffset(int seconds) {
        this.timeOffset = seconds;
    }

    /** Adds to the sequence counter (used to recover from bad_msg_notification 32/33). */
    public synchronized void bumpSequence(int delta) {
        sequence += delta;
    }

    /** Generates a new unique, increasing client message id (divisible by 4). */
    public synchronized long newMsgId() {
        long nowMs = System.currentTimeMillis() + timeOffset * 1000L;
        long seconds = nowMs / 1000L;
        long nanos = (nowMs % 1000L) * 1000000L;
        long id = (seconds << 32) | (nanos << 2);
        if (lastMsgId >= id) {
            id = lastMsgId + 4;
        }
        lastMsgId = id;
        return id;
    }

    /** Sequence number for the next message: odd for content related messages (requests), even otherwise. */
    public synchronized int nextSeqNo(boolean contentRelated) {
        if (contentRelated) {
            int r = sequence * 2 + 1;
            sequence++;
            return r;
        }
        return sequence * 2;
    }

    /** Corrects the time offset using a message id known to be valid; returns the new offset. */
    public synchronized int updateTimeOffset(long correctMsgId) {
        int old = timeOffset;
        int now = (int) (System.currentTimeMillis() / 1000L);
        int correct = (int) (correctMsgId >> 32);
        timeOffset = correct - now;
        if (timeOffset != old) {
            lastMsgId = 0;
        }
        return timeOffset;
    }

    private static byte[][] calcKey(byte[] authKey, byte[] msgKey, boolean client) {
        int x = client ? 0 : 8;
        byte[] a = Bytes.sha256(msgKey, Bytes.sub(authKey, x, 36));
        byte[] b = Bytes.sha256(Bytes.sub(authKey, x + 40, 36), msgKey);
        byte[] aesKey = Bytes.concat(Bytes.sub(a, 0, 8), Bytes.sub(b, 8, 16), Bytes.sub(a, 24, 8));
        byte[] aesIv = Bytes.concat(Bytes.sub(b, 0, 8), Bytes.sub(a, 8, 16), Bytes.sub(b, 24, 8));
        return new byte[][] {aesKey, aesIv};
    }

    /**
     * Wraps one message (msg_id, seq_no, length, body) into an encrypted packet.
     *
     * @param inner msg_id(8) + seq_no(4) + length(4) + body
     */
    public byte[] encrypt(byte[] inner) {
        return encrypt(inner, null);
    }

    /** Same as {@link #encrypt(byte[])} with explicit padding (for tests); null = random. */
    public synchronized byte[] encrypt(byte[] inner, byte[] padding) {
        byte[] data = Bytes.concat(Bytes.longToBytesLE(salt), Bytes.longToBytesLE(sessionId), inner);
        if (padding == null) {
            padding = Bytes.random(paddingLength(data.length));
        }
        byte[] key = authKey.raw();
        byte[] msgKeyLarge = Bytes.sha256(Bytes.sub(key, 88, 32), data, padding);
        byte[] msgKey = Bytes.sub(msgKeyLarge, 8, 16);
        byte[][] kv = calcKey(key, msgKey, true);
        byte[] cipher = AesIge.encrypt(Bytes.concat(data, padding), kv[0], kv[1]);
        return Bytes.concat(Bytes.longToBytesLE(authKey.getKeyId()), msgKey, cipher);
    }

    /** Length of the random padding MTProto 2.0 adds before encrypting a message of the given (unpadded) length. */
    public static int paddingLength(int dataLength) {
        return ((16 - ((dataLength + 12) % 16)) % 16) + 12;
    }

    /**
     * Decrypts a server packet.
     *
     * @return the message, or null when it has to be ignored (duplicate, too old, too new)
     * @throws TransportException when the server answered with a transport level error code
     * @throws MessageSecurityException when a security check failed
     */
    public synchronized Incoming decrypt(byte[] body) {
        long now = System.currentTimeMillis() / 1000L;
        if (body.length == 4) {
            throw new TransportException(-Bytes.readIntLE(body, 0));
        }
        if (body.length < 8) {
            throw new TransportException("Packet too short: " + body.length + " bytes");
        }
        long keyId = Bytes.readLongLE(body, 0);
        if (keyId != authKey.getKeyId()) {
            throw new MessageSecurityException("Server replied with an invalid auth key");
        }
        if (body.length < 8 + 16 + 16 + 32 || (body.length - 24) % 16 != 0) {
            throw new MessageSecurityException("Invalid encrypted packet length " + body.length);
        }
        byte[] msgKey = Bytes.sub(body, 8, 16);
        byte[][] kv = calcKey(authKey.raw(), msgKey, false);
        byte[] plain = AesIge.decrypt(Bytes.sub(body, 24, body.length - 24), kv[0], kv[1]);

        byte[] expected = Bytes.sub(Bytes.sha256(Bytes.sub(authKey.raw(), 96, 32), plain), 8, 16);
        if (!java.util.Arrays.equals(expected, msgKey)) {
            throw new MessageSecurityException("Received msg_key doesn't match with expected one");
        }
        // plain = salt(8) session_id(8) msg_id(8) seq_no(4) length(4) body padding
        if (Bytes.readLongLE(plain, 8) != sessionId) {
            throw new MessageSecurityException("Server replied with a wrong session ID");
        }
        long remoteMsgId = Bytes.readLongLE(plain, 16);
        if ((remoteMsgId & 1) != 1) {
            throw new MessageSecurityException("Server sent an even msg_id");
        }
        if (remoteMsgId <= highestRemoteId && recentRemoteIds.contains(Long.valueOf(remoteMsgId))) {
            countIgnored();
            return null;
        }
        int seqNo = Bytes.readIntLE(plain, 24);
        int length = Bytes.readIntLE(plain, 28);
        if (length < 0 || length > plain.length - 32) {
            throw new MessageSecurityException("Invalid message length " + length);
        }
        byte[] msgBody = Bytes.sub(plain, 32, length);

        int constructor = length >= 4 ? Bytes.readIntLE(msgBody, 0) : 0;
        if (constructor == BAD_SERVER_SALT_ID || constructor == BAD_MSG_NOTIFICATION_ID) {
            if (highestRemoteId == 0 && timeOffset == 0) {
                updateTimeOffset(remoteMsgId);
            }
        } else {
            long remoteTime = remoteMsgId >> 32;
            long delta = (now + timeOffset) - remoteTime;
            if (delta > MSG_TOO_OLD_DELTA || -delta > MSG_TOO_NEW_DELTA) {
                countIgnored();
                return null;
            }
        }
        recentRemoteIds.add(Long.valueOf(remoteMsgId));
        if (recentRemoteIds.size() > MAX_RECENT_MSG_IDS) {
            recentRemoteIds.removeFirst();
        }
        highestRemoteId = remoteMsgId;
        ignoreCount = 0;
        return new Incoming(remoteMsgId, seqNo, msgBody);
    }

    private void countIgnored() {
        ignoreCount++;
        if (ignoreCount >= MAX_CONSECUTIVE_IGNORED) {
            throw new MessageSecurityException("Too many messages had to be ignored consecutively");
        }
    }
}
