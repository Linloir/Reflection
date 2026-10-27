#pragma once
#include "reflection/raw_touchpad.hpp"
#include "reflection/config.hpp"
#include <functional>
#include <memory>
#include <mutex>

namespace reflection {

struct Display {
    std::string name;
    Rect bounds;
    bool primary;
};

// One mapping state shared by the input session, local control, and tablet control.
// A pending display switch is committed only after the current stroke is released.
class DisplayMapping {
public:
    using Provider = std::function<std::vector<Display>()>;
    DisplayMapping(Config config, Provider provider);
    void refresh();
    void request(unsigned action, const std::string& display = "");
    MappedSample map(const Sample& sample);
    void release();
    void seed(double x, double y);
    std::string status(uint64_t response_revision = 0) const;
    uint64_t revision() const;
    std::string selected_display() const;
private:
    void refresh_locked();
    void apply_locked();
    Config desired_;
    Provider provider_;
    std::vector<Display> displays_;
    std::vector<Display> active_displays_;
    std::unique_ptr<Mapper> mapper_;
    mutable std::mutex mutex_;
    Rect target_;
    std::string mode_, selected_;
    bool touching_ = false, pending_ = false, fallback_ = false;
    uint64_t revision_ = 1;
    double cursor_x_ = 0, cursor_y_ = 0;
};

bool mapping_capability(const std::vector<uint8_t>& payload);
bool stabilization_capability(const std::vector<uint8_t>& payload);
bool touchpad_capability(const std::vector<uint8_t>& payload);
std::optional<RawTouchpadSize> raw_touchpad_capability(const std::vector<uint8_t>& payload);
bool tablet_calibration_capability(const std::vector<uint8_t>& payload);
struct MappingRequest { uint64_t sequence; unsigned action; std::string display; };
MappingRequest decode_mapping_request(const std::vector<uint8_t>& payload, uint64_t previous_sequence);

} // namespace reflection
