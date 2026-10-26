#include "reflection/core.hpp"
#include <limits>

namespace reflection {

static bool finite_rect(const Rect& rect) {
    return std::isfinite(rect.x) && std::isfinite(rect.y) &&
        std::isfinite(rect.width) && std::isfinite(rect.height) &&
        rect.width > 0 && rect.height > 0;
}

void Mapping::validate() const {
    if (!finite_rect(source) || source.x < 0 || source.y < 0 ||
        source.x + source.width > 1.000001 || source.y + source.height > 1.000001)
        throw std::invalid_argument("source must be a nonempty rectangle inside [0,1]");
    if (!finite_rect(target) || target.width < 1 || target.height < 1 ||
        std::abs(target.x) > 100000 || std::abs(target.y) > 100000 ||
        target.width > 100000 || target.height > 100000)
        throw std::invalid_argument("invalid target pixel rectangle");
    if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)
        throw std::invalid_argument("rotation must be 0, 90, 180 or 270");
    if (!std::isfinite(pressure_gamma) || pressure_gamma < 0.1 || pressure_gamma > 10)
        throw std::invalid_argument("pressure_gamma must be in [0.1,10]");
    if (pressure_interpolation != "linear" && pressure_interpolation != "smooth")
        throw std::invalid_argument("pressure_interpolation must be linear or smooth");
    if (!pressure_curve.empty()) {
        if (pressure_curve.size() < 2 || pressure_curve.size() > 32 ||
            pressure_curve.front() != std::make_pair(0.0, 0.0) ||
            pressure_curve.back() != std::make_pair(1.0, 1.0))
            throw std::invalid_argument("pressure_curve requires 2..32 knots from 0,0 to 1,1");
        double previous_x = -1, previous_y = -1;
        for (const auto& [x, y] : pressure_curve) {
            if (!std::isfinite(x) || !std::isfinite(y) || x < 0 || x > 1 || y < 0 || y > 1 ||
                x <= previous_x || y < previous_y)
                throw std::invalid_argument("pressure_curve inputs must increase and outputs must not decrease");
            previous_x = x; previous_y = y;
        }
    }
    for (const auto steps : {position_steps_x, position_steps_y})
        if (steps != 0 && (steps < 2 || steps > 65536))
            throw std::invalid_argument("position_steps must be 0 (native) or 2..65536");
    if (!std::isfinite(tilt_scale) || tilt_scale < 0 || tilt_scale > 2)
        throw std::invalid_argument("tilt_scale must be in [0,2]");
    if (!std::isfinite(sensitivity) || sensitivity <= 0 || sensitivity > 10)
        throw std::invalid_argument("sensitivity must be in (0,10]");
}

Mapper::Mapper(Mapping mapping) : mapping_(mapping) {
    mapping_.validate();
    const auto& p = mapping_.pressure_curve;
    if (p.size() >= 2) {
        const size_t n = p.size();
        std::vector<double> h(n - 1), d(n - 1);
        for (size_t i = 0; i + 1 < n; ++i) {
            h[i] = p[i + 1].first - p[i].first;
            d[i] = (p[i + 1].second - p[i].second) / h[i];
        }
        pressure_slopes_.assign(n, d[0]);
        if (n > 2) {
            pressure_slopes_[0] = std::clamp(((2*h[0]+h[1])*d[0]-h[0]*d[1])/(h[0]+h[1]), 0.0, 3*d[0]);
            pressure_slopes_[n-1] = std::clamp(((2*h[n-2]+h[n-3])*d[n-2]-h[n-2]*d[n-3])/(h[n-2]+h[n-3]), 0.0, 3*d[n-2]);
            for (size_t i = 1; i + 1 < n; ++i) {
                const double w1 = 2*h[i]+h[i-1], w2 = h[i]+2*h[i-1];
                pressure_slopes_[i] = d[i-1] <= 0 || d[i] <= 0 ? 0 : (w1+w2)/(w1/d[i-1]+w2/d[i]);
            }
        }
    }
    seed(mapping_.target.x + (mapping_.target.width - 1) / 2,
         mapping_.target.y + (mapping_.target.height - 1) / 2);
}

void Mapper::reset() { tracking_ = false; }

void Mapper::seed(double x, double y) {
    cursor_x_ = std::clamp(x, mapping_.target.x, mapping_.target.x + mapping_.target.width - 1);
    cursor_y_ = std::clamp(y, mapping_.target.y, mapping_.target.y + mapping_.target.height - 1);
}

