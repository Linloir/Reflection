package io.github.linloir.reflection.core;

/**
 * Converts Android polar tilt to signed screen angles and repairs one verified
 * firmware format. This class has no Android dependencies and allocates no
 * objects while processing samples.
 */
public final class TiltDecoder {
    /** Reused for every call; consume its fields before processing another sample. */
    public static final class Result {
        public float tiltX, tiltY;
        public boolean reliable;
        public String status = "unavailable";
        /** Two standard axes, one recovered axis, or zero unavailable axes. */
        public int recoveredAxisCount;
        public int rotation = -1;
        public float recoveredAngle;
    }

    private static final double HALF_PI = Math.PI / 2;
    private static final double RANGE_EPSILON = 0.000001;
    private static final double MATCH_SQUARED = 0.0001 * 0.0001;
    private static final double[] SIGNATURE_X = new double[181 * 4];
    private static final double[] SIGNATURE_Y = new double[181 * 4];
    private static final double[] SIGNATURE_Z = new double[181 * 4];
    static {
        // The affected driver supplies one LE16 centidegree angle in two byte
        // axes. Android 9 InputReader treats each byte as an independent degree.
        for (int angle = -90; angle <= 90; angle++) {
            int packed = angle * 100;
            double x = Math.toRadians(packed & 255);
            double y = Math.toRadians((packed >> 8) & 255);
            double tilt = Math.acos(Math.max(-1, Math.min(1, Math.cos(x) * Math.cos(y))));
            double orientation = Math.atan2(-Math.sin(x), Math.sin(y));
            for (int rotation = 0; rotation < 4; rotation++) {
                int index = rotation * 181 + angle + 90;
                double adjusted = orientation - rotation * HALF_PI;
                SIGNATURE_X[index] = Math.sin(tilt) * Math.sin(adjusted);
                SIGNATURE_Y[index] = Math.sin(tilt) * Math.cos(adjusted);
                SIGNATURE_Z[index] = Math.cos(tilt);
            }
        }
    }

    private final Result result = new Result();
    private final int[] matchedAngles = new int[4];
    private String mode = "auto";
    private boolean deviceKnown, knownPackedDevice;
    private boolean displayKnown;
    private int displayRotation;
    private boolean timeKnown;
    private long lastTime;
    private boolean packedProven;
    private int lockedRotation = -1;
    private int candidateMask = 15, matchedSamples;
    private boolean impossibleSeen, nonzeroSeen, distinctNonzeroSeen;
    private double firstX, firstY, firstZ;
    private boolean standardRecovering;
    private int standardValidSamples;
    private long invalidSamples, suppressedSamples, recoveredSamples;

    public void configure(String value) {
        if (!"auto".equals(value) && !"standard".equals(value) && !"off".equals(value))
            throw new IllegalArgumentException("Tilt mode must be auto, standard, or off");
        if (!mode.equals(value)) { mode = value; reset(); }
    }

    /** Clears device classification and diagnostics without changing the mode. */
    public void reset() {
        resetState();
        invalidSamples = suppressedSamples = recoveredSamples = 0;
        clearResult();
        if ("off".equals(mode)) result.status = "off";
    }

    public String mode() { return mode; }
    public boolean reliable() { return result.reliable; }
    public String status() { return result.status; }
    public int recoveredAxisCount() { return result.recoveredAxisCount; }
    public int rotation() { return result.rotation; }
    public float recoveredAngle() { return result.recoveredAngle; }
    public long invalidSamples() { return invalidSamples; }
    public long suppressedSamples() { return suppressedSamples; }
    public long recoveredSamples() { return recoveredSamples; }

    /**
     * knownPackedDevice must come from an exact, verified device fingerprint.
     * displayRotation detects display changes; sensor mounting rotation is
     * inferred independently. The caller must reset when its input device changes.
     */
    public Result process(float tilt, float orientation, boolean hasTilt,
                          boolean knownPackedDevice, long timeNanos, int displayRotation) {
        clearResult();
        if ("off".equals(mode)) { result.status = "off"; return result; }
        if (deviceKnown && this.knownPackedDevice != knownPackedDevice) resetState();
        deviceKnown = true;
        this.knownPackedDevice = knownPackedDevice;
        if (displayKnown && this.displayRotation != displayRotation) clearInference();
        displayKnown = true;
        this.displayRotation = displayRotation;

        boolean standardValid = validPolar(tilt, orientation, HALF_PI);
        if (hasTilt && !standardValid) invalidSamples++;
        boolean chronological = !timeKnown || timeNanos > lastTime;
        boolean backwards = timeKnown && timeNanos < lastTime;
        if (chronological) { lastTime = timeNanos; timeKnown = true; }
        if (!hasTilt) {
            clearInference();
            standardValidSamples = 0;
            return result;
        }
        if (backwards) {
            clearInference();
            standardRecovering = true;
            standardValidSamples = 0;
            return suppress(packedProven ? "packed-recovering" : "invalid");
        }

        if ("auto".equals(mode) && knownPackedDevice) {
            int mask = 0;
            double x = 0, y = 0, z = 0;
            if (validPolar(tilt, orientation, Math.PI)) {
                double sin = Math.sin(tilt);
                x = sin * Math.sin(orientation);
                y = sin * Math.cos(orientation);
                z = Math.cos(tilt);
                mask = match(x, y, z);
            }
            if (mask != 0) {
                if (lockedRotation >= 0 && (mask & (1 << lockedRotation)) != 0)
                    return recovered(matchedAngles[lockedRotation], lockedRotation);
                if (lockedRotation >= 0 || (candidateMask & mask) == 0) clearInference();
                if (chronological) {
                    candidateMask &= mask;
                    matchedSamples = Math.min(3, matchedSamples + 1);
                    impossibleSeen |= tilt > HALF_PI + RANGE_EPSILON;
                    if (distanceSquared(x, y, z, 0, 0, 1) > MATCH_SQUARED) {
                        if (!nonzeroSeen) {
                            firstX = x; firstY = y; firstZ = z; nonzeroSeen = true;
                        } else if (distanceSquared(x, y, z, firstX, firstY, firstZ) > MATCH_SQUARED) {
                            distinctNonzeroSeen = true;
                        }
                    }
                    if (matchedSamples >= 3 && (distinctNonzeroSeen || packedProven)
                        && (packedProven || impossibleSeen)
                        && Integer.bitCount(candidateMask) == 1) {
                        lockedRotation = Integer.numberOfTrailingZeros(candidateMask);
                        packedProven = true;
                        return recovered(matchedAngles[lockedRotation], lockedRotation);
                    }
                }
                return suppress(packedProven ? "packed-recovering" : "packed-detecting");
            }
            clearInference();
            if (packedProven) return suppress("packed-recovering");
        }
        return standard(tilt, orientation, standardValid, chronological);
    }

