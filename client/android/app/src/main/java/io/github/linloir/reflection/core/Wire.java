package io.github.linloir.reflection.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Reflection v1 codec; pure Java for deterministic host-side tests. */
public final class Wire {
    public static final int VERSION = 1, MAX_FRAME = 4096;
    private Wire() {}

    public static byte[] token(String hex) {
        if (hex == null || !hex.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Token must contain 64 hexadecimal characters");
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    public static byte[] hmac(byte[] key, byte[] message) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) { throw new IOException("HMAC unavailable", e); }
    }

    private static byte[] proof(byte[] token, String label, byte[] nonce) throws IOException {
        byte[] prefix = label.getBytes(StandardCharsets.UTF_8);
        ByteBuffer message = ByteBuffer.allocate(prefix.length + nonce.length);
        message.put(prefix).put(nonce);
        return hmac(token, message.array());
    }

    public static byte[] authenticate(DataInputStream in, DataOutputStream out, byte[] token) throws IOException {
        byte[] challenge = read(in);
        require(challenge, 1, 34);
        byte[] nonce = new byte[32];
        System.arraycopy(challenge, 2, nonce, 0, nonce.length);
        write(out, 2, proof(token, "Reflection/client/v1", nonce), null);
        byte[] response = read(in);
        require(response, 3, 34);
        byte[] received = new byte[32];
        System.arraycopy(response, 2, received, 0, 32);
        if (!MessageDigest.isEqual(received, proof(token, "Reflection/server/v1", nonce))) throw new IOException("Server authentication failed");
        return proof(token, "Reflection/session/v1", nonce);
    }

    public static byte[] pen(PenSample p, long sequence, long timestamp) {
        return ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN)
            .putLong(sequence).putLong(timestamp).putFloat(p.x).putFloat(p.y).putFloat(p.pressure)
            .putFloat(p.tiltX).putFloat(p.tiltY).putFloat(p.distance).putInt(p.buttons)
            .put((byte) p.phase).put((byte) p.tool).putShort((short) 0).array();
    }

    public static byte[] touchpad(TouchpadEvent event, long sequence, long timestamp) {
        return ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).putLong(sequence).putLong(timestamp)
            .putFloat(event.x).putFloat(event.y).put((byte) event.kind).put((byte) event.fingers)
            .putShort((short) 0).putInt(0).array();
    }

    public static byte[] rawTouchpad(RawTouchpadFrame frame, long sequence, long timestamp) {
        ByteBuffer body = ByteBuffer.allocate(20 + 16 * frame.contacts.length).order(ByteOrder.BIG_ENDIAN);
        body.putLong(sequence).putLong(timestamp).put((byte) frame.contacts.length)
            .put((byte) (frame.cancelled ? 1 : 0)).putShort((short) 0);
        for (RawTouchpadFrame.Contact contact : frame.contacts)
            body.putInt(contact.id).put((byte) contact.phase).put((byte) (contact.confident ? 1 : 0))
                .putShort((short) 0).putFloat(contact.x).putFloat(contact.y);
        return body.array();
    }

    public static void write(DataOutputStream out, int type, byte[] payload, byte[] session) throws IOException {
        byte[] message = new byte[payload.length + 2];
        message[0] = VERSION; message[1] = (byte) type;
        System.arraycopy(payload, 0, message, 2, payload.length);
        byte[] tag = session == null ? new byte[0] : hmac(session, message);
        if (message.length + tag.length > MAX_FRAME) throw new IOException("Frame too large");
        out.writeInt(message.length + tag.length); out.write(message); out.write(tag); out.flush();
    }

    public static byte[] read(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 2 || size > MAX_FRAME) throw new IOException("Invalid frame length");
        byte[] body = new byte[size]; in.readFully(body);
        if (body[0] != VERSION) throw new IOException("Unsupported protocol version");
        return body;
    }

    public static byte[] verify(byte[] body, byte[] session) throws IOException {
        if (body.length < 34) throw new IOException("Missing frame authentication");
        byte[] message = new byte[body.length - 32], tag = new byte[32];
        System.arraycopy(body, 0, message, 0, message.length);
        System.arraycopy(body, message.length, tag, 0, 32);
        if (!MessageDigest.isEqual(tag, hmac(session, message))) throw new IOException("Frame authentication failed");
        return message;
    }

    public static void require(byte[] body, int type, int length) throws IOException {
        if (body.length != length || body[1] != (byte) type) throw new IOException("Unexpected protocol frame");
    }
}
