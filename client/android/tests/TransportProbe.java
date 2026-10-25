import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.transport.PenTransport;
import io.github.linloir.reflection.transport.TcpLink;

public final class TransportProbe {
    public static void main(String[] args) throws Exception {
        String token = new String(new char[64]).replace('\0', 'a');
        PenTransport transport = new PenTransport(() -> new TcpLink("127.0.0.1", Integer.parseInt(args[0])), token);
        try {
            transport.start();
            long deadline = System.nanoTime() + 5000000000L;
            while (!transport.connected() && System.nanoTime() < deadline) Thread.sleep(10);
            if (!transport.connected()) throw new AssertionError(transport.status());
            // A fresh/reconnected session must not forward the tail of an earlier stroke.
            transport.submit(new PenSample(90, 990, 0.2f, 0.4f, 0.5f, 0, 0, 0, PenSample.MOVE, PenSample.PEN, 0, 1));
            transport.submit(new PenSample(91, 991, 0.2f, 0.4f, 0, 0, 0, 0, PenSample.UP, PenSample.PEN, 0, 1));
            for (int i = 0; i < 6; i++) transport.submit(new PenSample(i, 1000 + i, 0.1f * i, 0.4f, i == 5 ? 0 : 0.1f * i,
                0, 0, 0, i == 0 ? PenSample.DOWN : i == 5 ? PenSample.UP : PenSample.MOVE, PenSample.PEN, 0, 1));
            deadline = System.nanoTime() + 3000000000L;
            while (transport.sent() < 6 && System.nanoTime() < deadline) Thread.sleep(10);
            if (transport.sent() != 6) throw new AssertionError("Not all samples sent: " + transport.status());
            deadline = System.nanoTime() + 2000000000L;
            while (transport.connected() && System.nanoTime() < deadline) Thread.sleep(10);
            if (transport.connected()) throw new AssertionError("Replayed heartbeat was accepted");
            System.out.println("Android transport handshake, event delivery and replay rejection passed");
        } finally { transport.stop(); }
    }
}
