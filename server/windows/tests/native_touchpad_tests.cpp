#include "reflection/native_touchpad.hpp"
#include <iostream>

using namespace reflection;

int main(int argc, char** argv) {
    try {
        const RawTouchpadSize size{10000, 6000};
        if (argc == 2 && std::string(argv[1]) == "--probe-device") {
            const bool available = native_touchpad_api_available();
            bool created = false;
            std::string reason;
            {
                NativeTouchpadBackend probe;
                created = probe.enable(size, false);
                reason = probe.unavailable_reason();
            }
            std::cout << "{\"status\":" << json_escape(created ? "pass" : available ? "fail" : "unavailable")
                << ",\"apiAvailable\":" << (available ? "true" : "false")
                << ",\"deviceCreated\":" << (created ? "true" : "false")
                << ",\"deviceReleased\":" << (created ? "true" : "false")
                << ",\"contactsInjected\":false,\"nativeGesturesVerified\":false,\"reason\":" << json_escape(reason) << "}\n";
            return created ? 0 : available ? 1 : 3;
        }
        if (argc != 1) throw std::invalid_argument("usage: reflection_native_touchpad_tests [--probe-device]");
        const NativeTouchpadContact contact{1, {42, RawTouchpadPhase::down, true, .25, .75}};
        auto frames = native_touchpad_frames({contact}, size, 1);
        const auto& pointer = frames[0].touchInfo.pointerInfo;
        if (frames.size() != 1 || frames[0].type != PT_TOUCHPAD || pointer.pointerType != PT_TOUCHPAD ||
            pointer.pointerId != 1 || pointer.ptHimetricLocation.x != 2500 || pointer.ptHimetricLocation.y != 4500 ||
            pointer.pointerFlags != (POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_CONFIDENCE) ||
            pointer.dwTime != 1 || frames[0].touchInfo.touchMask != 0)
            throw std::runtime_error("native touchpad contact frame");
        auto up = contact; up.source.phase = RawTouchpadPhase::up;
        frames = native_touchpad_frames({up}, size, 2);
        if (frames[0].touchInfo.pointerInfo.pointerFlags != POINTER_FLAG_CONFIDENCE)
            throw std::runtime_error("native touchpad terminal contact");
        frames = native_touchpad_frames({contact}, size, 3, true);
        if (frames[0].touchInfo.pointerInfo.pointerFlags != 0)
            throw std::runtime_error("native touchpad cancelled confidence");
        NativeTouchpadBackend dry_run;
        if (dry_run.enable(size, true) || dry_run.unavailable_reason().find("dry-run") == std::string::npos)
            throw std::runtime_error("dry-run must never create a native input device");
        std::cout << "Windows native touchpad frame and dry-run checks passed (no input injected)\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n'; return 1;
    }
}
