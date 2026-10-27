#include "reflection/windows.hpp"
#include <cstring>
#include <chrono>
#include <limits>
#include <sstream>
#include <thread>

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
    explicit PenBackend(bool show_cursor) {
        if (!api_.available()) throw std::runtime_error("pen injection requires Windows 10 version 1809 or later");
        // DEFAULT may hide hover feedback according to the user's Windows pen settings.
        // INDIRECT supplies native pen feedback without synthesizing mouse events.
        device_ = api_.create(PT_PEN, 1, show_cursor ? POINTER_FEEDBACK_INDIRECT : POINTER_FEEDBACK_DEFAULT);
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
        const auto bounds=virtual_desktop();
        const auto info=synthetic_pen_frame(s,bounds,in_range_?&last_:nullptr);
        // Android can repeat an unchanged sensor report at a later timestamp.
        // Coalesce only identical UPDATEs, keeping a bounded stationary heartbeat.
        // Pressure, buttons, tilt, DOWN/UP, and range transitions are never lost.
        const auto now=std::chrono::steady_clock::now();
        if(in_range_ && identical_pen_update(info,last_) && now-last_injected_<std::chrono::milliseconds(8)) return;
        inject(info, s.source);
        last_injected_=std::chrono::steady_clock::now();
        last_real_success_=last_injected_; last_native_keepalive_=false;
        last_source_time_=s.source.timestamp_ns;
        last_bounds_=bounds;
        last_ = info;
        if (s.source.phase != Phase::up) { last_desktop_x_ = s.x; last_desktop_y_ = s.y; }
        last_phase_ = s.source.phase;
        contact_ = contact;
        in_range_ = true;
        eraser_ = s.source.eraser;
    }

    void tick() override {
        if(!in_range_) return;
        const auto bounds=virtual_desktop();
        // Never replay an old virtual-origin position into a changed layout.
        const auto elapsed=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-last_injected_).count();
        if(!synthetic_pen_refresh_due(in_range_,elapsed,last_bounds_,bounds)) return;
        // Static devices may stop reporting while the authenticated connection is
        // still live. Refresh only the existing OS state; never invent contact.
        const auto info=synthetic_pen_repeat(last_,contact_);
        Sample source; source.phase=contact_?Phase::move:Phase::hover; source.timestamp_ns=last_source_time_;
        inject(info,source,true);
        last_=info; last_native_keepalive_=true; last_injected_=std::chrono::steady_clock::now();
    }

    void release() noexcept override {
        if (!in_range_) return;
        auto info = synthetic_pen_release(last_,contact_);
        // Preserve the last successfully injected position for the terminal contact
        // frame, including during topology changes. Rebase only the subsequent exit.
        if (contact_) {
            inject_retry(info);
        }
        info=synthetic_pen_release(info,false);
        try { info.penInfo.pointerInfo.ptPixelLocation = synthetic_pointer_position(last_desktop_x_, last_desktop_y_, virtual_desktop()); }
        catch (const std::exception&) {}
        inject_retry(info);
        contact_ = false;
        in_range_ = false;
    }
