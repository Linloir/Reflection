using System.Globalization;

namespace Reflection.Desktop;

internal readonly record struct CurveKnot(double X, double Y);

// Shape-preserving cubic Hermite interpolation, also used by both native hosts.
internal static class PressureCurve
{
    public static List<CurveKnot> Parse(string text)
    {
        var points = text.Split(';').Select(item =>
        {
            var pair = item.Split(',');
            if (pair.Length != 2) throw new FormatException("Use input,output pairs separated by semicolons.");
            return new CurveKnot(double.Parse(pair[0], CultureInfo.InvariantCulture),
                double.Parse(pair[1], CultureInfo.InvariantCulture));
        }).ToList();
        if (points.Count is < 2 or > 32 || points[0] != new CurveKnot(0, 0) || points[^1] != new CurveKnot(1, 1))
            throw new FormatException("Use 2-32 points, beginning at 0,0 and ending at 1,1.");
        for (var i = 0; i < points.Count; ++i)
        {
            var p = points[i];
            if (!double.IsFinite(p.X) || !double.IsFinite(p.Y) || p.X < 0 || p.X > 1 || p.Y < 0 || p.Y > 1 ||
                (i > 0 && (p.X <= points[i - 1].X || p.Y < points[i - 1].Y)))
                throw new FormatException("Inputs must increase and outputs must not decrease within 0-1.");
        }
        return points;
    }

    public static double Evaluate(IReadOnlyList<CurveKnot> points, double x, bool smooth)
    {
        x = Math.Clamp(x, 0, 1);
        var index = 0;
        while (index < points.Count - 2 && x > points[index + 1].X) ++index;
        var left = points[index];
        var right = points[index + 1];
        var h = right.X - left.X;
        var t = (x - left.X) / h;
        if (!smooth || points.Count == 2) return left.Y + t * (right.Y - left.Y);
        double Secant(int i) => (points[i + 1].Y - points[i].Y) / (points[i + 1].X - points[i].X);
        double Width(int i) => points[i + 1].X - points[i].X;
        double Endpoint(double h0, double h1, double d0, double d1)
        {
            var value = ((2 * h0 + h1) * d0 - h0 * d1) / (h0 + h1);
            return Math.Clamp(value, 0, 3 * d0);
        }
        double Slope(int i)
        {
            if (i == 0) return Endpoint(Width(0), Width(1), Secant(0), Secant(1));
            if (i == points.Count - 1)
                return Endpoint(Width(i - 1), Width(i - 2), Secant(i - 1), Secant(i - 2));
            var before = Secant(i - 1);
            var after = Secant(i);
            if (before <= 0 || after <= 0) return 0;
            var w1 = 2 * Width(i) + Width(i - 1);
            var w2 = Width(i) + 2 * Width(i - 1);
            return (w1 + w2) / (w1 / before + w2 / after);
        }
        var t2 = t * t;
        var t3 = t2 * t;
        var value = (2 * t3 - 3 * t2 + 1) * left.Y + (t3 - 2 * t2 + t) * h * Slope(index)
            + (-2 * t3 + 3 * t2) * right.Y + (t3 - t2) * h * Slope(index + 1);
        return Math.Clamp(value, left.Y, right.Y);
    }
}
