#include "reflection/protocol.hpp"
#include <cstring>
#include <cmath>

namespace reflection {

uint32_t read_u32(const uint8_t* p) {
    return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | uint32_t(p[3]);
}

uint64_t read_u64(const uint8_t* p) {
    return (uint64_t(read_u32(p)) << 32) | read_u32(p + 4);
}

static float read_float(const uint8_t* p) {
    const auto bits = read_u32(p);
    float value;
    static_assert(sizeof(value) == sizeof(bits), "32-bit floats required");
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

TabletNoiseCalibration decode_tablet_calibration(const std::vector<uint8_t>& p, uint64_t previous) {
    if (p.size() != 12) throw std::invalid_argument("invalid tablet calibration length");
    const auto sequence = read_u64(p.data());
    const auto noise = read_float(p.data() + 8);
    if (sequence <= previous || (previous == 0 && sequence != 1) || !std::isfinite(noise) ||
        (noise != 0 && (noise < .00001f || noise > .02f)))
        throw std::invalid_argument("invalid or replayed tablet calibration");
    return {sequence, noise == 0 ? 0 : std::max(.00001, std::min(.02, double(noise)))};
}

std::vector<uint8_t> tablet_calibration_ack(uint64_t sequence, double noise) {
    std::vector<uint8_t> result{1};
    for (int shift = 56; shift >= 0; shift -= 8) result.push_back(static_cast<uint8_t>(sequence >> shift));
    const float value = static_cast<float>(noise);
    uint32_t bits; std::memcpy(&bits, &value, sizeof(bits));
    for (int shift = 24; shift >= 0; shift -= 8) result.push_back(static_cast<uint8_t>(bits >> shift));
    return result;
}

Sample decode_pen(const std::vector<uint8_t>& p) {
    if (p.size() != pen_payload_size) throw std::invalid_argument("pen payload must be 48 bytes");
    if ((p[45] != 1 && p[45] != 2) || p[46] || p[47] || (read_u32(p.data() + 40) & ~3u))
        throw std::invalid_argument("unsupported tool, button or reserved bits");
    Sample s;
    s.sequence = read_u64(p.data());
    s.timestamp_ns = read_u64(p.data() + 8);
    s.x = read_float(p.data() + 16);
    s.y = read_float(p.data() + 20);
    s.pressure = read_float(p.data() + 24);
    s.tilt_x = read_float(p.data() + 28);
    s.tilt_y = read_float(p.data() + 32);
    s.distance = read_float(p.data() + 36);
    const auto buttons = read_u32(p.data() + 40);
    s.barrel = (buttons & 1) != 0;
    s.secondary = (buttons & 2) != 0;
    s.tilt = true;
    s.phase = static_cast<Phase>(p[44]);
    s.eraser = p[45] == 2;
    validate_sample(s);
    return s;
}

TouchpadEvent decode_touchpad(const std::vector<uint8_t>& p) {
    if (p.size() != 32 || p[24] > 8 || p[25] > 4 ||
        p[26] || p[27] || p[28] || p[29] || p[30] || p[31])
        throw std::invalid_argument("invalid touchpad payload");
    TouchpadEvent e{read_u64(p.data()), read_u64(p.data() + 8), read_float(p.data() + 16),
        read_float(p.data() + 20), static_cast<TouchpadKind>(p[24]), p[25]};
    if (!std::isfinite(e.x) || !std::isfinite(e.y) || std::abs(e.x) > 1024 || std::abs(e.y) > 1024)
        throw std::invalid_argument("invalid touchpad delta");
    bool valid = false;
    switch (e.kind) {
    case TouchpadKind::move: valid = e.fingers == 1; break;
    case TouchpadKind::left_tap: valid = e.fingers == 1 && e.x == 0 && e.y == 0; break;
    case TouchpadKind::right_tap: case TouchpadKind::scroll_begin: case TouchpadKind::scroll_end:
        valid = e.fingers == 2 && e.x == 0 && e.y == 0; break;
    case TouchpadKind::scroll: valid = e.fingers == 2; break;
    case TouchpadKind::zoom: valid = e.fingers == 2 && std::abs(e.x) <= 1 && e.y == 0; break;
    case TouchpadKind::swipe:
        valid = e.fingers >= 3 && std::abs(e.x) + std::abs(e.y) == 1 &&
            e.x == std::round(e.x) && e.y == std::round(e.y); break;
    case TouchpadKind::cancel: valid = e.fingers == 0 && e.x == 0 && e.y == 0; break;
    }
    if (!valid) throw std::invalid_argument("invalid touchpad gesture");
    return e;
}

void TouchpadSequenceGuard::accept(const TouchpadEvent& e) {
    if (e.sequence <= sequence_ || (sequence_ == 0 && e.sequence != 1) || e.timestamp_ns < timestamp_)
        throw std::invalid_argument("replayed or unordered touchpad event");
    sequence_ = e.sequence; timestamp_ = e.timestamp_ns;
}

void SequenceGuard::accept(const Sample& s) {
    if (s.sequence == 0 || s.sequence <= sequence_ ||
        (sequence_ == 0 && s.sequence != 1) || s.timestamp_ns < timestamp_)
        throw std::invalid_argument("replayed, unordered or invalid initial pen sequence");
    sequence_ = s.sequence;
    timestamp_ = s.timestamp_ns;
}

void SequenceGuard::accept_ping(uint64_t value) {
    if (ping_seen_ && value <= ping_) throw std::invalid_argument("replayed heartbeat");
    ping_ = value;
    ping_seen_ = true;
}

void ContactGuard::accept(const Sample& sample) {
    if (sample.phase == Phase::cancel || sample.phase == Phase::exit) {
        contact_ = false;
        return;
    }
    if (contact_ && sample.eraser != eraser_) throw std::invalid_argument("tool changed during contact");
    if (sample.phase == Phase::down && contact_) throw std::invalid_argument("duplicate pen down");
    if (sample.phase == Phase::move && !contact_) throw std::invalid_argument("pen move without down");
    if (sample.phase == Phase::up && !contact_) throw std::invalid_argument("pen up without down");
    if (sample.phase == Phase::hover && contact_) throw std::invalid_argument("hover without pen up");
    contact_ = sample.phase == Phase::down || sample.phase == Phase::move;
    eraser_ = sample.eraser;
}

} // namespace reflection
