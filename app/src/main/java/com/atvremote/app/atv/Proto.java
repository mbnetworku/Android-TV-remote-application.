package com.atvremote.app.atv;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tiny protobuf encoder/decoder: just enough for the Android TV Remote v2 messages. */
public final class Proto {

    // ---------------------------------------------------------------- writing

    public static final class Msg {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Msg num(int field, long value) {
            tag(field, 0);
            varint(out, value);
            return this;
        }

        public Msg bool(int field, boolean value) {
            return num(field, value ? 1 : 0);
        }

        public Msg bytes(int field, byte[] value) {
            tag(field, 2);
            varint(out, value.length);
            out.write(value, 0, value.length);
            return this;
        }

        public Msg str(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        public Msg msg(int field, Msg value) {
            return bytes(field, value.toBytes());
        }

        private void tag(int field, int wire) {
            varint(out, ((long) field << 3) | wire);
        }

        public byte[] toBytes() {
            return out.toByteArray();
        }
    }

    static void varint(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    /** Writes a message with a varint length prefix, as the TV expects. */
    public static void send(OutputStream os, Msg m) throws IOException {
        byte[] body = m.toBytes();
        ByteArrayOutputStream frame = new ByteArrayOutputStream(body.length + 5);
        varint(frame, body.length);
        frame.write(body, 0, body.length);
        os.write(frame.toByteArray());
        os.flush();
    }

    // ---------------------------------------------------------------- reading

    /** Parsed message: field number -> values (Long for varints, byte[] for length-delimited). */
    public static final class Fields {
        final Map<Integer, List<Object>> map = new HashMap<>();

        public boolean has(int field) {
            return map.containsKey(field);
        }

        public long num(int field, long def) {
            List<Object> l = map.get(field);
            return l != null && l.get(0) instanceof Long ? (Long) l.get(0) : def;
        }

        public byte[] bytes(int field) {
            List<Object> l = map.get(field);
            return l != null && l.get(0) instanceof byte[] ? (byte[]) l.get(0) : null;
        }

        public String str(int field) {
            byte[] b = bytes(field);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }

        public Fields msg(int field) {
            byte[] b = bytes(field);
            return b == null ? null : parse(b);
        }
    }

    public static Fields parse(byte[] data) {
        Fields f = new Fields();
        int[] pos = {0};
        while (pos[0] < data.length) {
            long key = readVarint(data, pos);
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7);
            Object value;
            switch (wire) {
                case 0:
                    value = readVarint(data, pos);
                    break;
                case 2: {
                    int len = (int) readVarint(data, pos);
                    byte[] b = new byte[len];
                    System.arraycopy(data, pos[0], b, 0, len);
                    pos[0] += len;
                    value = b;
                    break;
                }
                case 5:
                    pos[0] += 4;
                    continue;
                case 1:
                    pos[0] += 8;
                    continue;
                default:
                    return f; // unknown wire type: stop
            }
            List<Object> l = f.map.get(field);
            if (l == null) f.map.put(field, l = new ArrayList<>());
            l.add(value);
        }
        return f;
    }

    private static long readVarint(byte[] d, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = d[pos[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
    }

    /** Reads one length-prefixed message. */
    public static Fields receive(InputStream in) throws IOException {
        long len = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            len |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        if (len < 0 || len > 1 << 20) throw new IOException("bad message length");
        byte[] body = new byte[(int) len];
        int got = 0;
        while (got < body.length) {
            int n = in.read(body, got, body.length - got);
            if (n < 0) throw new EOFException();
            got += n;
        }
        return parse(body);
    }

    private Proto() {
    }
}
