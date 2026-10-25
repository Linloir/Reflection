import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.core.Wire;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class CoreTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static String hex(byte[] value) { StringBuilder out = new StringBuilder(); for (byte b : value) out.append(String.format("%02x", b)); return out.toString(); }
    public static void main(String[] args) throws Exception {
        byte[] key = new byte[20]; Arrays.fill(key, (byte) 0x0b);
        check(hex(Wire.hmac(key, "Hi There".getBytes("UTF-8"))).equals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"), "RFC 4231 HMAC vector");
        PenSample sample = new PenSample(99, 100, 0.25f, 0.75f, 0.5f, -20, 35, 0, PenSample.DOWN, PenSample.ERASER, 3, 7);
        byte[] payload = Wire.pen(sample, 1, 1234);
        check(payload.length == 48, "pen payload size");
        ByteBuffer pen = ByteBuffer.wrap(payload);
        check(pen.getLong() == 1 && pen.getLong() == 1234 && pen.getFloat() == 0.25f, "network endian layout");
        check(payload[44] == 1 && payload[45] == 2 && payload[46] == 0 && payload[47] == 0, "phase, tool, reserved");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Wire.write(new DataOutputStream(bytes), 0x10, payload, key);
        byte[] framed = bytes.toByteArray();
        check(ByteBuffer.wrap(framed).getInt() == 82, "signed pen frame length");
        byte[] body = Wire.read(new DataInputStream(new ByteArrayInputStream(framed)));
        byte[] verified = Wire.verify(body, key);
        check(verified.length == 50 && verified[1] == 0x10, "signature roundtrip");
        body[10] ^= 1;
        try { Wire.verify(body, key); throw new AssertionError("accepted modified frame"); } catch (IOException expected) {}
        for (int invalid : new int[]{-1, 0, 1, 4097, Integer.MAX_VALUE}) {
            byte[] bad = ByteBuffer.allocate(4).putInt(invalid).array();
            try { Wire.read(new DataInputStream(new ByteArrayInputStream(bad))); throw new AssertionError("accepted size " + invalid); } catch (IOException expected) {}
        }
        try { Wire.token("x"); throw new AssertionError("accepted invalid token"); } catch (IllegalArgumentException expected) {}
        check(Wire.token(new String(new char[64]).replace('\0', 'a')).length == 32, "token decode");
        System.out.println("Android pure-Java codec tests passed");
    }
}
