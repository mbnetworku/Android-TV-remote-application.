package com.atvremote.app.adb;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal ADB-over-TCP client: handshake/auth, streams, shell and file push. */
public final class AdbConnection implements Closeable {

    static final int CNXN = 0x4e584e43;
    static final int AUTH = 0x48545541;
    static final int OPEN = 0x4e45504f;
    static final int OKAY = 0x59414b4f;
    static final int CLSE = 0x45534c43;
    static final int WRTE = 0x45545257;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 256 * 1024;

    public interface ApprovalListener {
        /** The TV is showing "Allow USB debugging?" for this phone's key. */
        void onApprovalRequired();
    }

    /** Thrown when the TV did not accept the key in time (user ignored or denied the prompt). */
    public static final class NotApprovedException extends IOException {
        NotApprovedException(String msg) {
            super(msg);
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private final Map<Integer, AdbStream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile int maxData = 4096;
    private volatile boolean closed;
    private volatile Runnable onClosed;
    private String banner = "";

    private AdbConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
    }

    public static AdbConnection connect(String host, int port, AdbCrypto crypto, ApprovalListener listener,
                                        int connectTimeoutMs, int approvalTimeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        AdbConnection c = new AdbConnection(s);
        try {
            c.handshake(crypto, listener, approvalTimeoutMs);
        } catch (IOException | RuntimeException e) {
            c.close();
            throw e;
        }
        Thread t = new Thread(c::readLoop, "adb-reader");
        t.setDaemon(true);
        t.start();
        return c;
    }

    private void handshake(AdbCrypto crypto, ApprovalListener listener, int approvalTimeoutMs) throws IOException {
        socket.setSoTimeout(10000);
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
        boolean sentSignature = false;
        boolean sentKey = false;
        while (true) {
            Message m;
            try {
                m = readMessage();
            } catch (java.net.SocketTimeoutException e) {
                if (sentKey) throw new NotApprovedException("Not allowed on the TV in time");
                throw e;
            }
            if (m.cmd == CNXN) {
                maxData = Math.max(1024, Math.min(m.arg1, MAX_DATA));
                banner = new String(m.data, StandardCharsets.UTF_8);
                break;
            }
            if (m.cmd == AUTH && m.arg0 == AUTH_TOKEN) {
                if (!sentSignature) {
                    sentSignature = true;
                    byte[] sig;
                    try {
                        sig = crypto.signToken(m.data);
                    } catch (GeneralSecurityException e) {
                        throw new IOException("Could not sign ADB token", e);
                    }
                    send(AUTH, AUTH_SIGNATURE, 0, sig);
                } else if (!sentKey) {
                    sentKey = true;
                    send(AUTH, AUTH_RSAPUBLICKEY, 0, crypto.publicKeyPayload());
                    if (listener != null) listener.onApprovalRequired();
                    socket.setSoTimeout(approvalTimeoutMs);
                } else {
                    throw new NotApprovedException("The TV rejected this phone");
                }
            }
        }
        socket.setSoTimeout(0);
    }

    public String getBanner() {
        return banner;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setOnClosed(Runnable r) {
        onClosed = r;
        if (closed && r != null) r.run();
    }

    int maxPayload() {
        return maxData;
    }

    void forget(int localId) {
        streams.remove(localId);
    }

    public AdbStream open(String destination) throws IOException {
        if (closed) throw new IOException("not connected");
        int id = nextId.getAndIncrement();
        AdbStream s = new AdbStream(this, id);
        streams.put(id, s);
        send(OPEN, id, 0, (destination + "\0").getBytes(StandardCharsets.UTF_8));
        boolean ok;
        try {
            ok = s.awaitOpen(10000);
        } catch (InterruptedException e) {
            streams.remove(id);
            throw new InterruptedIOException();
        }
        if (!ok) {
            streams.remove(id);
            throw new IOException("TV refused " + destination);
        }
        return s;
    }

    /** Runs a command and returns its output (stdout and stderr). */
    public String shell(String command, long timeoutMs) throws IOException {
        try (AdbStream s = open("shell:" + command)) {
            return new String(s.readAll(timeoutMs), StandardCharsets.UTF_8);
        }
    }

    /** Writes a file on the TV through the sync service. */
    public void push(byte[] data, String remotePath, int mode, long mtimeSeconds) throws IOException {
        try (AdbStream s = open("sync:")) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(data.length + 4096);
            byte[] spec = (remotePath + "," + (0100000 | mode)).getBytes(StandardCharsets.UTF_8);
            syncHeader(o, "SEND", spec.length);
            o.write(spec, 0, spec.length);
            for (int off = 0; off < data.length; off += 65536) {
                int n = Math.min(65536, data.length - off);
                syncHeader(o, "DATA", n);
                o.write(data, off, n);
            }
            syncHeader(o, "DONE", (int) mtimeSeconds);
            s.write(o.toByteArray());

            byte[] resp = new byte[8];
            s.readFully(resp, 20000);
            String id = new String(resp, 0, 4, StandardCharsets.US_ASCII);
            if (!"OKAY".equals(id)) {
                int len = ByteBuffer.wrap(resp, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                String msg = id;
                if ("FAIL".equals(id) && len > 0 && len < 4096) {
                    byte[] m = new byte[len];
                    s.readFully(m, 5000);
                    msg = new String(m, StandardCharsets.UTF_8);
                }
                throw new IOException("Copy to TV failed: " + msg);
            }
            o.reset();
            syncHeader(o, "QUIT", 0);
            s.write(o.toByteArray());
        }
    }

    private static void syncHeader(ByteArrayOutputStream o, String id, int value) {
        o.write(id.getBytes(StandardCharsets.US_ASCII), 0, 4);
        byte[] v = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
        o.write(v, 0, 4);
    }

    // ---- wire format ----

    private static final class Message {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private Message readMessage() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // checksum, not verified (TCP already does)
        int magic = b.getInt();
        if (magic != ~m.cmd) throw new IOException("Not an ADB device (bad header)");
        if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("Bad ADB payload length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        send(cmd, arg0, arg1, data, 0, data == null ? 0 : data.length);
    }

    void send(int cmd, int arg0, int arg1, byte[] data, int off, int len) throws IOException {
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[off + i] & 0xFF;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        synchronized (writeLock) {
            if (closed) throw new IOException("not connected");
            try {
                out.write(h.array());
                if (len > 0) out.write(data, off, len);
                out.flush();
            } catch (IOException e) {
                close();
                throw e;
            }
        }
    }

    private void readLoop() {
        try {
            while (!closed) {
                Message m = readMessage();
                AdbStream s;
                switch (m.cmd) {
                    case OKAY:
                        s = streams.get(m.arg1);
                        if (s != null) s.onOkay(m.arg0);
                        break;
                    case WRTE:
                        s = streams.get(m.arg1);
                        if (s != null) {
                            s.onData(m.data);
                            send(OKAY, m.arg1, m.arg0, null);
                        } else {
                            send(CLSE, m.arg1, m.arg0, null);
                        }
                        break;
                    case CLSE:
                        s = streams.remove(m.arg1);
                        if (s != null) s.onClosed();
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException ignored) {
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        Runnable cb;
        synchronized (this) {
            if (closed) return;
            closed = true;
            cb = onClosed;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        for (AdbStream s : new ArrayList<>(streams.values())) s.onClosed();
        streams.clear();
        if (cb != null) cb.run();
    }
}
