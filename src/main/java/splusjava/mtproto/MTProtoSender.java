package splusjava.mtproto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import splusjava.RpcException;
import splusjava.SoroushException;
import splusjava.TransportException;
import splusjava.net.SoroushConnection;
import splusjava.tl.TLCodec;
import splusjava.tl.TLObject;
import splusjava.tl.TLType;
import splusjava.tl.TLWriter;
import splusjava.util.Bytes;
import splusjava.util.Log;

/**
 * Drives one authenticated MTProto session: turns {@link TLObject} requests into encrypted packets,
 * matches answers back to their caller, keeps the session healthy (salt updates, acks, time offset
 * corrections) and hands unsolicited server messages (updates) to a listener.
 * <p>
 * One background thread reads and dispatches incoming packets; {@link #invoke(TLObject)} blocks the
 * calling thread until the matching answer arrives (or {@link #DEFAULT_TIMEOUT_MS} elapses).
 */
public final class MTProtoSender {
    /** Something the server sent that was not the answer to a request (an update, in Soroush/Telegram terms). */
    public interface UpdateListener {
        void onUpdate(TLObject update);
    }

    /** Called when the background connection drops; {@code error} is null for a clean, requested close. */
    public interface DisconnectListener {
        void onDisconnected(Throwable error);
    }

    private static final long DEFAULT_TIMEOUT_MS = 25000;
    private static final int RPC_RESULT_ID = 0xf35c6d01;
    private static final int MSG_CONTAINER_ID = 0x73f1f8dc;
    private static final int RPC_ERROR_ID = 0x2144ca19;
    private static final int NEW_SESSION_CREATED_ID = 0x9ec20908;
    private static final int BAD_SERVER_SALT_ID = 0xedab447b;
    private static final int BAD_MSG_NOTIFICATION_ID = 0xa7eff811;
    private static final int PONG_ID = 0x347773c5;
    private static final int MSGS_ACK_ID = 0x62d6b459;
    private static final int MSG_DETAILED_INFO_ID = 0x276d3ec6;
    private static final int MSG_NEW_DETAILED_INFO_ID = 0x809db6df;
    private static final int FUTURE_SALTS_ID = 0xae500895;
    private static final int DESTROY_SESSION_OK_ID = 0xe22045fc;
    private static final int DESTROY_SESSION_NONE_ID = 0x62d350c9;

    private final SoroushConnection connection;
    private final MTProtoState state;
    private volatile UpdateListener updateListener;
    private volatile DisconnectListener disconnectListener;

    private final Map<Long, PendingRequest> pending = new ConcurrentHashMap<Long, PendingRequest>();
    private final Map<Long, TLType> resultHints = new ConcurrentHashMap<Long, TLType>();
    private Thread receiveThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MTProtoSender(SoroushConnection connection, AuthKey authKey) {
        this.connection = connection;
        this.state = new MTProtoState(authKey);
    }

    public MTProtoState getState() {
        return state;
    }

    public void setUpdateListener(UpdateListener listener) {
        this.updateListener = listener;
    }

    public void setDisconnectListener(DisconnectListener listener) {
        this.disconnectListener = listener;
    }

