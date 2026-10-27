#pragma once
#include "reflection/core.hpp"
#include <cstddef>

namespace reflection {

constexpr uint8_t protocol_version = 1;
constexpr size_t maximum_body = 4096;
constexpr size_t pen_payload_size = 48;
constexpr size_t authentication_tag_size = 32;

uint32_t read_u32(const uint8_t* bytes);
uint64_t read_u64(const uint8_t* bytes);
Sample decode_pen(const std::vector<uint8_t>& payload);
struct TabletNoiseCalibration { uint64_t sequence; double noise; };
TabletNoiseCalibration decode_tablet_calibration(const std::vector<uint8_t>& payload, uint64_t previous);
std::vector<uint8_t> tablet_calibration_ack(uint64_t sequence, double noise);

enum class TouchpadKind : uint8_t { move, left_tap, right_tap, scroll_begin, scroll, scroll_end, zoom, swipe, cancel };
struct TouchpadEvent {
    uint64_t sequence = 0, timestamp_ns = 0;
    double x = 0, y = 0;
    TouchpadKind kind = TouchpadKind::move;
    unsigned fingers = 0;
};
TouchpadEvent decode_touchpad(const std::vector<uint8_t>& payload);
class TouchpadSequenceGuard {
public:
    void accept(const TouchpadEvent& event);
private:
    uint64_t sequence_ = 0, timestamp_ = 0;
};

class SequenceGuard {
public:
    void accept(const Sample& sample);
    void accept_ping(uint64_t value);
private:
    uint64_t sequence_ = 0, timestamp_ = 0, ping_ = 0;
    bool ping_seen_ = false;
};

class ContactGuard {
public:
    void accept(const Sample& sample);
private:
    bool contact_ = false;
    bool eraser_ = false;
};

} // namespace reflection