MappedSample Mapper::map(const Sample& sample) {
    validate_sample(sample);
    double x = std::clamp((sample.x - mapping_.source.x) / mapping_.source.width, 0.0, 1.0);
    double y = std::clamp((sample.y - mapping_.source.y) / mapping_.source.height, 0.0, 1.0);
    double tx = sample.tilt_x, ty = sample.tilt_y;
    switch (mapping_.rotation) {
    case 90: { const double t = x; x = 1 - y; y = t; const double tt = tx; tx = -ty; ty = tt; break; }
    case 180: x = 1 - x; y = 1 - y; tx = -tx; ty = -ty; break;
    case 270: { const double t = x; x = y; y = 1 - t; const double tt = tx; tx = ty; ty = -tt; break; }
    default: break;
    }
    if (mapping_.relative) {
        if (tracking_) {
            cursor_x_ += (x - last_x_) * (mapping_.target.width - 1) * mapping_.sensitivity;
            cursor_y_ += (y - last_y_) * (mapping_.target.height - 1) * mapping_.sensitivity;
            seed(cursor_x_, cursor_y_);
        }
    } else {
        cursor_x_ = mapping_.target.x + x * (mapping_.target.width - 1);
        cursor_y_ = mapping_.target.y + y * (mapping_.target.height - 1);
    }
    last_x_ = x; last_y_ = y;
    tracking_ = sample.phase != Phase::exit && sample.phase != Phase::cancel;
    const bool contact = sample.phase == Phase::down || sample.phase == Phase::move;
    double pressure = std::pow(sample.pressure, mapping_.pressure_gamma);
    if (!mapping_.pressure_curve.empty()) {
        const auto& points = mapping_.pressure_curve;
        auto upper = std::upper_bound(points.begin(), points.end(), sample.pressure,
            [](double p, const auto& knot) { return p < knot.first; });
        if (upper == points.end()) pressure = 1;
        else {
            const auto& lower = *(upper - 1);
            pressure = lower.second + (upper->second - lower.second) *
                (sample.pressure - lower.first) / (upper->first - lower.first);
            if (mapping_.pressure_interpolation == "smooth") {
                const auto i = static_cast<size_t>(upper - points.begin() - 1);
                const double h = upper->first - lower.first, t = (sample.pressure - lower.first) / h;
                pressure = (2*t*t*t-3*t*t+1)*lower.second + (t*t*t-2*t*t+t)*h*pressure_slopes_[i] +
                    (-2*t*t*t+3*t*t)*upper->second + (t*t*t-t*t)*h*pressure_slopes_[i+1];
                pressure = std::clamp(pressure, lower.second, upper->second);
            }
        }
    }
    auto quantize = [](double value, double origin, double span, unsigned steps) {
        if (!steps || span <= 0) return value;
        return origin + std::round((value - origin) / span * (steps - 1)) / (steps - 1) * span;
    };
    // Keep the internal relative cursor continuous so small intentional motions accumulate.
    auto source = sample;
    source.tilt = source.tilt && mapping_.tilt_enabled;
    tx = source.tilt ? std::clamp(tx * mapping_.tilt_scale, -90.0, 90.0) : 0;
    ty = source.tilt ? std::clamp(ty * mapping_.tilt_scale, -90.0, 90.0) : 0;
    return {source,
        quantize(cursor_x_, mapping_.target.x, mapping_.target.width - 1, mapping_.position_steps_x),
        quantize(cursor_y_, mapping_.target.y, mapping_.target.height - 1, mapping_.position_steps_y),
        contact ? pressure : 0, tx, ty};
}

void validate_sample(const Sample& s) {
    if (static_cast<unsigned>(s.phase) > static_cast<unsigned>(Phase::exit) ||
        !std::isfinite(s.x) || !std::isfinite(s.y) ||
        !std::isfinite(s.pressure) || !std::isfinite(s.tilt_x) ||
        !std::isfinite(s.tilt_y) || !std::isfinite(s.rotation) || !std::isfinite(s.distance) ||
        s.x < 0 || s.x > 1 || s.y < 0 || s.y > 1 ||
        s.pressure < 0 || s.pressure > 1 || std::abs(s.tilt_x) > 90 ||
        std::abs(s.tilt_y) > 90 || s.rotation < 0 || s.rotation >= 360 ||
        s.distance < 0 || s.distance > 1)
        throw std::invalid_argument("invalid pen sample");
}

bool constant_time_equal(const std::string& a, const std::string& b) {
    const size_t n = std::max(a.size(), b.size());
    size_t different = a.size() ^ b.size();
    for (size_t i = 0; i < n; ++i)
        different |= static_cast<unsigned char>(i < a.size() ? a[i] : 0) ^
                     static_cast<unsigned char>(i < b.size() ? b[i] : 0);
    return different == 0;
}

std::vector<uint8_t> frame(const std::vector<uint8_t>& payload) {
    if (payload.size() < 2 || payload.size() > 4096) throw std::invalid_argument("invalid frame size");
    const auto n = static_cast<uint32_t>(payload.size());
    std::vector<uint8_t> result{static_cast<uint8_t>(n >> 24), static_cast<uint8_t>(n >> 16),
                              static_cast<uint8_t>(n >> 8), static_cast<uint8_t>(n)};
    result.insert(result.end(), payload.begin(), payload.end());
    return result;
}

} // namespace reflection
