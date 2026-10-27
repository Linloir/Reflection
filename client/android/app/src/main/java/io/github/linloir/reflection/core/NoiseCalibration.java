package io.github.linloir.reflection.core;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Negotiated per-session host noise prior; zero clears the tablet's override. */
public final class NoiseCalibration {
    private NoiseCalibration() {}
    public static boolean valid(float noise) {
        return !Float.isNaN(noise) && !Float.isInfinite(noise)
            && (noise == 0 || noise >= .00001f && noise <= .02f);
    }
    public static byte[] request(long sequence, float noise) {
        if (sequence <= 0 || !valid(noise)) throw new IllegalArgumentException("Invalid calibration noise");
        return ByteBuffer.allocate(12).putLong(sequence).putFloat(noise).array();
    }
    public static final class Acknowledgement {
        public final long sequence;
        public final float noise;
        private Acknowledgement(long sequence, float noise) { this.sequence = sequence; this.noise = noise; }
    }
    public static Acknowledgement acknowledge(byte[] payload, long previous, long sent, boolean negotiated) throws IOException {
        if (payload.length != 13 || payload[0] != 1) throw new IOException("Invalid calibration acknowledgement");
        ByteBuffer value = ByteBuffer.wrap(payload);
        value.get();
        long sequence = value.getLong(); float noise = value.getFloat();
        if (!valid(noise) || sequence < 0 || sequence > sent ||
            (sequence == 0 ? negotiated || noise != 0 : !negotiated || sequence <= previous || noise == 0))
            throw new IOException("Replayed or invalid calibration acknowledgement");
        return new Acknowledgement(sequence, noise);
    }
}