private:
    DWORD inject_retry(const POINTER_TYPE_INFO& info) noexcept {
        for (unsigned attempt=0; attempt<4; ++attempt) {
            // A TCP batch can put valid DOWN/UP transitions in the same native
            // timestamp slot. Windows may reject these with ERROR_INVALID_PARAMETER
            // (not just ERROR_NOT_READY), which also cancels the active contact.
            // Pace before injection; retrying that error cannot restore a lost stroke.
            const auto earliest=last_api_success_+std::chrono::microseconds(100);
            while(std::chrono::steady_clock::now()<earliest) std::this_thread::yield();
            if (api_.inject(device_, &info, 1)) {
                last_api_success_=std::chrono::steady_clock::now();
                return ERROR_SUCCESS;
            }
            const DWORD error=GetLastError();
            if(error!=ERROR_NOT_READY || attempt==3) return error;
            // Re-submit the exact frame; advancing past it would lose DOWN/UP ordering.
            Sleep(1);
        }
        return ERROR_NOT_READY;
    }
    void inject(const POINTER_TYPE_INFO& info, const Sample& source, bool keepalive=false) {
        const auto elapsed=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-last_injected_).count();
        const auto error=inject_retry(info);
        if(error) {
            const auto& p=info.penInfo.pointerInfo; const auto& last=last_.penInfo.pointerInfo;
            std::ostringstream message;
            message<<"InjectSyntheticPointerInput failed: "<<error<<" phase="<<unsigned(source.phase)
                <<" origin="<<(keepalive?"keepalive":"sample")
                <<" sequence="<<source.sequence<<" source_time_ns="<<source.timestamp_ns
                <<" source_dt_ns="<<(source.timestamp_ns>=last_source_time_?source.timestamp_ns-last_source_time_:0)
                <<" since_success_us="<<(in_range_?elapsed:-1)
                <<" since_real_success_us="<<(in_range_?std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-last_real_success_).count():-1)
                <<" previous_source_phase="<<unsigned(last_phase_)<<" previous_origin="<<(last_native_keepalive_?"keepalive":"sample")
                <<" xy="<<p.ptPixelLocation.x<<','<<p.ptPixelLocation.y
                <<" previous_xy="<<last.ptPixelLocation.x<<','<<last.ptPixelLocation.y
                <<" flags="<<p.pointerFlags<<" previous_flags="<<last.pointerFlags
                <<" pen_flags="<<info.penInfo.penFlags<<" previous_pen_flags="<<last_.penInfo.penFlags
                <<" mask="<<info.penInfo.penMask<<" previous_mask="<<last_.penInfo.penMask
                <<" pressure="<<info.penInfo.pressure<<" previous_pressure="<<last_.penInfo.pressure
                <<" tilt="<<info.penInfo.tiltX<<','<<info.penInfo.tiltY
                <<" previous_tilt="<<last_.penInfo.tiltX<<','<<last_.penInfo.tiltY
                <<" contact="<<contact_<<" in_range="<<in_range_;
            throw std::runtime_error(message.str());
        }
    }
    PenApi api_;
    HANDLE device_ = nullptr;
    POINTER_TYPE_INFO last_{};
    double last_desktop_x_ = 0, last_desktop_y_ = 0;
    bool in_range_ = false, contact_ = false, eraser_ = false;
    Phase last_phase_ = Phase::exit;
    std::chrono::steady_clock::time_point last_injected_{};
    std::chrono::steady_clock::time_point last_api_success_{};
    std::chrono::steady_clock::time_point last_real_success_{};
    bool last_native_keepalive_=false;
    uint64_t last_source_time_=0;
    Rect last_bounds_{};
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

POINT synthetic_pointer_position(double desktop_x, double desktop_y, const Rect& bounds) {
    if (!std::isfinite(desktop_x) || !std::isfinite(desktop_y) ||
        !std::isfinite(bounds.x) || !std::isfinite(bounds.y) ||
        !std::isfinite(bounds.width) || !std::isfinite(bounds.height) ||
        bounds.width < 1 || bounds.height < 1 ||
        bounds.width > std::numeric_limits<LONG>::max() || bounds.height > std::numeric_limits<LONG>::max())
        throw std::invalid_argument("invalid synthetic pointer desktop geometry");
    // InjectSyntheticPointerInput explicitly requires coordinates relative to the
    // virtual screen's top-left. GetMonitorInfo and Mapper instead use primary-origin
    // signed desktop coordinates. Convert only here; status/UI keep the OS layout.
    // https://learn.microsoft.com/windows/win32/api/winuser/nf-winuser-injectsyntheticpointerinput
    // Clamp for display removal or a custom area partly outside the current desktop.
    const auto x = std::clamp(desktop_x - bounds.x, 0.0, bounds.width - 1);
    const auto y = std::clamp(desktop_y - bounds.y, 0.0, bounds.height - 1);
    return {static_cast<LONG>(std::llround(x)), static_cast<LONG>(std::llround(y))};
}