    private Result standard(float tilt, float orientation, boolean valid, boolean chronological) {
        if (!valid) {
            standardRecovering = true;
            standardValidSamples = 0;
            return suppress("invalid");
        }
        if (standardRecovering) {
            if (chronological) standardValidSamples++;
            if (standardValidSamples < 3) return suppress("standard-recovering");
            standardRecovering = false;
            standardValidSamples = 0;
        }
        double boundedTilt = Math.min(HALF_PI, tilt);
        double horizontal = Math.sin(boundedTilt);
        double vertical = Math.cos(boundedTilt);
        double x = Math.sin(orientation) * horizontal;
        double y = -Math.cos(orientation) * horizontal;
        // atan2(tiny roundoff, 0) otherwise invents an orthogonal 90-degree tilt.
        if (Math.abs(x) < 0.0000001) x = 0;
        if (Math.abs(y) < 0.0000001) y = 0;
        result.tiltX = (float) Math.toDegrees(Math.atan2(x, vertical));
        result.tiltY = (float) Math.toDegrees(Math.atan2(y, vertical));
        result.reliable = true;
        result.status = "standard";
        result.recoveredAxisCount = 2;
        return result;
    }

    private Result recovered(int angle, int rotation) {
        // Guided measurements establish +Y toward the screen bottom at r3.
        // Only this channel is present; the orthogonal angle is unavailable.
        switch (rotation) {
            case 0: result.tiltX = angle; break;
            case 1: result.tiltY = -angle; break;
            case 2: result.tiltX = -angle; break;
            case 3: result.tiltY = angle; break;
            default: throw new AssertionError("Invalid inferred rotation");
        }
        result.reliable = true;
        result.status = "packed-single-axis";
        result.recoveredAxisCount = 1;
        result.rotation = rotation;
        result.recoveredAngle = angle;
        recoveredSamples++;
        return result;
    }

    private int match(double x, double y, double z) {
        int mask = 0;
        for (int rotation = 0; rotation < 4; rotation++) {
            int base = rotation * 181;
            for (int offset = 0; offset < 181; offset++) {
                int index = base + offset;
                if (distanceSquared(x, y, z, SIGNATURE_X[index], SIGNATURE_Y[index],
                                    SIGNATURE_Z[index]) <= MATCH_SQUARED) {
                    mask |= 1 << rotation;
                    matchedAngles[rotation] = offset - 90;
                    break;
                }
            }
        }
        return mask;
    }

    private Result suppress(String status) {
        result.status = status;
        suppressedSamples++;
        return result;
    }

    private void clearResult() {
        result.tiltX = result.tiltY = result.recoveredAngle = 0;
        result.reliable = false;
        result.status = "unavailable";
        result.recoveredAxisCount = 0;
        result.rotation = -1;
    }

    private void clearInference() {
        lockedRotation = -1;
        candidateMask = 15;
        matchedSamples = 0;
        impossibleSeen = nonzeroSeen = distinctNonzeroSeen = false;
    }

    private void resetState() {
        clearInference();
        packedProven = false;
        deviceKnown = displayKnown = timeKnown = false;
        standardRecovering = false;
        standardValidSamples = 0;
    }

    private static boolean validPolar(float tilt, float orientation, double maximumTilt) {
        return finite(tilt) && finite(orientation) && tilt >= 0
            && tilt <= maximumTilt + RANGE_EPSILON
            && orientation >= -Math.PI - RANGE_EPSILON
            && orientation <= Math.PI + RANGE_EPSILON;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static double distanceSquared(double x, double y, double z,
                                          double otherX, double otherY, double otherZ) {
        double dx = x - otherX, dy = y - otherY, dz = z - otherZ;
        return dx * dx + dy * dy + dz * dz;
    }
}
