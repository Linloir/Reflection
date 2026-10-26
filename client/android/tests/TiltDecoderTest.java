import io.github.linloir.reflection.core.TiltDecoder;

public final class TiltDecoderTest {
    private static int checks;
    private static long clock;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void close(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < 0.0001, message + ": " + actual + " != " + expected);
    }

    private static TiltDecoder.Result sample(TiltDecoder decoder, float tilt, float orientation,
                                             boolean known) {
        return decoder.process(tilt, orientation, true, known, ++clock * 4000000L, 0);
    }

    // Forward fixture generator follows AOSP InputReader's documented polar
    // conversion. Separate frozen device observations below test real data too.
    private static float[] polar(int angle, int rotation) {
        int bytes = angle * 100;
        double x = Math.toRadians(bytes & 255);
        double y = Math.toRadians((bytes >>> 8) & 255);
        double tilt = Math.acos(Math.cos(x) * Math.cos(y));
        double orientation = Math.atan2(-Math.sin(x), Math.sin(y)) - rotation * Math.PI / 2;
        while (orientation < -Math.PI) orientation += 2 * Math.PI;
        while (orientation > Math.PI) orientation -= 2 * Math.PI;
        return new float[]{(float) tilt, (float) orientation};
    }

    private static TiltDecoder.Result packed(TiltDecoder decoder, int angle, int rotation) {
        float[] p = polar(angle, rotation);
        return sample(decoder, p[0], p[1], true);
    }

    private static TiltDecoder.Result at(TiltDecoder decoder, int angle, int rotation,
                                          long time, int display) {
        float[] p = polar(angle, rotation);
        return decoder.process(p[0], p[1], true, true, time, display);
    }

    private static void unavailable(TiltDecoder.Result result, String status) {
        check(!result.reliable, "unavailable tilt must clear reliability");
        check(status.equals(result.status), "expected " + status + ", got " + result.status);
        check(result.recoveredAxisCount == 0 && result.rotation == -1, "no stale axis/rotation");
        close(result.tiltX, 0, "unavailable X");
        close(result.tiltY, 0, "unavailable Y");
        close(result.recoveredAngle, 0, "unavailable recovered angle");
    }

    private static void recovered(TiltDecoder.Result result, int angle, int rotation) {
        check(result.reliable && "packed-single-axis".equals(result.status), "packed output reliable");
        check(result.recoveredAxisCount == 1, "only the measured axis is recovered");
        check(result.rotation == rotation, "inferred rotation");
        close(result.recoveredAngle, angle, "decoded centidegree value");
        close(result.tiltX, rotation == 0 ? angle : rotation == 2 ? -angle : 0, "screen X");
        close(result.tiltY, rotation == 1 ? -angle : rotation == 3 ? angle : 0, "screen Y");
    }

    private static void lock(TiltDecoder decoder, int rotation) {
        unavailable(packed(decoder, 1, rotation), "packed-detecting");
        unavailable(packed(decoder, 12, rotation), "packed-detecting");
        recovered(packed(decoder, 14, rotation), 14, rotation);
    }

    private static void completeAngleRange() {
        for (int rotation = 0; rotation < 4; rotation++) {
            TiltDecoder decoder = new TiltDecoder();
            lock(decoder, rotation);
            for (int angle = -90; angle <= 90; angle++)
                recovered(packed(decoder, angle, rotation), angle, rotation);
            check(decoder.recoveredSamples() == 182, "every locked sample is counted");
            check(decoder.invalidSamples() > 0, "repaired out-of-range source data remains diagnosable");
            check(decoder.suppressedSamples() == 2, "only startup confirmation suppresses valid recovery");
        }
    }

    private static void ambiguousStartup() {
        for (int rotation = 0; rotation < 4; rotation++) {
            for (int angle : new int[]{0, 2, 53}) {
                TiltDecoder decoder = new TiltDecoder();
                for (int i = 0; i < 8; i++)
                    unavailable(packed(decoder, angle, rotation), "packed-detecting");
                if (angle == 0) unavailable(packed(decoder, 1, rotation), "packed-detecting");
                else recovered(packed(decoder, 1, rotation), 1, rotation);
                recovered(packed(decoder, 12, rotation), 12, rotation);
            }
        }
        TiltDecoder decoder = new TiltDecoder();
        for (int i = 0; i < 5; i++)
            unavailable(packed(decoder, 1, 3), "packed-detecting");
        recovered(packed(decoder, 14, 3), 14, 3);
    }

    private static void confirmationAndOrdering() {
        TiltDecoder decoder = new TiltDecoder();
        unavailable(at(decoder, 1, 2, 100, 0), "packed-detecting");
        for (int i = 0; i < 10; i++)
            unavailable(at(decoder, 12, 2, 100, 0), "packed-detecting");
        unavailable(at(decoder, 12, 2, 101, 0), "packed-detecting");
        recovered(at(decoder, 14, 2, 102, 0), 14, 2);
        recovered(at(decoder, -51, 2, 102, 0), -51, 2);
        unavailable(at(decoder, -50, 2, 101, 0), "packed-recovering");
        unavailable(at(decoder, 41, 2, 100, 0), "packed-recovering");
        unavailable(at(decoder, 41, 2, 103, 0), "packed-recovering");
        unavailable(at(decoder, 44, 2, 104, 0), "packed-recovering");
        recovered(at(decoder, 41, 2, 105, 0), 41, 2);

        decoder.reset();
        unavailable(at(decoder, 1, 0, 100, 0), "packed-detecting");
        unavailable(at(decoder, 12, 0, 99, 0), "invalid");
        unavailable(at(decoder, 14, 0, 100, 0), "packed-detecting");
        unavailable(at(decoder, 1, 0, 101, 0), "packed-detecting");
        unavailable(at(decoder, 12, 0, 102, 0), "packed-detecting");
        recovered(at(decoder, 14, 0, 103, 0), 14, 0);
    }

    private static void proofAndFingerprint() {
        TiltDecoder decoder = new TiltDecoder();
        for (int i = 0; i < 8; i++) {
            unavailable(packed(decoder, 41, 3), "packed-detecting");
            unavailable(packed(decoder, 44, 3), "packed-detecting");
        }
        recovered(packed(decoder, 1, 3), 1, 3);

        decoder.reset();
        TiltDecoder.Result normal = sample(decoder, .4f, .31f, true);
        check(normal.reliable && "standard".equals(normal.status), "same-ID corrected firmware standard");
        check(normal.recoveredAxisCount == 2 && normal.rotation == -1, "standard axes without guessed rotation");
        float[] malformed = polar(1, 3);
        unavailable(sample(decoder, malformed[0], malformed[1], false), "invalid");
        check(decoder.recoveredSamples() == 0, "unknown fingerprint never repaired");
        for (int i = 0; i < 2; i++)
            unavailable(sample(decoder, .4f, .31f, false), "standard-recovering");
        check(sample(decoder, .4f, .31f, false).reliable, "unknown valid sensor recovers normally");

        decoder.reset();
        lock(decoder, 3);
        check(sample(decoder, .4f, .31f, false).reliable, "fingerprint change discards packed classification");
        unavailable(packed(decoder, 41, 3), "packed-detecting");
        unavailable(packed(decoder, 44, 3), "packed-detecting");
        unavailable(packed(decoder, 41, 3), "packed-detecting");
        recovered(packed(decoder, 1, 3), 1, 3);
    }

    private static void malformedAndRecovery() {
        float[][] malformed = {
            {Float.NaN, 0}, {0, Float.NaN}, {Float.POSITIVE_INFINITY, 0},
            {.4f, Float.NEGATIVE_INFINITY}, {-.0001f, 0}, {2, 0},
            {.4f, 3.2f}, {.4f, -3.2f}, {4, 0}
        };
        for (boolean known : new boolean[]{false, true}) {
            for (float[] pair : malformed) {
                TiltDecoder decoder = new TiltDecoder();
                check(sample(decoder, .4f, .31f, known).reliable, "normal startup immediate");
                unavailable(sample(decoder, pair[0], pair[1], known), "invalid");
                unavailable(sample(decoder, .4f, .31f, known), "standard-recovering");
                unavailable(sample(decoder, .4f, .31f, known), "standard-recovering");
                check(sample(decoder, .4f, .31f, known).reliable, "three valid chronological samples recover");
                check(decoder.invalidSamples() == 1 && decoder.suppressedSamples() == 3,
                    "invalid and suppression counter semantics");
            }
        }
        TiltDecoder decoder = new TiltDecoder();
        sample(decoder, .4f, .31f, false);
        unavailable(decoder.process(2, 0, true, false, 100000000000L, 0), "invalid");
        for (int i = 0; i < 8; i++)
            unavailable(decoder.process(.4f, .31f, true, false, 100000000000L, 0), "standard-recovering");
        unavailable(decoder.process(.4f, .31f, true, false, 100000000001L, 0), "standard-recovering");
        unavailable(decoder.process(.4f, .31f, true, false, 100000000002L, 0), "standard-recovering");
        check(decoder.process(.4f, .31f, true, false, 100000000003L, 0).reliable,
            "duplicates do not complete recovery");
        unavailable(decoder.process(.4f, .31f, true, false, 100000000002L, 0), "invalid");
        unavailable(decoder.process(.4f, .31f, true, false, 100000000004L, 0), "standard-recovering");
        unavailable(decoder.process(.4f, .31f, true, false, 100000000005L, 0), "standard-recovering");
        check(decoder.process(.4f, .31f, true, false, 100000000006L, 0).reliable,
            "timestamp regression has bounded recovery");
    }

    private static void standardGeometry() {
        float[][] directions = {
            {0, 0, -90},
            {(float) (Math.PI / 2), 90, 0},
            {(float) -Math.PI, 0, 90},
            {(float) Math.PI, 0, 90},
            {(float) (-Math.PI / 2), -90, 0}
        };
        for (float[] direction : directions) {
            for (float tilt : new float[]{(float) (Math.PI / 2), (float) (Math.PI / 2 + .0000005)}) {
                TiltDecoder decoder = new TiltDecoder();
                TiltDecoder.Result result = sample(decoder, tilt, direction[0], false);
                check(result.reliable, "float endpoint accepted");
                close(result.tiltX, direction[1], "flat tilt X");
                close(result.tiltY, direction[2], "flat tilt Y");
            }
        }
        TiltDecoder decoder = new TiltDecoder();
        TiltDecoder.Result up = sample(decoder, (float) (Math.PI / 4), 0, false);
        close(up.tiltX, 0, "up X"); close(up.tiltY, -45, "up Y");
        TiltDecoder.Result right = sample(decoder, (float) (Math.PI / 4), (float) (Math.PI / 2), false);
        close(right.tiltX, 45, "right X"); close(right.tiltY, 0, "right Y");
        TiltDecoder.Result upright = sample(decoder, 0, 2.3f, false);
        close(upright.tiltX, 0, "upright X"); close(upright.tiltY, 0, "upright Y");
    }

    private static void modeResetRotationAndMissingData() {
        TiltDecoder decoder = new TiltDecoder();
        lock(decoder, 3);
        decoder.configure("auto");
        recovered(packed(decoder, 41, 3), 41, 3);
        decoder.configure("off");
        unavailable(packed(decoder, 14, 3), "off");
        check(decoder.suppressedSamples() == 0, "intentional off not counted as suppression");
        decoder.configure("standard");
        unavailable(packed(decoder, 1, 3), "invalid");
        check(decoder.recoveredSamples() == 0, "explicit standard never repairs");
        decoder.configure("auto");
        lock(decoder, 3);
        decoder.reset();
        check(!decoder.reliable() && "unavailable".equals(decoder.status()), "reset diagnostics immediately");
        check(decoder.invalidSamples() == 0 && decoder.suppressedSamples() == 0
            && decoder.recoveredSamples() == 0, "reset clears counters");
        unavailable(packed(decoder, 41, 3), "packed-detecting");
        unavailable(packed(decoder, 44, 3), "packed-detecting");
        unavailable(packed(decoder, 41, 3), "packed-detecting");
        recovered(packed(decoder, 1, 3), 1, 3);

        unavailable(decoder.process(Float.NaN, Float.NaN, false, true, ++clock * 4000000L, 0), "unavailable");
        unavailable(packed(decoder, 41, 3), "packed-recovering");
        unavailable(packed(decoder, 44, 3), "packed-recovering");
        recovered(packed(decoder, 41, 3), 41, 3);
        unavailable(sample(decoder, .4f, .31f, true), "packed-recovering");
        unavailable(packed(decoder, 41, 3), "packed-recovering");
        unavailable(packed(decoder, 41, 3), "packed-recovering");
        recovered(packed(decoder, 41, 3), 41, 3);

        unavailable(at(decoder, 41, 0, ++clock * 4000000L, 1), "packed-recovering");
        unavailable(at(decoder, 44, 0, ++clock * 4000000L, 1), "packed-recovering");
        recovered(at(decoder, 41, 0, ++clock * 4000000L, 1), 41, 0);
        unavailable(at(decoder, 44, 2, ++clock * 4000000L, 1), "packed-recovering");
        unavailable(at(decoder, 41, 2, ++clock * 4000000L, 1), "packed-recovering");
        recovered(at(decoder, 44, 2, ++clock * 4000000L, 1), 44, 2);

        for (String invalid : new String[]{null, "", "AUTO", "repair"}) {
            try { decoder.configure(invalid); throw new AssertionError("invalid mode accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        check("auto".equals(decoder.mode()), "rejected configuration leaves mode intact");
    }

    private static void capturedPolarFixtures() {
        // Frozen MotionEvent observations, paired with simultaneous kernel
        // centidegree values during guided upright/up/down/left/right motions.
        float[][] fixtures = {
            {-3, 1.3348598f, -2.0746179f}, {-2, 1.7160362f, -.8615133f},
            {-50, 1.2874225f, -.76358074f}, {-51, 2.124063f, -1.1795174f},
            {-48, 1.8118789f, -.75080794f}, {-47, 1.0197948f, -1.2532576f},
            {-6, 1.2807643f, -1.3567163f}, {-7, 1.6805409f, -.8008582f},
            {39, 1.066761f, .29040918f}, {41, .28762567f, 1.3229272f},
            {42, 1.8054953f, .27678403f}, {0, 0, 1.5707964f},
            {-5, 1.8437884f, -1.357787f}, {-14, 1.3222156f, -.93421715f},
            {-12, 1.6273608f, -.76504725f}, {-11, 1.2391838f, -1.5707965f},
            {-10, 1.8569883f, -1.1666678f}, {-9, 1.3971244f, -.85383743f}
        };
        TiltDecoder decoder = new TiltDecoder();
        unavailable(sample(decoder, fixtures[0][1], fixtures[0][2], true), "packed-detecting");
        unavailable(sample(decoder, fixtures[1][1], fixtures[1][2], true), "packed-detecting");
        recovered(sample(decoder, fixtures[2][1], fixtures[2][2], true), -50, 3);
        for (float[] fixture : fixtures)
            recovered(sample(decoder, fixture[1], fixture[2], true), (int) fixture[0], 3);
    }

    public static void main(String[] args) {
        completeAngleRange();
        ambiguousStartup();
        confirmationAndOrdering();
        proofAndFingerprint();
        malformedAndRecovery();
        standardGeometry();
        modeResetRotationAndMissingData();
        capturedPolarFixtures();
        System.out.println("TiltDecoderTest passed: " + checks + " checks");
    }
}