POINTER_TYPE_INFO synthetic_pen_frame(const MappedSample& s,const Rect& bounds,const POINTER_TYPE_INFO* previous) {
    POINTER_TYPE_INFO info{}; info.type=PT_PEN;
    auto& pen=info.penInfo;
    const bool contact=s.source.phase==Phase::down || s.source.phase==Phase::move;
    pen.pointerInfo.pointerType=PT_PEN; pen.pointerInfo.pointerId=1;
    pen.pointerInfo.ptPixelLocation=synthetic_pointer_position(s.x,s.y,bounds);
    // Preserve the last successful API position, even when release coordinates or
    // desktop topology changed. UP cannot introduce motion (touch API error 87).
    if(s.source.phase==Phase::up && previous) pen.pointerInfo.ptPixelLocation=previous->penInfo.pointerInfo.ptPixelLocation;
    pen.pointerInfo.pointerFlags=POINTER_FLAG_PRIMARY|POINTER_FLAG_INRANGE;
    if(!previous) pen.pointerInfo.pointerFlags|=POINTER_FLAG_NEW;
    if(s.source.phase==Phase::down) pen.pointerInfo.pointerFlags|=POINTER_FLAG_DOWN;
    else if(s.source.phase==Phase::up) pen.pointerInfo.pointerFlags|=POINTER_FLAG_UP;
    else pen.pointerInfo.pointerFlags|=POINTER_FLAG_UPDATE;
    if(contact) pen.pointerInfo.pointerFlags|=POINTER_FLAG_INCONTACT|(s.source.barrel?POINTER_FLAG_SECONDBUTTON:POINTER_FLAG_FIRSTBUTTON);
    // Inversion identifies the tool; ERASER is its active contact switch. Keeping
    // ERASER set during hover/UP incorrectly advertises an erase action without
    // contact. Windows' pen reporting rules also require the first cleared erase
    // frame to keep the last contact position, as the UP path above does.
    // https://learn.microsoft.com/windows-hardware/design/component-guidelines/required-hid-top-level-collections#eraser
    if(s.source.eraser) {
        pen.penFlags|=PEN_FLAG_INVERTED;
        if(contact) pen.penFlags|=PEN_FLAG_ERASER;
    }
    if(s.source.barrel) pen.penFlags|=PEN_FLAG_BARREL;
    pen.penMask=PEN_MASK_PRESSURE;
    if(s.source.tilt) pen.penMask|=PEN_MASK_TILT_X|PEN_MASK_TILT_Y;
    pen.pressure=contact?static_cast<UINT32>(std::llround(std::clamp(s.pressure,0.0,1.0)*1024)):0;
    pen.tiltX=static_cast<INT32>(std::llround(s.tilt_x)); pen.tiltY=static_cast<INT32>(std::llround(s.tilt_y));
    return info;
}

bool identical_pen_update(const POINTER_TYPE_INFO& next,const POINTER_TYPE_INFO& previous) {
    const auto& a=next.penInfo; const auto& b=previous.penInfo;
    const auto flags=a.pointerInfo.pointerFlags;
    return next.type==PT_PEN && previous.type==PT_PEN && (flags&POINTER_FLAG_UPDATE) &&
        !(flags&(POINTER_FLAG_NEW|POINTER_FLAG_DOWN|POINTER_FLAG_UP|POINTER_FLAG_CANCELED)) &&
        flags==b.pointerInfo.pointerFlags && a.pointerInfo.pointerId==b.pointerInfo.pointerId &&
        a.pointerInfo.ptPixelLocation.x==b.pointerInfo.ptPixelLocation.x &&
        a.pointerInfo.ptPixelLocation.y==b.pointerInfo.ptPixelLocation.y &&
        a.penFlags==b.penFlags && a.penMask==b.penMask && a.pressure==b.pressure &&
        a.rotation==b.rotation && a.tiltX==b.tiltX && a.tiltY==b.tiltY;
}

POINTER_TYPE_INFO synthetic_pen_repeat(const POINTER_TYPE_INFO& previous,bool contact) {
    auto info=previous;
    auto& pen=info.penInfo;
    pen.pointerInfo.pointerFlags=POINTER_FLAG_PRIMARY|POINTER_FLAG_INRANGE|POINTER_FLAG_UPDATE;
    pen.penFlags&=~PEN_FLAG_ERASER;
    if(contact && (pen.penFlags&PEN_FLAG_INVERTED)) pen.penFlags|=PEN_FLAG_ERASER;
    if(contact) pen.pointerInfo.pointerFlags|=POINTER_FLAG_INCONTACT|
        ((pen.penFlags&PEN_FLAG_BARREL)?POINTER_FLAG_SECONDBUTTON:POINTER_FLAG_FIRSTBUTTON);
    else pen.pressure=0;
    return info;
}

POINTER_TYPE_INFO synthetic_pen_release(const POINTER_TYPE_INFO& previous,bool contact) {
    auto info=previous;
    auto& pen=info.penInfo;
    pen.pressure=0;
    // Preserve inverted identity only while the canceled UP is still in range.
    // Both terminal frames clear the erase switch and all pressed buttons.
    pen.penFlags=contact?(pen.penFlags&PEN_FLAG_INVERTED):PEN_FLAG_NONE;
    pen.pointerInfo.pointerFlags=contact?
        POINTER_FLAG_PRIMARY|POINTER_FLAG_INRANGE|POINTER_FLAG_UP|POINTER_FLAG_CANCELED:
        POINTER_FLAG_UPDATE;
    return info;
}

bool synthetic_pen_refresh_due(bool in_range,int64_t elapsed_us,const Rect& previous,const Rect& current) {
    return in_range && elapsed_us>=50000 && previous.x==current.x && previous.y==current.y &&
        previous.width==current.width && previous.height==current.height;
}

bool pen_api_available() { return PenApi{}.available(); }
std::unique_ptr<InputBackend> make_pen_backend(bool dry_run, bool show_cursor) {
    if (dry_run) return std::make_unique<DryRunBackend>();
    return std::make_unique<PenBackend>(show_cursor);
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
