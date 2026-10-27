package io.github.linloir.reflection.transport;

import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.core.TouchpadEvent;
import io.github.linloir.reflection.core.RawTouchpadFrame;
import io.github.linloir.reflection.core.MappingState;
import io.github.linloir.reflection.core.NoiseCalibration;
import io.github.linloir.reflection.core.Wire;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded single-writer transport. No socket operation runs on Android's UI thread. */
public final class PenTransport {
    private final DuplexLink.Factory factory;
    private final byte[] token;
    private static final class Outbound {
        final PenSample sample;
        final TouchpadEvent touchpad;
        final RawTouchpadFrame rawTouchpad;
        final int action;
        final String display;
        final DuplexLink link;
        Outbound(PenSample sample) { this.sample = sample; touchpad = null; rawTouchpad = null; action = -1; display = ""; link = null; }
        Outbound(TouchpadEvent event, DuplexLink link) { sample = null; touchpad = event; rawTouchpad = null; action = -1; display = ""; this.link = link; }
        Outbound(RawTouchpadFrame frame, DuplexLink link) { sample = null; touchpad = null; rawTouchpad = frame; action = -1; display = ""; this.link = link; }
        Outbound(int action, String display, DuplexLink link) { sample = null; touchpad = null; rawTouchpad = null; this.action = action; this.display = display; this.link = link; }
    }
    private final ArrayBlockingQueue<Outbound> samples = new ArrayBlockingQueue<>(256);
    private final AtomicLong sent = new AtomicLong(), overflows = new AtomicLong();
    private volatile boolean running, connected;
    private volatile DuplexLink socket;
    private volatile long responseDeadline;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reflection-watchdog"); t.setDaemon(true); return t;
    });
    private volatile String status = "Disconnected";
    private volatile long rttMicros;
    private volatile MappingState mapping;
    private volatile boolean touchpadSupported;
    private volatile boolean rawTouchpadSupported;
    private static final class RawSurface {
        final int width, height;
        RawSurface(int width, int height) { this.width = width; this.height = height; }
    }
    private volatile RawSurface rawSurface = new RawSurface(10000, 6000), activeRawSurface;
    private volatile boolean calibrationSupported;
    private volatile float calibrationNoise;
    private volatile long calibrationSent;
    private volatile long calibrationAcknowledged;
    private volatile float appliedCalibrationNoise;
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
        if (!samples.offer(new Outbound(sample))) {
            overflows.incrementAndGet(); status = "Queue overflow; reconnecting"; connected = false;
            closeSocket(); samples.clear();
        }
    }

    public boolean touchpadSupported() { return connected && (touchpadSupported || rawTouchpadSupported); }
    public boolean rawTouchpadSupported() { return connected && rawTouchpadSupported && activeRawSurface == rawSurface; }
    public synchronized void setRawTouchpadDimensions(int width, int height) {
        if (!RawTouchpadFrame.validDimension(width) || !RawTouchpadFrame.validDimension(height))
            throw new IllegalArgumentException("Invalid physical touchpad dimensions");
        if (rawSurface.width != width || rawSurface.height != height) rawSurface = new RawSurface(width, height);
    }
    public void setCalibrationNoise(float noise) {
        if (!NoiseCalibration.valid(noise)) throw new IllegalArgumentException("Calibration noise must be zero or 0.00001..0.02");
        calibrationNoise = noise;
    }
    public boolean calibrationSupported() { return connected && calibrationSupported; }
    public long calibrationAcknowledged() { return calibrationAcknowledged; }
    public float appliedCalibrationNoise() { return appliedCalibrationNoise; }

    public void submitTouchpad(TouchpadEvent event) {
        if (!connected || !touchpadSupported) return;
        if (!samples.offer(new Outbound(event, socket))) {
            overflows.incrementAndGet(); status = "Queue overflow; reconnecting"; connected = false;
            closeSocket(); samples.clear();
        }
    }

    public void submitRawTouchpad(RawTouchpadFrame frame) {
        if (!rawTouchpadSupported()) return;
        if (!samples.offer(new Outbound(frame, socket))) {
            overflows.incrementAndGet(); status = "Queue overflow; reconnecting"; connected = false;
            closeSocket(); samples.clear();
        }
    }

    /** Queued with samples so switching can never overtake an already submitted pen transition. */
    public void requestMapping(int action, String display) {
        MappingState.request(1, action, display); // Validate before entering the queue.
        DuplexLink current = socket;
        if (!connected || mapping == null)
            throw new IllegalStateException("The connected host has not enabled screen mapping controls");
        if (!samples.offer(new Outbound(action, display, current)))
            throw new IllegalStateException("Input queue is busy; retry after lifting the pen");
    }

    public MappingState mapping() { return connected ? mapping : null; }

    public synchronized void stop() {
        running = false; connected = false; mapping = null; touchpadSupported = rawTouchpadSupported = calibrationSupported = false; closeSocket(); samples.clear();
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
                samples.clear(); mapping = null; touchpadSupported = rawTouchpadSupported = calibrationSupported = false; calibrationSent = 0;
                calibrationAcknowledged = 0; appliedCalibrationNoise = 0;
                connected = true; status = "Connected"; retryMillis = 250;
                final RawSurface sessionSurface = rawSurface;
                activeRawSurface = sessionSurface;
                String capabilities = "{\"platform\":\"android\",\"features\":[\"pressure\",\"tilt\",\"eraser\",\"hover\"],\"deviceDependent\":true,\"mappingControl\":1,\"hostStabilization\":1,\"touchpad\":1,\"tabletCalibration\":1,\"rawTouchpad\":1,\"rawTouchpadWidth\":"
                    + sessionSurface.width + ",\"rawTouchpadHeight\":" + sessionSurface.height + "}";
                Wire.write(out, 0x20, capabilities.getBytes(StandardCharsets.UTF_8), session);
                Thread reader = new Thread(() -> readResponses(connectedLink, in, session, sessionSurface), "reflection-transport-read");
                reader.setDaemon(true); reader.start();
                long sequence = 0, timestamp = 0, lastPing = 0, mappingSequence = 0, touchSequence = 0, touchTimestamp = 0;
                long rawSequence = 0, rawTimestamp = 0;
                RawTouchpadFrame.State rawState = new RawTouchpadFrame.State();
                boolean touching = false;
                float sentNoise = Float.NaN;
                while (running && connected && !current.isClosed()) {
                    Outbound next = samples.poll(100, TimeUnit.MILLISECONDS);
                    float requestedNoise = calibrationNoise;
                    if (calibrationSupported && !touching && requestedNoise != sentNoise) {
                        byte[] request = NoiseCalibration.request(calibrationSent + 1, requestedNoise);
                        calibrationSent++;
                        Wire.write(out, 0x25, request, session); sentNoise = requestedNoise;
                    }
                    PenSample sample = next == null ? null : next.sample;
                    if (sample != null) {
                        if (sample.phase == PenSample.DOWN) touching = true;
                        // Reconnection must never continue a stroke whose DOWN was lost.
                        if ((sample.phase != PenSample.MOVE && sample.phase != PenSample.UP) || touching) {
                            // Android's millisecond clock can give distinct history points the same time.
                            // Sequence orders those points; never invent a 1 ns sampling interval.
                            timestamp = Math.max(timestamp, sample.timeNanos);
                            Wire.write(out, 0x10, Wire.pen(sample, ++sequence, timestamp), session); sent.incrementAndGet();
                        }
                        if (sample.phase == PenSample.UP || sample.phase == PenSample.CANCEL || sample.phase == PenSample.LEAVE) touching = false;
                    } else if (next != null && next.link == current && next.rawTouchpad != null && rawTouchpadSupported()) {
                        RawTouchpadFrame frame = next.rawTouchpad;
                        rawTimestamp = Math.max(rawTimestamp, frame.timeNanos);
                        if (rawState.accept(frame)) {
                            Wire.write(out, 0x27, Wire.rawTouchpad(frame, ++rawSequence, rawTimestamp), session);
                        } else if (rawState.active()) {
                            rawState.reset();
                            Wire.write(out, 0x27, Wire.rawTouchpad(RawTouchpadFrame.cancel(rawTimestamp), ++rawSequence, rawTimestamp), session);
                        }
                    } else if (next != null && next.link == current && next.touchpad != null && touchpadSupported) {
                        touchTimestamp = Math.max(touchTimestamp, next.touchpad.timeNanos);
                        Wire.write(out, 0x23, Wire.touchpad(next.touchpad, ++touchSequence, touchTimestamp), session);
                    } else if (next != null && next.link == current && next.action >= 0 && mapping != null) {
                        Wire.write(out, 0x21, MappingState.request(++mappingSequence, next.action, next.display), session);
                    }
                    if (rawTouchpadSupported && sessionSurface != rawSurface && !touching) {
                        if (rawState.active())
                            Wire.write(out, 0x27, Wire.rawTouchpad(RawTouchpadFrame.cancel(rawTimestamp), ++rawSequence, rawTimestamp), session);
                        status = "Touchpad surface changed; reconnecting"; connected = false; closeSocket(); break;
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
                connected = false; mapping = null; touchpadSupported = rawTouchpadSupported = calibrationSupported = false; responseDeadline = 0; samples.clear();
                if (current != null) try { current.close(); } catch (IOException ignored) {}
            }
            if (running) {
                try { Thread.sleep(retryMillis); } catch (InterruptedException ignored) {}
                retryMillis = Math.min(retryMillis * 2, 5000);
            }
        }
    }

    private void readResponses(DuplexLink current, DataInputStream in, byte[] session, RawSurface surface) {
        long lastEcho = -1, lastMappingRevision = 0, lastCalibrationAck = 0;
        boolean rawAcknowledged = false;
        try {
            while (running && connected && !current.isClosed()) {
                byte[] message = Wire.verify(Wire.read(in), session);
                if (message[1] == 0x28) {
                    Wire.require(message, 0x28, 11);
                    ByteBuffer dimensions = ByteBuffer.wrap(message, 3, 8);
                    if (message[2] != 1 || rawAcknowledged || dimensions.getInt() != surface.width
                            || dimensions.getInt() != surface.height) throw new IOException("Invalid native touchpad capability");
                    rawAcknowledged = true;
                    if (socket == current) rawTouchpadSupported = true;
                    continue;
                }
                if (message[1] == 0x26) {
                    NoiseCalibration.Acknowledgement acknowledgement = NoiseCalibration.acknowledge(
                        Arrays.copyOfRange(message, 2, message.length), lastCalibrationAck, calibrationSent, calibrationSupported);
                    lastCalibrationAck = acknowledgement.sequence;
                    if (socket == current) {
                        calibrationSupported = true;
                        appliedCalibrationNoise = acknowledgement.noise;
                        calibrationAcknowledged = acknowledgement.sequence;
                    }
                    continue;
                }
                if (message[1] == 0x24) {
                    Wire.require(message, 0x24, 3);
                    if (message[2] != 1 || touchpadSupported) throw new IOException("Invalid touchpad capability");
                    if (socket == current) touchpadSupported = true;
                    continue;
                }
                if (message[1] == 0x22) {
                    MappingState next = MappingState.decode(Arrays.copyOfRange(message, 2, message.length));
                    if (next.revision <= lastMappingRevision) throw new IOException("Invalid or replayed mapping status");
                    lastMappingRevision = next.revision;
                    if (socket == current) mapping = next;
                    // Only a valid pong extends the host's liveness deadline.
                    continue;
                }
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
