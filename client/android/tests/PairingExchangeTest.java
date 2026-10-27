import io.github.linloir.reflection.core.PairingExchange;
import io.github.linloir.reflection.core.Wire;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class PairingExchangeTest {
    private static byte[] frame(int type, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Wire.write(new DataOutputStream(bytes), type, payload, null); return bytes.toByteArray();
    }
    private static byte[] exchange(byte[] response, ByteArrayOutputStream output) throws IOException {
        return PairingExchange.exchange(new DataInputStream(new ByteArrayInputStream(response)),
            new DataOutputStream(output), "REFLECTION-DEVICE/1\nidentity".getBytes(StandardCharsets.US_ASCII));
    }
    private static void reject(byte[] response) throws IOException {
        try { exchange(response, new ByteArrayOutputStream()); throw new AssertionError("invalid response accepted"); }
        catch (IOException expected) {}
    }
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] pending = frame(5, "REFLECTION-PENDING/1".getBytes(StandardCharsets.US_ASCII));
        if (exchange(pending, output) != null) throw new AssertionError("pending must not approve a host");
        byte[] sent = Wire.read(new DataInputStream(new ByteArrayInputStream(output.toByteArray())));
        if (sent[1] != 4 || !new String(sent, 2, sent.length - 2, StandardCharsets.US_ASCII).startsWith("REFLECTION-DEVICE/1\n"))
            throw new AssertionError("pairing uses the separate announcement frame");
        byte[] approval = "REFLECTION-PAIR/1\nnonce\nciphertext".getBytes(StandardCharsets.US_ASCII);
        if (!Arrays.equals(approval, exchange(frame(5, approval), new ByteArrayOutputStream())))
            throw new AssertionError("approval must reach the shared RSA verifier unchanged");
        reject(frame(3, approval));
        reject(frame(5, "REFLECTION-PENDING/1\nextra".getBytes(StandardCharsets.US_ASCII)));
        reject(frame(5, new byte[]{(byte) 0xff}));
        reject(frame(5, new byte[1025]));
        reject(Arrays.copyOf(pending, pending.length - 1));
        byte[] badVersion = pending.clone(); badVersion[4] = 2; reject(badVersion);
        try {
            PairingExchange.exchange(new DataInputStream(new ByteArrayInputStream(pending)),
                new DataOutputStream(new ByteArrayOutputStream()), new byte[1025]);
            throw new AssertionError("oversized announcement accepted");
        } catch (IOException expected) {}
        System.out.println("PairingExchangeTest passed: framing, pending, response type, bounds, version and truncation");
    }
}
