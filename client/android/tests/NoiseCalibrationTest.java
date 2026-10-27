import io.github.linloir.reflection.core.NoiseCalibration;
import java.nio.ByteBuffer;

public final class NoiseCalibrationTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static byte[] ack(long seq, float noise) { return ByteBuffer.allocate(13).put((byte) 1).putLong(seq).putFloat(noise).array(); }
    private static void reject(byte[] body, long previous, long sent, boolean negotiated) throws Exception {
        try { NoiseCalibration.acknowledge(body, previous, sent, negotiated); }
        catch (java.io.IOException expected) { return; }
        throw new AssertionError("Invalid/replayed acknowledgement accepted");
    }
    public static void main(String[] args) throws Exception {
        byte[] request = NoiseCalibration.request(1, .003f);
        check(request.length == 12 && ByteBuffer.wrap(request).getLong() == 1, "request encoding");
        check(ByteBuffer.wrap(request, 8, 4).getFloat() == .003f, "float encoding");
        check(NoiseCalibration.valid(0) && NoiseCalibration.valid(.00001f) && NoiseCalibration.valid(.02f), "bounds and reset");
        check(!NoiseCalibration.valid(Float.NaN) && !NoiseCalibration.valid(Float.POSITIVE_INFINITY)
            && !NoiseCalibration.valid(-1) && !NoiseCalibration.valid(.03f), "invalid noise rejected");
        check(NoiseCalibration.acknowledge(ack(0, 0), 0, 0, false).sequence == 0, "capability accepted");
        check(NoiseCalibration.acknowledge(ack(1, .003f), 0, 1, true).noise == .003f, "ack accepted");
        reject(ack(0, 0), 0, 0, true);
        reject(ack(1, .003f), 1, 1, true);
        reject(ack(2, .003f), 0, 1, true);
        reject(ack(1, .003f), 0, 1, false);
        reject(ack(1, 0), 0, 1, true);
        reject(ack(1, Float.NaN), 0, 1, true);
        System.out.println("NoiseCalibrationTest passed");
    }
}
