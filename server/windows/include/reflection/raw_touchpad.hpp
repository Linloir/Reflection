#pragma once
#include "reflection/protocol.hpp"
#include <map>

namespace reflection {

struct RawTouchpadSize { uint32_t width = 0, height = 0; };
enum class RawTouchpadPhase : uint8_t { down = 1, update = 2, up = 3 };
struct RawTouchpadContact {
    uint32_t id = 0;
    RawTouchpadPhase phase = RawTouchpadPhase::down;
    bool confident = true;
    double x = 0, y = 0;
};
struct RawTouchpadFrame {
    uint64_t sequence = 0, timestamp_ns = 0;
    bool cancel = false;
    std::vector<RawTouchpadContact> contacts;
};
struct NativeTouchpadContact {
    uint32_t slot = 0;
    RawTouchpadContact source;
};
RawTouchpadFrame decode_raw_touchpad(const std::vector<uint8_t>& payload);
std::vector<uint8_t> raw_touchpad_ack(RawTouchpadSize size);

class RawTouchpadState {
public:
    std::vector<NativeTouchpadContact> accept(const RawTouchpadFrame& frame);
    std::vector<NativeTouchpadContact> active() const;
    void cancel() noexcept { active_.clear(); }
private:
    uint64_t sequence_ = 0, timestamp_ = 0;
    std::map<uint32_t, NativeTouchpadContact> active_;
};

} // namespace reflection
