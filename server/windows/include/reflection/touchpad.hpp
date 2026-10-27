#pragma once
#include "reflection/protocol.hpp"

namespace reflection {

class TouchpadBackend {
public:
    explicit TouchpadBackend(bool dry_run) : dry_run_(dry_run) {}
    void submit(const TouchpadEvent& event);
    void release();
private:
    bool dry_run_;
    double motion_x_ = 0, motion_y_ = 0, wheel_x_ = 0, wheel_y_ = 0, zoom_ = 0;
};

} // namespace reflection
