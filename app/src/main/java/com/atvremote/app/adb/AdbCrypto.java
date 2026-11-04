package com.atvremote.app.adb;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;

/** The RSA key this app uses to authenticate with the TV's adbd, stored in app-private files. */
public final class AdbCrypto {

    /** DER prefix of a SHA-1 DigestInfo; adbd treats the 20-byte token as a SHA-1 digest. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };
    private static final int KEY_BITS = 2048;

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final String name;

    public AdbCrypto(PrivateKey privateKey, RSAPublicKey publicKey, String name) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.name = name;
    }

    public static synchronized AdbCrypto loadOrCreate(File dir, String name) throws IOException, GeneralSecurityException {
        File privFile = new File(dir, "adbkey.pk8");
        File pubFile = new File(dir, "adbkey.x509");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (privFile.isFile() && pubFile.isFile()) {
            try {
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privFile.toPath())));
                RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                        new X509EncodedKeySpec(Files.readAllBytes(pubFile.toPath())));
                return new AdbCrypto(priv, pub, name);
            } catch (GeneralSecurityException | IOException | ClassCastException e) {
                // Corrupt key files: fall through and make a new key (the TV will ask to approve again).
            }
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(KEY_BITS);
        KeyPair kp = gen.generateKeyPair();
        write(privFile, kp.getPrivate().getEncoded());
        write(pubFile, kp.getPublic().getEncoded());
        return new AdbCrypto(kp.getPrivate(), (RSAPublicKey) kp.getPublic(), name);
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    /** PKCS#1 v1.5 signature of the token, built by hand so it works the same on every JCA provider. */
    public byte[] signToken(byte[] token) throws GeneralSecurityException {
        int k = (publicKey.getModulus().bitLength() + 7) / 8;
        int tLen = SHA1_DIGEST_INFO.length + token.length;
        if (tLen + 11 > k) throw new GeneralSecurityException("token too long");
        byte[] em = new byte[k];
        em[0] = 0x00;
        em[1] = 0x01;
        Arrays.fill(em, 2, k - tLen - 1, (byte) 0xFF);
        em[k - tLen - 1] = 0x00;
        System.arraycopy(SHA1_DIGEST_INFO, 0, em, k - tLen, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, em, k - token.length, token.length);
        Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);
        return cipher.doFinal(em);
    }

    /** Payload for AUTH(RSAPUBLICKEY): base64 of Android's RSAPublicKey struct, a name, and a NUL. */
    public byte[] publicKeyPayload() {
        String s = Base64.getEncoder().encodeToString(encodeAndroidPublicKey(publicKey)) + " " + name + "\0";
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** Encodes the key the way adb's android_pubkey_encode() does. */
    public static byte[] encodeAndroidPublicKey(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        int words = n.bitLength() / 32;
        int bytes = words * 4;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(bytes * 8 * 2).mod(n);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + bytes + bytes + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, bytes));
        b.put(littleEndian(rr, bytes));
        b.putInt(key.getPublicExponent().intValue());
        return b.array();
    }

    private static byte[] littleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }
}
