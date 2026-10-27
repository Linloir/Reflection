import io.github.linloir.reflection.core.RawTouchpadFrame;
import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.core.TouchpadEvent;
import io.github.linloir.reflection.transport.PenTransport;
import io.github.linloir.reflection.transport.TcpLink;

public final class RawTouchpadTransportProbe {
    private static RawTouchpadFrame frame(int id, int phase, long time) {
        return new RawTouchpadFrame(new RawTouchpadFrame.Contact[] {
            new RawTouchpadFrame.Contact(id, phase, .25f, .75f, true)}, false, time);
    }
    private static void waitNative(PenTransport transport, boolean expected) throws Exception {
        long end = System.nanoTime() + 5000000000L;
        while (transport.rawTouchpadSupported() != expected && System.nanoTime() < end) Thread.sleep(5);
        if (transport.rawTouchpadSupported() != expected) throw new AssertionError(transport.status());
    }
    public static void main(String[] args) throws Exception {
        PenTransport transport = new PenTransport(() -> new TcpLink("127.0.0.1", Integer.parseInt(args[0])),
            new String(new char[64]).replace('\0', 'a'));
        transport.setRawTouchpadDimensions(12000, 8000);
        try {
            transport.start();
            long end = System.nanoTime() + 5000000000L;
            while (!transport.touchpadSupported() && System.nanoTime() < end) Thread.sleep(5);
            if (!transport.touchpadSupported()) throw new AssertionError(transport.status());
            if (args.length > 1 && args[1].equals("fallback")) {
                if (transport.rawTouchpadSupported()) throw new AssertionError("Raw enabled without negotiation");
                transport.submitRawTouchpad(frame(7, 1, 10));
                transport.submitTouchpad(new TouchpadEvent(TouchpadEvent.LEFT_TAP, 1, 0, 0, 20));
                Thread.sleep(400);
                if (!transport.connected()) throw new AssertionError("Compatibility host lost connection");
                System.out.println("Raw touchpad fallback negotiation passed");
                return;
            }
            waitNative(transport, true);
            if (args.length > 1 && args[1].equals("geometry")) {
                transport.submitRawTouchpad(frame(7, 1, 20));
                transport.submit(new PenSample(1, 1000, .5f, .5f, .5f, 0, 0, 0, PenSample.DOWN, PenSample.PEN, 0, 1));
                Thread.sleep(100);
                transport.setRawTouchpadDimensions(9000, 6000);
                if (transport.rawTouchpadSupported()) throw new AssertionError("Stale native geometry remained enabled");
                transport.submit(new PenSample(2, 2000, .6f, .5f, .5f, 0, 0, 0, PenSample.MOVE, PenSample.PEN, 0, 1));
                transport.submit(new PenSample(3, 3000, .6f, .5f, 0, 0, 0, 0, PenSample.UP, PenSample.PEN, 0, 1));
                waitNative(transport, true);
                transport.submitRawTouchpad(frame(7, 2, 30));
                transport.submitRawTouchpad(frame(2, 1, 40));
                transport.submitRawTouchpad(frame(2, 3, 50));
                waitNative(transport, false);
                System.out.println("Native touchpad geometry change cancels contacts and renegotiates after pen release");
                return;
            }
            transport.submitRawTouchpad(frame(7, 2, 10));
            transport.submitRawTouchpad(frame(7, 1, 20));
            transport.submitRawTouchpad(frame(7, 2, 30));
            transport.submitRawTouchpad(frame(8, 1, 40));
            transport.submitRawTouchpad(frame(7, 2, 50));
            waitNative(transport, false);
            waitNative(transport, true);
            transport.submitRawTouchpad(frame(7, 2, 60));
            transport.submitRawTouchpad(frame(7, 3, 70));
            transport.submitRawTouchpad(frame(3, 1, 80));
            transport.submitRawTouchpad(frame(3, 3, 90));
            transport.submitRawTouchpad(RawTouchpadFrame.cancel(100));
            waitNative(transport, false);
            System.out.println("Raw touchpad TCP negotiation, lifecycle, reconnect and acknowledgement replay checks passed");
        } finally { transport.stop(); }
    }
}
