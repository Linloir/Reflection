import io.github.linloir.reflection.core.TiltMatcher;

public final class TiltMatcherTest {
    private static long time;
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void sample(TiltMatcher matcher, float x, float y, boolean reliable) {
        time += 20000000L; matcher.sample(x, y, time, reliable);
    }
    private static void hold(TiltMatcher matcher, float x, float y, int count) {
        for (int i = 0; i < count; i++) sample(matcher, x, y, true);
    }
    private static void neutral(TiltMatcher matcher) {
        hold(matcher, 0, 0, 12);
        check(matcher.stage() == TiltMatcher.Stage.SWINGING, "stable reliable neutral opens swing");
    }
    public static void main(String[] args) {
        TiltMatcher matcher = new TiltMatcher();
        for (int i = 0; i < 120; i++) sample(matcher, 0, 0, false);
        check(matcher.stage() == TiltMatcher.Stage.UNAVAILABLE && !matcher.hasData(), "unreliable startup cannot establish neutral");
        hold(matcher, 30, 0, 80);
        check(matcher.stage() == TiltMatcher.Stage.CENTERING, "initially leaned pen is not the neutral baseline");
        neutral(matcher);
        hold(matcher, 8, 0, 60); hold(matcher, 40, 0, 60); hold(matcher, 0, 0, 60);
        check(!matcher.complete() && matcher.progress() < 1, "large single-sided excursion and return cannot complete");
        hold(matcher, -8, 0, 10);
        check(matcher.complete() && matcher.axis() == 0 && matcher.sign() == 1, "opposite eight-degree swing completes measured first sign");

        matcher.reset(); neutral(matcher);
        hold(matcher, 1, -16, 45); hold(matcher, -1, 16, 45);
        check(matcher.complete() && matcher.axis() == 1 && matcher.sign() == -1, "negative first swing on other axis maps its sign");

        matcher.reset(); neutral(matcher);
        hold(matcher, 16, 0, 5); hold(matcher, -16, 0, 5);
        check(!matcher.complete(), "two quick movements need sufficient duration and sample count");
        matcher.reset(); neutral(matcher);
        matcher.sample(16, 0, time + 1000000000L, true);
        matcher.sample(-16, 0, time + 2000000000L, true);
        check(!matcher.complete(), "elapsed time alone cannot replace samples");
        time += 2000000000L;

        matcher.reset(); neutral(matcher);
        for (int i = 0; i < 100; i++) {
            float main = i < 50 ? 16 : -16;
            sample(matcher, main, i % 10 == 0 ? 45 : i % 2 == 0 ? 2 : -2, true);
        }
        check(matcher.complete() && matcher.axis() == 0, "orthogonal jitter and isolated spikes cannot select another axis");
        matcher.reset(); neutral(matcher);
        hold(matcher, 16, 15, 50); hold(matcher, -16, -15, 50);
        check(!matcher.complete(), "ambiguous diagonal swings have no dominant axis");
        matcher.reset(); neutral(matcher);
        for (int i = 0; i < 160; i++) {
            float main = (float) (16 * Math.sin(i * Math.PI / 80));
            sample(matcher, main, main * .94f, true);
        }
        check(!matcher.complete(), "slightly earlier axis threshold cannot prematurely accept a smooth diagonal");

        matcher.reset();
        for (int i = 0; i < 50; i++) sample(matcher, i % 2 == 0 ? 2 : -2, 0, true);
        check(matcher.stage() == TiltMatcher.Stage.NEUTRAL, "unstable upright position never freezes a baseline");
        neutral(matcher); hold(matcher, 20, 0, 50);
        sample(matcher, 0, 0, false);
        check(matcher.stage() == TiltMatcher.Stage.UNAVAILABLE && !matcher.hasData(), "reliability loss clears all accumulated direction evidence");
        neutral(matcher); hold(matcher, -16, 0, 90);
        check(!matcher.complete(), "old positive excursion does not survive reliability loss");
        matcher.reset();
        check(!matcher.hasData() && matcher.axis() == -1 && matcher.sign() == 0, "cancel clears result and progress");
        neutral(matcher); hold(matcher, -16, 0, 45); hold(matcher, 16, 0, 45);
        check(matcher.complete() && matcher.sign() == -1, "retry learns a fresh first direction");
        System.out.println("TiltMatcherTest passed: " + checks + " checks");
    }
}
