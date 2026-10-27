package io.github.linloir.reflection.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import io.github.linloir.reflection.core.Wire;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.util.Collections;
import java.util.LinkedHashSet;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/** First contact is approved on the host after comparing the tablet's six-digit identity code. */
final class PairingIdentity {
    private final Controller controller;
    private final SharedPreferences prefs;
    private KeyPair keys;
    private String nonce, id, code;
    private DatagramSocket socket;
    PairingIdentity(Context context, Controller controller) {
        this.controller = controller;
        prefs = context.getSharedPreferences("reflection-identity", Context.MODE_PRIVATE);
    }
    synchronized String code() { return code == null ? "------" : code; }
    synchronized String identifier() {
        try { initialize(); return id; } catch (Exception error) { return ""; }
    }
    private synchronized void initialize() throws Exception {
        if (keys != null) return;
        String publicValue = prefs.getString("public", ""), privateValue = prefs.getString("private", "");
        if (!publicValue.isEmpty() && !privateValue.isEmpty()) {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            keys = new KeyPair(factory.generatePublic(new X509EncodedKeySpec(Base64.decode(publicValue, Base64.NO_WRAP))),
                factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(privateValue, Base64.NO_WRAP))));
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            keys = generator.generateKeyPair();
            if (!prefs.edit().putString("public", encode(keys.getPublic().getEncoded()))
                    .putString("private", encode(keys.getPrivate().getEncoded())).commit())
                throw new IllegalStateException("Cannot save tablet identity");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(unsigned(((RSAPublicKey) keys.getPublic()).getModulus().toByteArray()));
        id = hex(digest).substring(0, 32);
        int number = ((digest[0] & 255) << 16 | (digest[1] & 255) << 8 | (digest[2] & 255)) % 1_000_000;
        synchronized (this) { code = String.format(java.util.Locale.ROOT, "%06d", number); }
        byte[] random = new byte[16]; new SecureRandom().nextBytes(random); nonce = hex(random);
    }
    synchronized byte[] announcement() throws Exception {
        initialize();
        RSAPublicKey publicKey = (RSAPublicKey) keys.getPublic();
        String name = Build.MANUFACTURER + " " + Build.MODEL;
        String message = "REFLECTION-DEVICE/1\n" + id + "\n" + encode(name.getBytes(StandardCharsets.UTF_8))
            + "\n" + encode(unsigned(publicKey.getModulus().toByteArray()))
            + "\n" + encode(unsigned(publicKey.getPublicExponent().toByteArray())) + "\n" + nonce;
        return message.getBytes(StandardCharsets.US_ASCII);
    }
    void announceAndReceive() throws Exception {
        byte[] bytes = announcement();
        if (socket == null) { socket = new DatagramSocket(); socket.setBroadcast(true); socket.setSoTimeout(100); }
        LinkedHashSet<InetAddress> destinations = new LinkedHashSet<>();
        destinations.add(InetAddress.getByName("255.255.255.255"));
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InterfaceAddress address : network.getInterfaceAddresses())
                if (address.getBroadcast() != null) destinations.add(address.getBroadcast());
        }
        for (InetAddress destination : destinations) {
            try { socket.send(new DatagramPacket(bytes, bytes.length, destination, 24820)); }
            catch (java.io.IOException ignored) {}
        }
        receive();
    }
    void receive() throws Exception {
        if (socket == null) return;
        for (int i = 0; i < 8; i++) {
            DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
            try { socket.receive(packet); } catch (java.net.SocketTimeoutException ignored) { return; }
            consumeReply(java.util.Arrays.copyOf(packet.getData(), packet.getLength()),
                packet.getAddress().getHostAddress(), false);
        }
    }
    void consumeReply(byte[] reply, String address, boolean bluetooth) {
        final String token, approvedNonce;
        final int port;
        try {
            synchronized (this) {
                initialize();
                if (reply.length > 1024) return;
                for (byte value : reply) if (value < 0) return;
                String[] fields = new String(reply, StandardCharsets.US_ASCII).split("\n", -1);
                if (fields.length != 3 || !fields[0].equals("REFLECTION-PAIR/1") || !fields[1].equals(nonce)) return;
                byte[] ciphertext = Base64.decode(fields[2], Base64.NO_WRAP);
                if (ciphertext.length != 256) return;
                Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
                cipher.init(Cipher.DECRYPT_MODE, keys.getPrivate(), new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
                String[] clear = new String(cipher.doFinal(ciphertext), StandardCharsets.US_ASCII).split("\n", -1);
                if (clear.length != 4 || !clear[0].equals(nonce) || !clear[1].matches("[0-9a-f]{16}")) return;
                port = Integer.parseInt(clear[2]);
                if (port < 1 || port > 65535) return;
                Wire.token(clear[3]); token = clear[3]; approvedNonce = nonce;
                byte[] random = new byte[16]; new SecureRandom().nextBytes(random); nonce = hex(random);
            }
            // Controller also takes the identity lock for ADB queries, so never call it while holding ours.
            if (bluetooth) controller.requestBluetoothHostTrust(this, token, address, approvedNonce);
            else controller.requestHostTrust(this, token, address, port, approvedNonce);
        } catch (Exception ignored) { /* Invalid ciphertext never alters the trusted-host store. */ }
    }
    void close() { if (socket != null) socket.close(); socket = null; }
    private static String encode(byte[] data) { return Base64.encodeToString(data, Base64.NO_WRAP); }
    private static byte[] unsigned(byte[] data) { return data[0] == 0 ? java.util.Arrays.copyOfRange(data, 1, data.length) : data; }
    private static String hex(byte[] data) {
        StringBuilder value = new StringBuilder();
        for (byte item : data) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return value.toString();
    }
}
