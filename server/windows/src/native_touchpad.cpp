#include "reflection/native_touchpad.hpp"
#include <cstring>
#include <cstddef>
#include <limits>

namespace reflection {
namespace {
// Public SYNTHETIC_DEVICE_CREATION_PARAMS ABI, named locally for older SDKs.
// Resolve only the documented export; Windows 10 and older Windows 11 builds fall back.
struct CreationParams {
    POINTER_INPUT_TYPE pointerType;
    ULONG maxCount;
    POINTER_FEEDBACK_MODE feedbackMode;
    HMONITOR hMonitor;
    ULONG deviceWidth, deviceHeight;
    uint32_t options;
};
static_assert(offsetof(CreationParams, hMonitor) == (sizeof(void*) == 8 ? 16 : 12), "creation parameters ABI");
static_assert(sizeof(CreationParams) == (sizeof(void*) == 8 ? 40 : 28), "creation parameters size");
constexpr uint32_t physical_size = 1;
using Create = HANDLE(WINAPI*)(const CreationParams*);
using Inject = BOOL(WINAPI*)(HANDLE, const POINTER_TYPE_INFO*, UINT32);
using Destroy = void(WINAPI*)(HANDLE);

template<typename T> T procedure(HMODULE module, const char* name) {
    const auto address = GetProcAddress(module, name);
    T result = nullptr;
    static_assert(sizeof(result) == sizeof(address), "procedure pointer size");
    std::memcpy(&result, &address, sizeof(result));
    return result;
}
struct API {
    Create create;
    Inject inject;
    Destroy destroy;
    API() {
        const auto user = GetModuleHandleW(L"user32.dll");
        create = procedure<Create>(user, "CreateSyntheticPointerDevice2");
        inject = procedure<Inject>(user, "InjectSyntheticPointerInput");
        destroy = procedure<Destroy>(user, "DestroySyntheticPointerDevice");
    }
    bool available() const { return create && inject && destroy; }
};
}

bool native_touchpad_api_available() { return API{}.available(); }

std::vector<POINTER_TYPE_INFO> native_touchpad_frames(const std::vector<NativeTouchpadContact>& contacts,
                                                     RawTouchpadSize size, DWORD time, bool cancel) {
    raw_touchpad_ack(size);
    if (contacts.size() > 5) throw std::invalid_argument("too many native touchpad contacts");
    std::vector<POINTER_TYPE_INFO> result(contacts.size());
    for (size_t i = 0; i < contacts.size(); ++i) {
        const auto& contact = contacts[i];
        if (!contact.slot || contact.slot > 5 || !std::isfinite(contact.source.x) || !std::isfinite(contact.source.y) ||
            contact.source.x < 0 || contact.source.x > 1 || contact.source.y < 0 || contact.source.y > 1)
            throw std::invalid_argument("invalid native touchpad contact");
        auto& info = result[i];
        info.type = PT_TOUCHPAD;
        auto& pointer = info.touchInfo.pointerInfo;
        pointer.pointerType = PT_TOUCHPAD;
        pointer.pointerId = contact.slot;
        // The public touchpad injection sample uses contact state, not DOWN/UPDATE/UP bits.
        if (!cancel) {
            if (contact.source.confident) pointer.pointerFlags |= POINTER_FLAG_CONFIDENCE;
            if (contact.source.phase != RawTouchpadPhase::up)
                pointer.pointerFlags |= POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT;
        }
        pointer.ptHimetricLocation = {static_cast<LONG>(std::llround(contact.source.x * size.width)),
                                     static_cast<LONG>(std::llround(contact.source.y * size.height))};
        pointer.dwTime = time;
    }
    return result;
}

struct NativeTouchpadBackend::Impl {
    API api;
    HANDLE device = nullptr;
    RawTouchpadSize size;
    RawTouchpadState state;
    std::string reason = "not negotiated";
    uint64_t first_source_time = 0, last_time = 0;
    bool timestamp_started = false, enabled = false;

    bool create() {
        const CreationParams parameters{PT_TOUCHPAD, 5, POINTER_FEEDBACK_NONE, nullptr,
                                        size.width, size.height, physical_size};
        device = api.create(&parameters);
        if (!device) reason = "CreateSyntheticPointerDevice2 failed: " + std::to_string(GetLastError());
        return device != nullptr;
    }
    DWORD time(uint64_t source) {
        if (!timestamp_started) { first_source_time = source; timestamp_started = true; }
        const auto next = std::max((source - first_source_time) / 1000000 + 1, last_time + 1);
        if (next > std::numeric_limits<DWORD>::max()) throw std::runtime_error("native touchpad session timestamp exhausted");
        last_time = next;
        return static_cast<DWORD>(next);
    }
    bool inject(const std::vector<POINTER_TYPE_INFO>& frames) noexcept {
        if (frames.empty()) return true;
        for (unsigned attempt = 0; attempt < 4; ++attempt) {
            if (api.inject(device, frames.data(), static_cast<UINT32>(frames.size()))) return true;
            if (GetLastError() != ERROR_NOT_READY || attempt == 3) return false;
            Sleep(1);
        }
        return false;
    }
    void release() noexcept {
        const auto destroy = [&] {
            if (device) api.destroy(device);
            device = nullptr; timestamp_started = false; first_source_time = last_time = 0;
        };
        try {
            const auto active = state.active();
            if (!active.empty() && device) {
                // Clear confidence for aborted contact, then destroy the device to cancel OS state.
                const auto frames = native_touchpad_frames(active, size, static_cast<DWORD>(last_time + 1), true);
                inject(frames); destroy();
            }
        } catch (...) { destroy(); }
        state.cancel();
    }
    ~Impl() { release(); if (device) api.destroy(device); }
};

NativeTouchpadBackend::NativeTouchpadBackend() : impl_(std::make_unique<Impl>()) {}
NativeTouchpadBackend::~NativeTouchpadBackend() = default;
bool NativeTouchpadBackend::enable(RawTouchpadSize size, bool dry_run) {
    if (impl_->enabled) throw std::logic_error("native touchpad already negotiated");
    raw_touchpad_ack(size);
    if (dry_run) { impl_->reason = "dry-run does not create native input devices"; return false; }
    if (!impl_->api.available()) { impl_->reason = "Windows native touchpad API unavailable"; return false; }
    impl_->size = size;
    if (!impl_->create()) return false;
    impl_->enabled = true; impl_->reason.clear(); return true;
}
const std::string& NativeTouchpadBackend::unavailable_reason() const { return impl_->reason; }
void NativeTouchpadBackend::submit(const RawTouchpadFrame& frame) {
    if (!impl_->enabled) throw std::runtime_error("native touchpad was not negotiated");
    auto next = impl_->state;
    const auto contacts = next.accept(frame);
    if (frame.cancel) { impl_->release(); impl_->state = std::move(next); return; }
    if (!impl_->device && !impl_->create()) throw std::runtime_error(impl_->reason);
    const auto frames = native_touchpad_frames(contacts, impl_->size, impl_->time(frame.timestamp_ns));
    if (!impl_->inject(frames)) {
        const auto error = GetLastError();
        impl_->release();
        if (impl_->device) { impl_->api.destroy(impl_->device); impl_->device = nullptr; }
        throw std::runtime_error("native touchpad injection failed: " + std::to_string(error));
    }
    impl_->state = std::move(next);
}
void NativeTouchpadBackend::release() noexcept { impl_->release(); }
} // namespace reflection
