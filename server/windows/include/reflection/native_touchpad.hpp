#pragma once
#include "reflection/windows.hpp"
#include "reflection/raw_touchpad.hpp"

namespace reflection {

bool native_touchpad_api_available();
std::vector<POINTER_TYPE_INFO> native_touchpad_frames(const std::vector<NativeTouchpadContact>& contacts,
                                                     RawTouchpadSize size, DWORD time, bool cancel = false);

class NativeTouchpadBackend {
public:
    NativeTouchpadBackend();
    ~NativeTouchpadBackend();
    bool enable(RawTouchpadSize size, bool dry_run);
    const std::string& unavailable_reason() const;
    void submit(const RawTouchpadFrame& frame);
    void release() noexcept;
    NativeTouchpadBackend(const NativeTouchpadBackend&) = delete;
    NativeTouchpadBackend& operator=(const NativeTouchpadBackend&) = delete;
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
} // namespace reflection
