#include "reflection/windows.hpp"
#include <cstring>

namespace reflection {
namespace {

using CreatePen = HANDLE(WINAPI*)(POINTER_INPUT_TYPE, ULONG, POINTER_FEEDBACK_MODE);
using InjectPen = BOOL(WINAPI*)(HANDLE, const POINTER_TYPE_INFO*, UINT32);
using DestroyPen = void(WINAPI*)(HANDLE);

template<typename T> T resolve(HMODULE module, const char* name) {
    const auto address = GetProcAddress(module, name);
    T result = nullptr;
    static_assert(sizeof(result) == sizeof(address), "Windows procedure pointer size mismatch");
    std::memcpy(&result, &address, sizeof(result));
    return result;
}

struct PenApi {
    CreatePen create;
    InjectPen inject;
    DestroyPen destroy;
    PenApi() {
        const auto user = GetModuleHandleW(L"user32.dll");
        create = resolve<CreatePen>(user, "CreateSyntheticPointerDevice");
        inject = resolve<InjectPen>(user, "InjectSyntheticPointerInput");
        destroy = resolve<DestroyPen>(user, "DestroySyntheticPointerDevice");
    }
    bool available() const { return create && inject && destroy; }
};

class PenBackend final : public InputBackend {
public:
    PenBackend() {
        if (!api_.available()) throw std::runtime_error("pen injection requires Windows 10 version 1809 or later");
        device_ = api_.create(PT_PEN, 1, POINTER_FEEDBACK_DEFAULT);
        if (!device_) throw std::runtime_error("CreateSyntheticPointerDevice failed: " + std::to_string(GetLastError()));
    }
    ~PenBackend() override { release(); api_.destroy(device_); }

    void submit(const MappedSample& s) override {
        if (s.source.phase == Phase::cancel || s.source.phase == Phase::exit) { release(); return; }
        const bool contact = s.source.phase == Phase::down || s.source.phase == Phase::move;
        if (s.source.phase == Phase::down && contact_) throw std::runtime_error("duplicate pen down");
        if (s.source.phase == Phase::move && !contact_) throw std::runtime_error("pen move without down");
        if (s.source.phase == Phase::up && !contact_) throw std::runtime_error("pen up without down");
        if (s.source.phase == Phase::hover && contact_) throw std::runtime_error("hover without pen up");
        // Tool changes require the old pointer to leave range first.
        if (in_range_ && eraser_ != s.source.eraser) {
            if (contact_) throw std::runtime_error("tool changed during contact");
            release();
        }
        POINTER_TYPE_INFO info{};
        info.type = PT_PEN;
        auto& pen = info.penInfo;
        pen.pointerInfo.pointerType = PT_PEN;
        pen.pointerInfo.pointerId = 1;
        pen.pointerInfo.ptPixelLocation = {static_cast<LONG>(std::llround(s.x)), static_cast<LONG>(std::llround(s.y))};
        pen.pointerInfo.pointerFlags = POINTER_FLAG_PRIMARY | POINTER_FLAG_INRANGE;
        if (!in_range_) pen.pointerInfo.pointerFlags |= POINTER_FLAG_NEW;
        if (s.source.phase == Phase::down) pen.pointerInfo.pointerFlags |= POINTER_FLAG_DOWN;
        else if (s.source.phase == Phase::up) pen.pointerInfo.pointerFlags |= POINTER_FLAG_UP;
        else pen.pointerInfo.pointerFlags |= POINTER_FLAG_UPDATE;
        if (contact) pen.pointerInfo.pointerFlags |= POINTER_FLAG_INCONTACT | POINTER_FLAG_FIRSTBUTTON;
        if (s.source.barrel) pen.pointerInfo.pointerFlags |= POINTER_FLAG_SECONDBUTTON;
        if (s.source.eraser) pen.penFlags |= PEN_FLAG_INVERTED | PEN_FLAG_ERASER;
        if (s.source.barrel) pen.penFlags |= PEN_FLAG_BARREL;
        pen.penMask = PEN_MASK_PRESSURE;
        if (s.source.tilt) pen.penMask |= PEN_MASK_TILT_X | PEN_MASK_TILT_Y;
        pen.pressure = static_cast<UINT32>(std::llround(s.pressure * 1024));
        pen.tiltX = static_cast<INT32>(std::llround(s.tilt_x));
        pen.tiltY = static_cast<INT32>(std::llround(s.tilt_y));
        inject(info);
        last_ = info;
        contact_ = contact;
        in_range_ = true;
        eraser_ = s.source.eraser;
    }

    void release() noexcept override {
        if (!in_range_) return;
        auto info = last_;
        info.penInfo.pressure = 0;
        info.penInfo.penFlags = eraser_ ? PEN_FLAG_INVERTED | PEN_FLAG_ERASER : PEN_FLAG_NONE;
        if (contact_) {
            info.penInfo.pointerInfo.pointerFlags = POINTER_FLAG_PRIMARY | POINTER_FLAG_INRANGE |
                POINTER_FLAG_UP | POINTER_FLAG_CANCELED;
            api_.inject(device_, &info, 1);
        }
        info.penInfo.pointerInfo.pointerFlags = POINTER_FLAG_UPDATE;
        api_.inject(device_, &info, 1);
        contact_ = false;
        in_range_ = false;
    }
private:
    void inject(const POINTER_TYPE_INFO& info) {
        if (!api_.inject(device_, &info, 1))
            throw std::runtime_error("InjectSyntheticPointerInput failed: " + std::to_string(GetLastError()));
    }
    PenApi api_;
    HANDLE device_ = nullptr;
    POINTER_TYPE_INFO last_{};
    bool in_range_ = false, contact_ = false, eraser_ = false;
};

class DryRunBackend final : public InputBackend {
public:
    void submit(const MappedSample&) override {}
    void release() noexcept override {}
};

BOOL CALLBACK monitor_callback(HMONITOR monitor, HDC, LPRECT, LPARAM pointer) {
    MONITORINFOEXW info{};
    info.cbSize = sizeof(info);
    if (!GetMonitorInfoW(monitor, &info)) return TRUE;
    char name[128]{};
    WideCharToMultiByte(CP_UTF8, 0, info.szDevice, -1, name, sizeof(name), nullptr, nullptr);
    const auto& r = info.rcMonitor;
    reinterpret_cast<std::vector<Display>*>(pointer)->push_back(
        {name, {double(r.left), double(r.top), double(r.right - r.left), double(r.bottom - r.top)},
         (info.dwFlags & MONITORINFOF_PRIMARY) != 0});
    return TRUE;
}

} // namespace

bool pen_api_available() { return PenApi{}.available(); }
std::unique_ptr<InputBackend> make_pen_backend(bool dry_run) {
    if (dry_run) return std::make_unique<DryRunBackend>();
    return std::make_unique<PenBackend>();
}
std::vector<Display> displays() {
    std::vector<Display> result;
    if (!EnumDisplayMonitors(nullptr, nullptr, monitor_callback, reinterpret_cast<LPARAM>(&result)))
        throw std::runtime_error("EnumDisplayMonitors failed");
    return result;
}
Rect virtual_desktop() {
    return {double(GetSystemMetrics(SM_XVIRTUALSCREEN)), double(GetSystemMetrics(SM_YVIRTUALSCREEN)),
            double(GetSystemMetrics(SM_CXVIRTUALSCREEN)), double(GetSystemMetrics(SM_CYVIRTUALSCREEN))};
}

} // namespace reflection
