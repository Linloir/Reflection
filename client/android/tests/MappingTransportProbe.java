import io.github.linloir.reflection.core.MappingState;
import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.transport.PenTransport;
import io.github.linloir.reflection.transport.TcpLink;

public final class MappingTransportProbe {
    private static void waitFor(PenTransport transport, long revision) throws Exception {
        long deadline = System.nanoTime() + 4000000000L;
        while (System.nanoTime() < deadline) {
            MappingState state = transport.mapping();
            if (state != null && state.revision >= revision) return;
            Thread.sleep(10);
        }
        throw new AssertionError("No mapping revision " + revision + ": " + transport.status());
    }
    public static void main(String[] args) throws Exception {
        String token = new String(new char[64]).replace('\0', 'a');
        PenTransport transport = new PenTransport(() -> new TcpLink("127.0.0.1", Integer.parseInt(args[0])), token);
        try {
            transport.start(); waitFor(transport, 1);
            if (args[1].equals("replay")) {
                transport.submit(new PenSample(0, 1000, .5f, .5f, .5f, 0, 0, 0, PenSample.DOWN, PenSample.PEN, 0, 1));
                transport.requestMapping(MappingState.NEXT, null);
                transport.submit(new PenSample(1, 1001, .5f, .5f, 0, 0, 0, 0, PenSample.UP, PenSample.PEN, 0, 1));
                waitFor(transport, 3);
                if (!transport.mapping().selected.equals("right")) throw new AssertionError("Wrong selected display");
                transport.requestMapping(MappingState.ALL, null);
                waitFor(transport, 4);
            }
            long deadline = System.nanoTime() + 3500000000L;
            while (transport.connected() && System.nanoTime() < deadline) Thread.sleep(10);
            if (transport.connected()) throw new AssertionError("Invalid host was kept alive: " + args[1]);
            if (transport.mapping() != null) throw new AssertionError("Stale disconnected mapping exposed");
            System.out.println("Mapping transport " + args[1] + " passed");
        } finally { transport.stop(); }
    }
}
