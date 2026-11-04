package com.atvremote.app.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** One ADB stream (a shell command, the sync service, ...). One reader thread and any writer thread. */
public final class AdbStream implements Closeable {

    private static final byte[] EOF_MARK = new byte[0];

    private final AdbConnection conn;
    final int localId;

    private final Object lock = new Object();
    private int remoteId;
    private boolean opened;
    private boolean closed;
    private boolean canWrite;

    private final LinkedBlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
    private byte[] pending;
    private int pendingOff;
    private boolean eof;

    AdbStream(AdbConnection conn, int localId) {
        this.conn = conn;
        this.localId = localId;
    }

    // ---- called by the connection's reader thread ----

    void onOkay(int remote) {
        synchronized (lock) {
            if (!opened) {
                opened = true;
                remoteId = remote;
            }
            canWrite = true;
            lock.notifyAll();
        }
    }

    void onData(byte[] data) {
        if (data != null && data.length > 0) incoming.offer(data);
    }

    void onClosed() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            lock.notifyAll();
        }
        incoming.offer(EOF_MARK);
    }

    boolean awaitOpen(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (!opened && !closed) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                lock.wait(left);
            }
            return opened;
        }
    }

    // ---- public API ----

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int n = Math.min(len, conn.maxPayload());
            int rid;
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + 15000;
                while (!canWrite && !closed) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) throw new SocketTimeoutException("TV stopped acknowledging data");
                    try {
                        lock.wait(left);
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }
                }
                if (closed) throw new IOException("stream closed");
                canWrite = false;
                rid = remoteId;
            }
            conn.send(AdbConnection.WRTE, localId, rid, b, off, n);
            off += n;
            len -= n;
        }
    }

    /** Next chunk of output; null on timeout (timeoutMs < 0 waits forever). Throws EOFException when closed. */
    public byte[] read(long timeoutMs) throws IOException {
        if (pending != null) {
            byte[] rest = Arrays.copyOfRange(pending, pendingOff, pending.length);
            pending = null;
            return rest;
        }
        if (eof) throw new EOFException();
        byte[] d;
        try {
            d = timeoutMs < 0 ? incoming.take() : incoming.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
        if (d == null) return null;
        if (d == EOF_MARK) {
            eof = true;
            throw new EOFException();
        }
        return d;
    }

    /** Blocking read; null at end of stream. */
    public byte[] read() throws IOException {
        try {
            return read(-1);
        } catch (EOFException e) {
            return null;
        }
    }

    public void readFully(byte[] dst, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int got = 0;
        while (got < dst.length) {
            if (pending == null) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new SocketTimeoutException();
                byte[] d = read(left);
                if (d == null) continue;
                pending = d;
                pendingOff = 0;
            }
            int n = Math.min(pending.length - pendingOff, dst.length - got);
            System.arraycopy(pending, pendingOff, dst, got, n);
            pendingOff += n;
            got += n;
            if (pendingOff == pending.length) pending = null;
        }
    }

    /** Everything until the TV closes the stream. */
    public byte[] readAll(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) throw new SocketTimeoutException("command timed out");
            byte[] d;
            try {
                d = read(left);
            } catch (EOFException e) {
                return out.toByteArray();
            }
            if (d != null) out.write(d, 0, d.length);
        }
    }

    @Override
    public void close() {
        boolean sendClose;
        int rid;
        synchronized (lock) {
            sendClose = opened && !closed;
            rid = remoteId;
        }
        conn.forget(localId);
        onClosed();
        if (sendClose) {
            try {
                conn.send(AdbConnection.CLSE, localId, rid, null, 0, 0);
            } catch (IOException ignored) {
            }
        }
    }
}
