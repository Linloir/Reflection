package io.github.linloir.reflection.control;

import android.content.Context;
import android.content.SharedPreferences;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

public final class PairingIdentityTest {
    private static int checks;
    private static final String TOKEN = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
    private static final class Preferences implements SharedPreferences {
        private final Map<String, String> values = new HashMap<>();
        @Override public String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, String> pending = new HashMap<>();
                @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
                @Override public boolean commit() { values.putAll(pending); return true; }
            };
        }
    }
    private static final class Storage extends Context {
        private final Preferences preferences = new Preferences();
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
    }
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static String[] announcement(PairingIdentity identity) throws Exception {
        return new String(identity.announcement(), StandardCharsets.US_ASCII).split("\n", -1);
    }
    private static byte[] approval(String[] announcement, String plaintextNonce, String token) throws Exception {
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
            new BigInteger(1, Base64.getDecoder().decode(announcement[3])),
            new BigInteger(1, Base64.getDecoder().decode(announcement[4]))));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] encrypted = cipher.doFinal((plaintextNonce + "\n0123456789abcdef\n24819\n" + token).getBytes(StandardCharsets.US_ASCII));
        return ("REFLECTION-PAIR/1\n" + announcement[5] + "\n" + Base64.getEncoder().encodeToString(encrypted)).getBytes(StandardCharsets.US_ASCII);
    }
    public static void main(String[] args) throws Exception {
        Storage storage = new Storage(); Controller controller = new Controller();
        PairingIdentity identity = new PairingIdentity(storage, controller);
        String[] first = announcement(identity);
        check(first.length == 6 && first[1].equals(identity.identifier()), "same identity used by announcement and ADB");
        check(Arrays.equals(identity.announcement(), identity.announcement()), "nonce remains stable across pending polls");
        byte[] good = approval(first, first[5], TOKEN);
        byte[] damaged = good.clone(); damaged[damaged.length - 20] ^= 1;
        identity.consumeReply(damaged, "AA:BB:CC:DD:EE:FF", true);
        check(controller.deliveries == 0 && announcement(identity)[5].equals(first[5]), "tampered ciphertext cannot approve or rotate nonce");
        identity.consumeReply(approval(first, "00000000000000000000000000000000", TOKEN), "192.0.2.2", false);
        check(controller.deliveries == 0, "encrypted plaintext nonce must match envelope");
        identity.consumeReply(approval(first, first[5], "bad-token"), "192.0.2.2", false);
        check(controller.deliveries == 0 && announcement(identity)[5].equals(first[5]), "invalid credential preserves pending nonce");
        identity.consumeReply(good, "AA:BB:CC:DD:EE:FF", true);
        check(controller.deliveries == 1 && controller.bluetooth && TOKEN.equals(controller.token)
            && controller.address.equals("AA:BB:CC:DD:EE:FF") && controller.nonce.equals(first[5]), "Bluetooth approval reaches matching trust route");
        identity.consumeReply(good, "192.0.2.2", false);
        check(controller.deliveries == 1 && !announcement(identity)[5].equals(first[5]), "consumed nonce rejects cross-transport replay");
        String[] second = announcement(identity);
        identity.consumeReply(approval(second, second[5], TOKEN), "192.0.2.3", false);
        check(controller.deliveries == 2 && !controller.bluetooth && controller.address.equals("192.0.2.3")
            && controller.port == 24819, "LAN route continues sharing the same verifier");
        String[] concurrent = announcement(identity);
        byte[] reply = approval(concurrent, concurrent[5], TOKEN);
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        Runnable consume = () -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
            identity.consumeReply(reply, "192.0.2.4", false);
        };
        Thread one = new Thread(consume), two = new Thread(consume); one.start(); two.start();
        ready.await(); go.countDown(); one.join(); two.join();
        check(controller.deliveries == 3, "simultaneous UDP and Bluetooth replies consume each nonce only once");
        PairingIdentity restored = new PairingIdentity(storage, new Controller());
        check(announcement(restored)[1].equals(first[1]) && !announcement(restored)[5].equals(first[5]),
            "restart retains public identity and creates a fresh nonce");
        System.out.println("PairingIdentityTest passed: " + checks + " checks");
    }
}
