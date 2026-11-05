package com.atvremote.app.atv;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Android TV Remote protocol v2 — what Google's own TV remote uses. Works on certified Android TV /
 * Google TV devices without developer options: the phone pairs once with a code shown on the TV.
 */
public final class AtvRemote implements Closeable {

    public static final int PAIRING_PORT = 6467;
    public static final int REMOTE_PORT = 6466;

    public interface Listener {
        void onVolume(int level, int max, boolean muted);

        void onClosed();
    }

    /** Asks the user for the 6-character code the TV is showing; returns null if cancelled. */
    public interface CodeProvider {
        String requestCode(String message) throws InterruptedException;
    }

    public static final class PairingFailed extends IOException {
        PairingFailed(String m) {
            super(m);
        }
    }

    // ---------------------------------------------------------------- pairing

    /** Runs the pairing exchange on port 6467. */
    public static void pair(String host, AtvCert cert, String clientName, CodeProvider codes) throws Exception {
        try (SSLSocket s = open(host, PAIRING_PORT, cert.sslContext())) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            Proto.send(out, base().msg(10, new Proto.Msg().str(1, "atvremote").str(2, clientName)));
            expectOk(Proto.receive(in));
            Proto.Msg hex6 = new Proto.Msg().num(1, 3).num(2, 6); // HEXADECIMAL, 6 symbols
            Proto.send(out, base().msg(20, new Proto.Msg().msg(1, hex6).num(3, 1))); // preferred role: INPUT
            expectOk(Proto.receive(in));
            Proto.send(out, base().msg(30, new Proto.Msg().msg(1, hex6).num(2, 1)));
            expectOk(Proto.receive(in)); // the TV now shows the code

            RSAPublicKey client = (RSAPublicKey) cert.cert.getPublicKey();
            RSAPublicKey server = (RSAPublicKey) s.getSession().getPeerCertificates()[0].getPublicKey();
            String message = "Enter the code shown on your TV";
            while (true) {
                String code = codes.requestCode(message);
                if (code == null) throw new PairingFailed("Pairing cancelled");
                code = code.trim().toUpperCase(java.util.Locale.ROOT);
                if (!code.matches("[0-9A-F]{6}")) {
                    message = "The code has 6 letters/numbers (0-9, A-F). Try again";
                    continue;
                }
                byte[] codeBytes = new BigInteger(code, 16).toByteArray();
                codeBytes = Arrays.copyOfRange(codeBytes, codeBytes.length - 3, codeBytes.length);
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                sha.update(unsigned(client.getModulus()));
                sha.update(unsigned(client.getPublicExponent()));
                sha.update(unsigned(server.getModulus()));
                sha.update(unsigned(server.getPublicExponent()));
                sha.update(codeBytes, 1, 2);
                byte[] secret = sha.digest();
                if (secret[0] != codeBytes[0]) {
                    message = "Wrong code. Check the TV and try again";
                    continue;
                }
                Proto.send(out, base().msg(40, new Proto.Msg().bytes(1, secret)));
                Proto.Fields r = Proto.receive(in);
                if (r.num(2, 0) != 200) throw new PairingFailed("The TV rejected the code");
                return;
            }
        }
    }

    private static Proto.Msg base() {
        return new Proto.Msg().num(1, 2).num(2, 200);
    }

    private static void expectOk(Proto.Fields f) throws PairingFailed {
        long status = f.num(2, 0);
        if (status != 200) throw new PairingFailed("TV refused pairing (status " + status + ")");
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        int i = 0;
        while (i < b.length - 1 && b[i] == 0) i++;
        return Arrays.copyOfRange(b, i, b.length);
    }

    private static SSLSocket open(String host, int port, SSLContext ctx) throws IOException {
        java.net.Socket raw = new java.net.Socket();
        raw.connect(new InetSocketAddress(host, port), 4000);
        raw.setTcpNoDelay(true);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
        s.setSoTimeout(15000);
        s.startHandshake();
        return s;
    }

    // ---------------------------------------------------------------- remote session

    private static final int FEATURES = 622; // ping, key, IME, power, volume, app links

    private final SSLSocket socket;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean closed;

    private AtvRemote(SSLSocket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
    }

    /** Connects to port 6466. Throws if the TV does not know our certificate (pair first). */
    public static AtvRemote connect(String host, AtvCert cert, String model, String version, Listener l) throws Exception {
        SSLSocket s = open(host, REMOTE_PORT, cert.sslContext());
        AtvRemote r = new AtvRemote(s);
        r.model = model;
        r.version = version;
        InputStream in = s.getInputStream();
        // Wait for the TV's configure message; an unpaired certificate gets disconnected here.
        Proto.Fields first;
        try {
            first = Proto.receive(in);
        } catch (IOException e) {
            r.close();
            throw new PairingFailed("not paired");
        }
        r.handle(first, l);
        s.setSoTimeout(0);
        Thread t = new Thread(() -> {
            try {
                while (!r.closed) r.handle(Proto.receive(in), l);
            } catch (IOException ignored) {
            } finally {
                r.close();
                if (l != null) l.onClosed();
            }
        }, "atv-remote");
        t.setDaemon(true);
        t.start();
        return r;
    }

    private String model = "Phone";
    private String version = "1";

    private void handle(Proto.Fields m, Listener l) throws IOException {
        if (m.has(1)) { // remote_configure
            send(new Proto.Msg().msg(1, new Proto.Msg().num(1, FEATURES).msg(2, new Proto.Msg()
                    .str(1, model).str(2, "TV Remote").num(3, 1).str(4, "1")
                    .str(5, "com.atvremote.app").str(6, version))));
        } else if (m.has(2)) { // remote_set_active
            send(new Proto.Msg().msg(2, new Proto.Msg().num(1, FEATURES)));
        } else if (m.has(8)) { // ping
            Proto.Fields p = m.msg(8);
            send(new Proto.Msg().msg(9, new Proto.Msg().num(1, p == null ? 0 : p.num(1, 0))));
        } else if (m.has(50) && l != null) { // volume level
            Proto.Fields v = m.msg(50);
            if (v != null) l.onVolume((int) v.num(7, 0), (int) v.num(6, 0), v.num(8, 0) != 0);
        }
    }

    private void send(Proto.Msg m) throws IOException {
        synchronized (writeLock) {
            Proto.send(out, m);
        }
    }

    /** Short key press. Key codes are the same as Android's KeyEvent codes. */
    public void key(int keyCode) throws IOException {
        send(new Proto.Msg().msg(10, new Proto.Msg().num(1, keyCode).num(2, 3)));
    }

    public void longKey(int keyCode) throws IOException, InterruptedException {
        send(new Proto.Msg().msg(10, new Proto.Msg().num(1, keyCode).num(2, 1)));
        Thread.sleep(700);
        send(new Proto.Msg().msg(10, new Proto.Msg().num(1, keyCode).num(2, 2)));
    }

    /** Opens an app link, e.g. "market://launch?id=com.netflix.ninja" or "https://www.youtube.com". */
    public void openLink(String link) throws IOException {
        send(new Proto.Msg().msg(90, new Proto.Msg().str(1, link)));
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
