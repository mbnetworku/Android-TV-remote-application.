package com.atvremote.app.atv;

import com.atvremote.app.adb.IoUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The phone's client certificate for the Android TV Remote protocol. The TV remembers it after
 * pairing. Built by hand (DER) so no extra libraries are needed.
 */
public final class AtvCert {

    public final PrivateKey key;
    public final X509Certificate cert;

    private AtvCert(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
    }

    public static synchronized AtvCert loadOrCreate(File dir, String commonName) throws Exception {
        File kf = new File(dir, "atv.pk8");
        File cf = new File(dir, "atv.crt");
        if (kf.isFile() && cf.isFile()) {
            try {
                PrivateKey k = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(IoUtil.read(kf)));
                return new AtvCert(k, parse(IoUtil.read(cf)));
            } catch (Exception ignored) {
                // corrupt: make a new one (the TV will ask to pair again)
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        byte[] der = selfSigned(kp, commonName);
        IoUtil.write(kf, kp.getPrivate().getEncoded());
        IoUtil.write(cf, der);
        return new AtvCert(kp.getPrivate(), parse(der));
    }

    private static X509Certificate parse(byte[] der) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    /** TLS context that presents our certificate and accepts the TV's self-signed one. */
    public SSLContext sslContext() throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("atv", key, new char[0], new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] c, String a) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] c, String a) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), new TrustManager[]{trustAll}, new SecureRandom());
        return ctx;
    }

    // ---------------------------------------------------------------- DER

    private static final byte[] SHA256_RSA = {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    private static final byte[] CN = {0x06, 0x03, 0x55, 0x04, 0x03};
    private static final byte[] NULL = {0x05, 0x00};

    private static byte[] selfSigned(KeyPair kp, String cn) throws Exception {
        byte[] algo = seq(SHA256_RSA, NULL);
        byte[] name = seq(set(seq(CN, tlv(0x0C, cn.getBytes("UTF-8")))));
        SimpleDateFormat utc = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        byte[] notBefore = tlv(0x17, utc.format(new Date(System.currentTimeMillis() - 86400000L)).getBytes("US-ASCII"));
        byte[] notAfter = tlv(0x17, "491231235959Z".getBytes("US-ASCII"));
        byte[] version = tlv(0xA0, integer(BigInteger.valueOf(2)));
        byte[] serial = integer(new BigInteger(63, new SecureRandom()).add(BigInteger.ONE));
        byte[] tbs = seq(version, serial, algo, name, seq(notBefore, notAfter), name, kp.getPublic().getEncoded());
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return seq(tbs, algo, tlv(0x03, bits));
    }

    private static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(value.length + 6);
        o.write(tag);
        int len = value.length;
        if (len < 0x80) {
            o.write(len);
        } else if (len < 0x100) {
            o.write(0x81);
            o.write(len);
        } else if (len < 0x10000) {
            o.write(0x82);
            o.write(len >> 8);
            o.write(len);
        } else {
            o.write(0x83);
            o.write(len >> 16);
            o.write(len >> 8);
            o.write(len);
        }
        o.write(value, 0, value.length);
        return o.toByteArray();
    }

    private AtvCert() {
        key = null;
        cert = null;
    }
}
