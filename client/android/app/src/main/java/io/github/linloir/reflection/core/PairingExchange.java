package io.github.linloir.reflection.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** The separate pairing service cannot carry pen input or authenticated session frames. */
public final class PairingExchange {
    public static final String SERVICE_UUID = "7b011e5a-8b31-4c40-8d7a-3e6c6dcbb759";
    private PairingExchange() {}

    public static byte[] exchange(DataInputStream input, DataOutputStream output, byte[] announcement) throws IOException {
        if (announcement.length == 0 || announcement.length > 1024) throw new IOException("Invalid pairing announcement size");
        Wire.write(output, 0x04, announcement, null);
        byte[] frame = Wire.read(input);
        if (frame[1] != 0x05 || frame.length > 1026) throw new IOException("Invalid pairing response");
        byte[] reply = Arrays.copyOfRange(frame, 2, frame.length);
        for (byte value : reply) if (value < 0) throw new IOException("Pairing response must be ASCII");
        String message = new String(reply, StandardCharsets.US_ASCII);
        if ("REFLECTION-PENDING/1".equals(message)) return null;
        if (!message.startsWith("REFLECTION-PAIR/1\n")) throw new IOException("Unexpected pairing response");
        return reply;
    }
}
