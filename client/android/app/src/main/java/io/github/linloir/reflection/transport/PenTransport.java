package io.github.linloir.reflection.transport;

import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.core.Wire;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded single-writer transport. No socket operation runs on Android's UI thread. */
public final class PenTransport {
    private final DuplexLink.Factory factory;
    private final byte[] token;
    private final ArrayBlockingQueue<PenSample> samples = new ArrayBlockingQueue<>(256);
    private final AtomicLong sent = new AtomicLong(), overflows = new AtomicLong();
    private volatile boolean running, connected;
    private volatile DuplexLink socket;
    private volatile long responseDeadline;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reflection-watchdog"); t.setDaemon(true); return t;
    });
    private volatile String status = "Disconnected";
    private volatile long rttMicros;
    private Thread worker;

    public PenTransport(DuplexLink.Factory factory, String tokenHex) {
        this.factory = factory; this.token = Wire.token(tokenHex);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        watchdog.scheduleAtFixedRate(() -> {
            long deadline = responseDeadline;
            if (running && deadline != 0 && System.nanoTime() > deadline) {
                status = "Connection timed out"; connected = false; closeSocket();
            }
        }, 250, 250, TimeUnit.MILLISECONDS);
        worker = new Thread(this::run, "reflection-transport"); worker.start();
    }

    public void submit(PenSample sample) {
        if (!connected) return;
        if (!samples.offer(sample)) {
            overflows.incrementAndGet(); status = "Queue overflow; reconnecting"; connected = false;
            closeSocket(); samples.clear();
        }
    }

    public synchronized void stop() {
        running = false; connected = false; closeSocket(); samples.clear();
        if (worker != null) worker.interrupt();
        status = "Disconnected"; watchdog.shutdownNow();
    }

    private void closeSocket() {
        DuplexLink current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) {}
    }

    private void run() {
        int retryMillis = 250;
        while (running) {
            DuplexLink current = null;
            try {
                current = factory.create(); socket = current;
                if (!running) break;
                final DuplexLink connectedLink = current;
                responseDeadline = System.nanoTime() + 5000000000L;
                status = "Connecting";
                current.connect();
                current.setReadTimeout(5000);
                DataInputStream in = new DataInputStream(current.input());
                DataOutputStream out = new DataOutputStream(current.output());
                byte[] session = Wire.authenticate(in, out, token);
                if (!running) break;
                current.setReadTimeout(2500);
                responseDeadline = System.nanoTime() + 2500000000L;
                samples.clear(); connected = true; status = "Connected"; retryMillis = 250;
                Wire.write(out, 0x20, "{\"platform\":\"android\",\"features\":[\"pressure\",\"tilt\",\"eraser\",\"hover\"],\"deviceDependent\":true}".getBytes(StandardCharsets.UTF_8), session);
                Thread reader = new Thread(() -> readResponses(connectedLink, in, session), "reflection-transport-read");
                reader.setDaemon(true); reader.start();
                long sequence = 0, timestamp = 0, lastPing = 0;
                boolean touching = false;
                while (running && connected && !current.isClosed()) {
                    PenSample sample = samples.poll(100, TimeUnit.MILLISECONDS);
                    if (sample != null) {
                        if (sample.phase == PenSample.DOWN) touching = true;
                        // Reconnection must never continue a stroke whose DOWN was lost.
                        if ((sample.phase != PenSample.MOVE && sample.phase != PenSample.UP) || touching) {
                            timestamp = Math.max(timestamp + 1, sample.timeNanos);
                            Wire.write(out, 0x10, Wire.pen(sample, ++sequence, timestamp), session); sent.incrementAndGet();
                        }
                        if (sample.phase == PenSample.UP || sample.phase == PenSample.CANCEL || sample.phase == PenSample.LEAVE) touching = false;
                    }
                    long now = System.nanoTime();
                    if (now - lastPing >= 500000000L) {
                        Wire.write(out, 0x11, ByteBuffer.allocate(8).putLong(now).array(), session); lastPing = now;
                    }
                }
            } catch (IOException | IllegalArgumentException | SecurityException e) {
                if (running) status = "Connection failed: " + safeMessage(e);
            } catch (InterruptedException e) {
                if (running) status = "Connection interrupted";
            } finally {
                connected = false; responseDeadline = 0; samples.clear();
                if (current != null) try { current.close(); } catch (IOException ignored) {}
            }
            if (running) {
                try { Thread.sleep(retryMillis); } catch (InterruptedException ignored) {}
                retryMillis = Math.min(retryMillis * 2, 5000);
            }
        }
    }

    private void readResponses(DuplexLink current, DataInputStream in, byte[] session) {
        long lastEcho = -1;
        try {
            while (running && connected && !current.isClosed()) {
                byte[] message = Wire.verify(Wire.read(in), session);
                Wire.require(message, 0x12, 10);
                long echo = ByteBuffer.wrap(message, 2, 8).getLong();
                long elapsed = System.nanoTime() - echo;
                if (echo <= lastEcho || elapsed < 0) throw new IOException("Invalid or replayed heartbeat");
                lastEcho = echo;
                if (socket == current) { responseDeadline = System.nanoTime() + 2500000000L; rttMicros = elapsed / 1000; }
            }
        } catch (IOException e) {
            if (running && socket == current) { status = "Connection lost: " + safeMessage(e); connected = false; }
            try { current.close(); } catch (IOException ignored) {}
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message.replaceAll("[\r\n]", " ");
    }
    public String status() { return status; }
    public boolean connected() { return connected; }
    public long sent() { return sent.get(); }
    public long overflows() { return overflows.get(); }
    public long rttMicros() { return rttMicros; }
}