    /** Connects the transport and starts the receive loop. Does not perform the key exchange. */
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        if (!connection.isConnected()) {
            connection.connect();
        }
        running.set(true);
        receiveThread = new Thread(new Runnable() {
            public void run() {
                receiveLoop();
            }
        }, "splusjava-receiver");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        connection.close();
        for (PendingRequest p : pending.values()) {
            p.fail(new TransportException("Connection closed"));
        }
        pending.clear();
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Sends a request and blocks until the server answers (or {@link #DEFAULT_TIMEOUT_MS} elapses). */
    public Object invoke(TLObject request) {
        return invoke(request, DEFAULT_TIMEOUT_MS);
    }

    public Object invoke(TLObject request, long timeoutMs) {
        if (!running.get()) {
            throw new TransportException("Not connected");
        }
        long msgId = state.newMsgId();
        TLType hint = request.effectiveResultType();
        PendingRequest p = new PendingRequest(msgId, hint);
        pending.put(Long.valueOf(msgId), p);
        resultHints.put(Long.valueOf(msgId), hint);
        try {
            sendEncrypted(msgId, state.nextSeqNo(true), request.toBytes());
        } catch (RuntimeException e) {
            pending.remove(Long.valueOf(msgId));
            throw e;
        }
        try {
            return p.await(timeoutMs);
        } finally {
            pending.remove(Long.valueOf(msgId));
        }
    }

    /** Fire-and-forget send (acks, msgs_ack...): no reply is expected. */
    public void send(TLObject request) {
        long msgId = state.newMsgId();
        sendEncrypted(msgId, state.nextSeqNo(true), request.toBytes());
    }

    private void sendEncrypted(long msgId, int seqNo, byte[] body) {
        byte[] inner = Bytes.concat(Bytes.longToBytesLE(msgId), Bytes.intToBytesLE(seqNo),
                Bytes.intToBytesLE(body.length), body);
        connection.sendPacket(state.encrypt(inner));
    }

    private void receiveLoop() {
        Throwable failure = null;
        try {
            while (running.get()) {
                byte[] packet;
                try {
                    packet = connection.receivePacket();
                } catch (TransportException e) {
                    if (!running.get()) {
                        break;
                    }
                    throw e;
                }
                MTProtoState.Incoming in;
                try {
                    in = state.decrypt(packet);
                } catch (MessageSecurityException e) {
                    Log.w("Dropping message that failed a security check: " + e.getMessage());
                    continue;
                }
                if (in == null) {
                    continue; // duplicate / out-of-window, already logged by MTProtoState
                }
                try {
                    dispatch(in.msgId, in.body);
                } catch (SoroushException e) {
                    Log.w("Error handling an incoming message", e);
                }
            }
        } catch (Throwable t) {
            failure = t;
        } finally {
            running.set(false);
            connection.close();
            for (PendingRequest p : pending.values()) {
                p.fail(failure != null
                        ? new TransportException("Connection lost: " + failure, failure)
                        : new TransportException("Connection closed"));
            }
            pending.clear();
            DisconnectListener l = disconnectListener;
            if (l != null) {
                l.onDisconnected(failure);
            }
        }
    }

    private void dispatch(long msgId, byte[] body) {
        if (body.length < 4) {
            return;
        }
        int constructor = Bytes.readIntLE(body, 0);
        if (constructor == RPC_RESULT_ID) {
            handleRpcResult(body);
        } else if (constructor == MSG_CONTAINER_ID) {
            handleContainer(body);
        } else if (constructor == TLCodec.GZIP_PACKED_ID) {
            byte[] inner = TLCodec.gunzip(readTLBytesAt(body, 4));
            dispatch(msgId, inner);
        } else if (constructor == NEW_SESSION_CREATED_ID) {
            long salt = Bytes.readLongLE(body, 4 + 8 + 8);
            state.setSalt(salt);
            Log.d("new_session_created, salt updated");
        } else if (constructor == BAD_SERVER_SALT_ID || constructor == BAD_MSG_NOTIFICATION_ID) {
            handleBadMsg(body, constructor == BAD_SERVER_SALT_ID);
        } else if (constructor == PONG_ID || constructor == MSGS_ACK_ID
                || constructor == MSG_DETAILED_INFO_ID || constructor == MSG_NEW_DETAILED_INFO_ID
                || constructor == FUTURE_SALTS_ID || constructor == DESTROY_SESSION_OK_ID
                || constructor == DESTROY_SESSION_NONE_ID) {
            // acknowledged implicitly; nothing to act on for a minimal client
        } else {
            deliverUpdate(body);
        }
        // content-related messages (everything except pure acks/containers) should be acked eventually;
        // a minimal but correct approach is to ack every message id we successfully handled
        if (constructor != MSG_CONTAINER_ID) {
            queueAck(msgId);
        }
    }

    private final List<Long> ackQueue = new ArrayList<Long>();

    private void queueAck(long msgId) {
        synchronized (ackQueue) {
            ackQueue.add(Long.valueOf(msgId));
            if (ackQueue.size() >= 16) {
                flushAcksLocked();
            }
        }
    }

    /** Sends any pending acks now; safe to call periodically (e.g. from a keep-alive timer). */
    public void flushAcks() {
        synchronized (ackQueue) {
            flushAcksLocked();
        }
    }

    private void flushAcksLocked() {
        if (ackQueue.isEmpty() || !running.get()) {
            return;
        }
        long[] ids = new long[ackQueue.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = ackQueue.get(i).longValue();
        }
        ackQueue.clear();
        try {
            send(TLObject.of("msgs_ack", "msg_ids", ids));
        } catch (RuntimeException e) {
            Log.w("Failed to send msgs_ack", e);
        }
    }

    private void handleRpcResult(byte[] body) {
        long reqMsgId = Bytes.readLongLE(body, 4);
        PendingRequest p = pending.remove(Long.valueOf(reqMsgId));
        TLType hint = resultHints.remove(Long.valueOf(reqMsgId));
        int innerConstructor = body.length >= 16 ? Bytes.readIntLE(body, 12) : 0;
        try {
            if (innerConstructor == RPC_ERROR_ID) {
                TLObject err = (TLObject) TLCodec.deserialize(Bytes.sub(body, 12, body.length - 12));
                RpcException ex = new RpcException(err.getInt("error_code"), err.getString("error_message"));
                if (p != null) {
                    p.fail(ex);
                } else {
                    Log.w("rpc_error for unknown request " + reqMsgId + ": " + ex.getMessage());
                }
                return;
            }
            byte[] resultBytes = Bytes.sub(body, 12, body.length - 12);
            if (innerConstructor == TLCodec.GZIP_PACKED_ID) {
                resultBytes = TLCodec.gunzip(readTLBytesAt(resultBytes, 4));
            }
            Object result = TLCodec.readResult(resultBytes, hint);
            if (p != null) {
                p.complete(result);
            }
        } catch (RuntimeException e) {
            if (p != null) {
                p.fail(e instanceof SoroushException ? (SoroushException) e : new SoroushException("Bad rpc_result", e));
            } else {
                throw e;
            }
        }
    }

    private void handleContainer(byte[] body) {
        int count = Bytes.readIntLE(body, 4);
        int off = 8;
        for (int i = 0; i < count && off + 16 <= body.length; i++) {
            long innerMsgId = Bytes.readLongLE(body, off);
            int len = Bytes.readIntLE(body, off + 12);
            off += 16;
            if (off + len > body.length) {
                Log.w("Truncated msg_container, stopping early");
                break;
            }
            byte[] innerBody = Bytes.sub(body, off, len);
            off += len;
            try {
                dispatch(innerMsgId, innerBody);
            } catch (SoroushException e) {
                Log.w("Error handling a contained message", e);
            }
        }
    }

    private void handleBadMsg(byte[] body, boolean hasSalt) {
        long badMsgId = Bytes.readLongLE(body, 4);
        int errorCode = Bytes.readIntLE(body, 16);
        if (hasSalt) {
            long newSalt = Bytes.readLongLE(body, 20);
            state.setSalt(newSalt);
        }
        if (errorCode == 16 || errorCode == 17) {
            // msg_id was too low/high compared to the server's clock; forces future msg_ids to jump forward
            state.updateTimeOffset(state.newMsgId());
        } else if (errorCode == 32 || errorCode == 33) {
            state.bumpSequence(errorCode == 32 ? 64 : 16);
        }
        PendingRequest p = pending.remove(Long.valueOf(badMsgId));
        TLType hint = resultHints.remove(Long.valueOf(badMsgId));
        if (p == null) {
            Log.d("bad_msg_notification for unknown/already answered request " + badMsgId + " (code " + errorCode + ")");
            return;
        }
        if (!hasSalt && errorCode != 16 && errorCode != 17 && errorCode != 32 && errorCode != 33) {
            p.fail(new SoroushException("bad_msg_notification, error_code=" + errorCode));
            return;
        }
        // Resend transparently under a fresh msg_id/seq_no using the request's own retained bytes is not
        // possible here (only its hash is kept) - callers see a TransportException and should retry the
        // whole invoke(); this still fixes the session (salt/time offset) for the retry to succeed.
        p.fail(new TransportException("Message rejected (code " + errorCode + "); session corrected, please retry"));
    }

    private void deliverUpdate(byte[] body) {
        Object obj;
        try {
            obj = TLCodec.deserialize(body);
        } catch (RuntimeException e) {
            Log.w("Could not parse an incoming message", e);
            return;
        }
        if (obj instanceof TLObject) {
            UpdateListener l = updateListener;
            if (l != null) {
                l.onUpdate((TLObject) obj);
            }
        }
    }

    private static byte[] readTLBytesAt(byte[] data, int offset) {
        return new splusjava.tl.TLReader(data, offset, data.length - offset).readTLBytes();
    }
}
