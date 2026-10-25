#include "reflection/protocol.hpp"
#include <cstring>

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
