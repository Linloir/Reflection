import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.transport.PenTransport;
import io.github.linloir.reflection.transport.TcpLink;
import io.github.linloir.reflection.input.PenInput;
import android.view.MotionEvent;

public final class TransportProbe {
    public static void main(String[] args) throws Exception {
        String token = new String(new char[64]).replace('\0', 'a');
        PenTransport transport = new PenTransport(() -> new TcpLink("127.0.0.1", Integer.parseInt(args[0])), token);
        try {
            transport.start();
            long deadline = System.nanoTime() + 5000000000L;
            while (!transport.connected() && System.nanoTime() < deadline) Thread.sleep(10);
            if (!transport.connected()) throw new AssertionError(transport.status());
            try {
                transport.requestMapping(1, null);
                throw new AssertionError("Control sent without host negotiation");
            } catch (IllegalStateException expected) {}
            // A fresh/reconnected session must not forward the tail of an earlier stroke.
            transport.submit(new PenSample(90, 990, 0.2f, 0.4f, 0.5f, 0, 0, 0, PenSample.MOVE, PenSample.PEN, 0, 1));
            transport.submit(new PenSample(91, 991, 0.2f, 0.4f, 0, 0, 0, 0, PenSample.UP, PenSample.PEN, 0, 1));
            // Distinct historical samples can share one Android millisecond tick.
            // Exercise the production adapter and wire writer together, including equal-time UP.
            PenInput input = new PenInput(transport::submit);
            input.onEvent(new MotionEvent(MotionEvent.ACTION_DOWN,
                new MotionEvent.Sample(1000, 0, 400, 0)), 1001, 1001);
            input.onEvent(new MotionEvent(MotionEvent.ACTION_MOVE,
                new MotionEvent.Sample(1000, 100, 400, 0.1f),
                new MotionEvent.Sample(1000, 200, 400, 0.2f),
                new MotionEvent.Sample(1001, 300, 400, 0.3f),
                new MotionEvent.Sample(1002, 400, 400, 0.4f)), 1001, 1001);
            input.onEvent(new MotionEvent(MotionEvent.ACTION_UP,
                new MotionEvent.Sample(1002, 500, 400, 0)), 1001, 1001);
            deadline = System.nanoTime() + 3000000000L;
            while (transport.sent() < 6 && System.nanoTime() < deadline) Thread.sleep(10);
            if (transport.sent() != 6) throw new AssertionError("Not all samples sent: " + transport.status());
            if (args.length > 1 && "legacy".equals(args[1])) {
                Thread.sleep(1000);
                if (!transport.connected()) throw new AssertionError("Legacy host rejected equal timestamps: " + transport.status());
                System.out.println("Android equal-timestamp history accepted by legacy native host");
                return;
            }
            deadline = System.nanoTime() + 2000000000L;
            while (transport.connected() && System.nanoTime() < deadline) Thread.sleep(10);
            if (transport.connected()) throw new AssertionError("Replayed heartbeat was accepted");
            System.out.println("Android transport handshake, event delivery and replay rejection passed");
        } finally { transport.stop(); }
    }
}
