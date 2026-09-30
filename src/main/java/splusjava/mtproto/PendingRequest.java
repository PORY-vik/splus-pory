package splusjava.mtproto;

import splusjava.RpcException;
import splusjava.tl.TLObject;
import splusjava.tl.TLType;

/** Tracks one in-flight request until its rpc_result (or an error) arrives. */
final class PendingRequest {
    final long msgId;
    final TLType resultHint;
    final Object lock = new Object();
    volatile boolean done;
    volatile Object result;
    volatile RuntimeException error;

    PendingRequest(long msgId, TLType resultHint) {
        this.msgId = msgId;
        this.resultHint = resultHint;
    }

    void complete(Object value) {
        synchronized (lock) {
            result = value;
            done = true;
            lock.notifyAll();
        }
    }

    void fail(RuntimeException e) {
        synchronized (lock) {
            error = e;
            done = true;
            lock.notifyAll();
        }
    }

    Object await(long timeoutMs) {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!done) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    throw new splusjava.TransportException("Request timed out (msg_id=" + msgId + ")");
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new splusjava.TransportException("Interrupted while waiting for a reply", e);
                }
            }
        }
        if (error != null) {
            throw error;
        }
        return result;
    }
}
