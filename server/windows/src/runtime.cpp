#include "reflection/runtime.hpp"
#include <windows.h>
#include <cwctype>
#include <thread>
#include <sstream>

namespace reflection {
namespace {

uint64_t hash_text(const std::string& text) {
    uint64_t hash = 14695981039346656037ull;
    for (const auto ch : text) { hash ^= static_cast<unsigned char>(ch); hash *= 1099511628211ull; }
    return hash;
}
uint64_t fingerprint(const Config& config) {
    std::string text;
    for (const auto& [key, value] : config_values(config, true)) text += key + '=' + value + '\n';
    return hash_text(text);
}
std::wstring object_name(const std::filesystem::path& path, const wchar_t* suffix) {
    auto canonical = std::filesystem::weakly_canonical(std::filesystem::absolute(path)).wstring();
    for (auto& ch : canonical) ch = static_cast<wchar_t>(std::towlower(ch));
    const auto bytes = std::filesystem::path(canonical).u8string();
    return L"Local\\Reflection-" + std::to_wstring(hash_text(bytes)) + suffix;
}
struct State {
    DWORD version, pid;
    uint64_t fingerprint, started;
    volatile LONG listening, connected, port, dry_run;
    volatile LONG64 samples;
};
struct Handle {
    HANDLE value = nullptr;
    ~Handle() { if (value) CloseHandle(value); }
};
struct View {
    State* value = nullptr;
    ~View() { if (value) UnmapViewOfFile(value); }
};

} // namespace

struct ConfigLock::Impl {
    Handle mutex;
    explicit Impl(const std::filesystem::path& path) {
        mutex.value = CreateMutexW(nullptr, FALSE, object_name(path, L"-config").c_str());
        if (!mutex.value) throw std::runtime_error("cannot create configuration lock");
        const auto wait = WaitForSingleObject(mutex.value, 10000);
        if (wait != WAIT_OBJECT_0 && wait != WAIT_ABANDONED)
            throw std::runtime_error("configuration is busy; retry the command");
    }
    ~Impl() { ReleaseMutex(mutex.value); }
};
ConfigLock::ConfigLock(const std::filesystem::path& path) : impl_(std::make_unique<Impl>(path)) {}
ConfigLock::~ConfigLock() = default;

struct HostRuntime::Impl {
    Handle mutex, stop_event, mapping;
    View state;
    std::atomic_bool& running;
    std::thread watcher;
    Impl(const std::filesystem::path& path, const Config& config, bool dry_run, std::atomic_bool& flag)
        : running(flag) {
        mutex.value = CreateMutexW(nullptr, FALSE, object_name(path, L"-owner").c_str());
        if (!mutex.value) throw std::runtime_error("cannot create host ownership mutex");
        const DWORD ownership = WaitForSingleObject(mutex.value, 0);
        if (ownership != WAIT_OBJECT_0 && ownership != WAIT_ABANDONED)
            throw std::runtime_error("a host already owns this configuration");
        stop_event.value = CreateEventW(nullptr, TRUE, FALSE, object_name(path, L"-stop").c_str());
        if (!stop_event.value) throw std::runtime_error("cannot create host stop event");
        ResetEvent(stop_event.value);
        mapping.value = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0,
                                           sizeof(State), object_name(path, L"-state").c_str());
        if (!mapping.value) throw std::runtime_error("cannot create host status");
        state.value = static_cast<State*>(MapViewOfFile(mapping.value, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(State)));
        if (!state.value) throw std::runtime_error("cannot map host status");
        *state.value = State{1, GetCurrentProcessId(), fingerprint(config), GetTickCount64(),
                            0, 0, 0, dry_run ? 1L : 0L, 0};
        watcher = std::thread([this] {
            while (running.load()) {
                if (WaitForSingleObject(stop_event.value, 100) == WAIT_OBJECT_0) {
                    running.store(false);
                    break;
                }
            }
        });
    }
    ~Impl() {
        running.store(false);
        SetEvent(stop_event.value);
        if (watcher.joinable()) watcher.join();
        state.value->version = 0;
        ReleaseMutex(mutex.value);
    }
};

HostRuntime::HostRuntime(const std::filesystem::path& path, const Config& config,
                         bool dry_run, std::atomic_bool& running)
    : impl_(std::make_unique<Impl>(path, config, dry_run, running)) {}
HostRuntime::~HostRuntime() = default;

void HostRuntime::event(const std::string& kind, unsigned port, uint64_t samples) {
    auto& state = *impl_->state.value;
    if (kind == "listening") {
        InterlockedExchange(&state.port, static_cast<LONG>(port));
        InterlockedExchange(&state.listening, 1);
    } else if (kind == "connected") {
        InterlockedExchange(&state.connected, 1);
        InterlockedExchange64(&state.samples, 0);
    } else if (kind == "session_end" || kind == "disconnected") {
        InterlockedExchange(&state.connected, 0);
        if (kind == "session_end") InterlockedExchange64(&state.samples, static_cast<LONG64>(samples));
    } else if (kind == "samples") InterlockedExchange64(&state.samples, static_cast<LONG64>(samples));
}

std::string HostRuntime::status(const std::filesystem::path& path) {
    Handle mapping;
    mapping.value = OpenFileMappingW(FILE_MAP_READ, FALSE, object_name(path, L"-state").c_str());
    if (!mapping.value) {
        if (GetLastError() != ERROR_FILE_NOT_FOUND) throw std::runtime_error("cannot access host status");
        return "{\"running\":false,\"processAlive\":false,\"controlAvailable\":false,\"restartRequired\":false}";
    }
    View view;
    view.value = static_cast<State*>(MapViewOfFile(mapping.value, FILE_MAP_READ, 0, 0, sizeof(State)));
    if (!view.value || view.value->version != 1)
        return "{\"running\":false,\"processAlive\":false,\"controlAvailable\":false,\"restartRequired\":false}";
    const auto& state = *view.value;
    bool restart = true;
    try {
        ConfigLock lock(path);
        restart = state.fingerprint != fingerprint(load_config(path));
    } catch (const std::exception&) {}
    std::ostringstream out;
    out << "{\"running\":true,\"processAlive\":true,\"controlAvailable\":true,\"pid\":" << state.pid
        << ",\"listening\":" << (state.listening ? "true" : "false")
        << ",\"connected\":" << (state.connected ? "true" : "false")
        << ",\"port\":" << state.port << ",\"samples\":" << state.samples
        << ",\"dry_run\":" << (state.dry_run ? "true" : "false")
        << ",\"uptime_ms\":" << GetTickCount64() - state.started
        << ",\"restartRequired\":" << (restart ? "true" : "false") << '}';
    return out.str();
}

bool HostRuntime::stop(const std::filesystem::path& path) {
    Handle event;
    event.value = OpenEventW(EVENT_MODIFY_STATE, FALSE, object_name(path, L"-stop").c_str());
    if (!event.value) {
        if (GetLastError() != ERROR_FILE_NOT_FOUND) throw std::runtime_error("cannot access host stop event");
        return false;
    }
    if (!SetEvent(event.value)) throw std::runtime_error("cannot signal host stop");
    return true;
}

} // namespace reflection
