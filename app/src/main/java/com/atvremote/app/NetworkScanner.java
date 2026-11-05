package com.atvremote.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Finds devices on the phone's Wi-Fi subnet (/24) that accept connections on the ADB port. */
public final class NetworkScanner {

    public interface Callback {
        void onFound(String host);

        void onDone(int found);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private ExecutorService pool;

    public void start(Context ctx, int port, Callback cb) {
        cancel();
        cancelled = false;
        String prefix = localPrefix(ctx);
        if (prefix == null) {
            main.post(() -> cb.onDone(-1));
            return;
        }
        pool = Executors.newFixedThreadPool(48);
        AtomicInteger remaining = new AtomicInteger(254);
        AtomicInteger found = new AtomicInteger();
        for (int i = 1; i <= 254; i++) {
            final String host = prefix + i;
            pool.execute(() -> {
                if (!cancelled && probe(host, port)) {
                    found.incrementAndGet();
                    main.post(() -> {
                        if (!cancelled) cb.onFound(host);
                    });
                }
                if (remaining.decrementAndGet() == 0) {
                    main.post(() -> {
                        if (!cancelled) cb.onDone(found.get());
                    });
                }
            });
        }
        pool.shutdown();
    }

    public void cancel() {
        cancelled = true;
        if (pool != null) pool.shutdownNow();
        pool = null;
    }

    private static boolean probe(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** "192.168.1." for the phone's current network, or null if it has no IPv4 address. */
    static String localPrefix(Context ctx) {
        // NetworkInterface works on every Android version; prefer Wi-Fi/Ethernet over mobile data.
        String fallback = null;
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    byte[] b = a.getAddress();
                    String prefix = (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + ".";
                    String name = ni.getName();
                    if (name.startsWith("wlan") || name.startsWith("eth")) return prefix;
                    if (fallback == null && !name.startsWith("rmnet") && !name.startsWith("ccmni")) fallback = prefix;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
