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
    if (!std::isfinite(sensitivity) || sensitivity <= 0 || sensitivity > 10)
        throw std::invalid_argument("sensitivity must be in (0,10]");
}

Mapper::Mapper(Mapping mapping) : mapping_(mapping) {
    mapping_.validate();
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
    return {sample, cursor_x_, cursor_y_, contact ? std::pow(sample.pressure, mapping_.pressure_gamma) : 0, tx, ty};
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
