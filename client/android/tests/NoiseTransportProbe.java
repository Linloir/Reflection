import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.transport.PenTransport;
import io.github.linloir.reflection.transport.TcpLink;
import java.util.function.BooleanSupplier;

public final class NoiseTransportProbe {
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long limit = System.nanoTime() + 4000000000L;
        while (!condition.getAsBoolean() && System.nanoTime() < limit) Thread.sleep(5);
        if (!condition.getAsBoolean()) throw new AssertionError(message);
    }
    private static PenSample sample(int phase, long sequence) {
        return new PenSample(sequence, sequence * 10000000L, .5f, .5f,
            phase == PenSample.UP ? 0 : .5f, 0, 0, 0, phase, PenSample.PEN, 0, 1);
    }
    public static void main(String[] args) throws Exception {
        PenTransport transport = new PenTransport(() -> new TcpLink("127.0.0.1", Integer.parseInt(args[0])),
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        transport.setCalibrationNoise(.003f); transport.start();
        try {
            await(() -> transport.calibrationAcknowledged() == 1, "Initial calibration was not acknowledged");
            transport.submit(sample(PenSample.DOWN, 1));
            await(() -> transport.sent() >= 1, "DOWN was not sent");
            transport.setCalibrationNoise(.004f);
            transport.submit(sample(PenSample.MOVE, 2));
            transport.submit(sample(PenSample.UP, 3));
            await(() -> transport.calibrationAcknowledged() == 2, "Calibration update was not acknowledged");
            if (transport.appliedCalibrationNoise() != .004f) throw new AssertionError("Incorrect applied noise");
            transport.setCalibrationNoise(0);
            await(() -> transport.calibrationAcknowledged() == 3, "Calibration reset was not acknowledged");
            await(() -> !transport.connected(), "Replayed acknowledgement did not close connection");
            System.out.println("NoiseTransportProbe passed");
        } finally {
            transport.stop();
        }
    }
}
