#include "reflection/runtime.hpp"
#include "reflection/windows.hpp"
#include <cwctype>
#include <thread>
#include <sstream>
#include <cstring>

namespace reflection {
namespace {

uint64_t hash_text(const std::string& text) {
    uint64_t hash = 14695981039346656037ull;
    for (const auto ch : text) { hash ^= static_cast<unsigned char>(ch); hash *= 1099511628211ull; }
    return hash;
}
uint64_t fingerprint(const Config& config) {
    std::string text;
    for (const auto& [key, value] : config_values(config, true))
        if (key != "input.mode") text += key + '=' + value + '\n';
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
    volatile LONG input_mode;
};
struct Handle {
    HANDLE value = nullptr;
    ~Handle() { if (value) CloseHandle(value); }
};
struct View {
    State* value = nullptr;
    ~View() { if (value) UnmapViewOfFile(value); }
};
struct MappingState {
    DWORD version;
    uint64_t requested, completed;
    unsigned action;
    char display[129];
    char response[4096];
    char snapshot[4096];
};
struct MappingView {
    MappingState* value = nullptr;
    ~MappingView() { if (value) UnmapViewOfFile(value); }
};
struct MutexScope {
    HANDLE handle;
    explicit MutexScope(HANDLE value) : handle(value) {
        const auto wait = WaitForSingleObject(handle, 2000);
        if (wait != WAIT_OBJECT_0 && wait != WAIT_ABANDONED)
            throw std::runtime_error("live mapping control is busy; retry the command");
    }
    ~MutexScope() { ReleaseMutex(handle); }
};
void copy_response(MappingState& state, const std::string& response) {
    if (response.size() >= sizeof(state.response)) throw std::runtime_error("mapping status exceeds frame limit");
    std::memcpy(state.response, response.c_str(), response.size() + 1);
}

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
    Handle mapping_data, mapping_mutex, mapping_call_mutex;
    MappingView mapping_state;
    Handle calibration_data, calibration_mutex, calibration_call_mutex;
    MappingView calibration_state;
    std::shared_ptr<DisplayMapping> display_mapping;
    std::shared_ptr<HostInput> input;
    Config startup_config;
    std::filesystem::path config_path;
    std::atomic_bool& running;
    std::thread watcher;
    Impl(const std::filesystem::path& path, const Config& config, bool dry_run, std::atomic_bool& flag)
        : startup_config(config), config_path(path), running(flag) {
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
        *state.value = State{2, GetCurrentProcessId(), fingerprint(config), GetTickCount64(),
                            0, 0, 0, dry_run ? 1L : 0L, 0, -1};
        display_mapping = std::make_shared<DisplayMapping>(config, displays);
        input = std::make_shared<HostInput>(config.stabilization);
        mapping_mutex.value = CreateMutexW(nullptr, FALSE, object_name(path, L"-mapping-lock").c_str());
        mapping_call_mutex.value = CreateMutexW(nullptr, FALSE, object_name(path, L"-mapping-call").c_str());
        mapping_data.value = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0,
            sizeof(MappingState), object_name(path, L"-mapping-state").c_str());
        if (!mapping_mutex.value || !mapping_call_mutex.value || !mapping_data.value)
            throw std::runtime_error("cannot create live mapping control");
        mapping_state.value = static_cast<MappingState*>(MapViewOfFile(mapping_data.value, FILE_MAP_ALL_ACCESS,
            0, 0, sizeof(MappingState)));
        if (!mapping_state.value) throw std::runtime_error("cannot map live mapping control");
        {
            MutexScope lock(mapping_mutex.value);
            *mapping_state.value = MappingState{};
            mapping_state.value->version = 1;
            copy_response(*mapping_state.value, display_mapping->status());
            std::memcpy(mapping_state.value->snapshot, mapping_state.value->response, sizeof(mapping_state.value->snapshot));
        }
        calibration_mutex.value=CreateMutexW(nullptr,FALSE,object_name(path,L"-calibration-lock").c_str());
        calibration_call_mutex.value=CreateMutexW(nullptr,FALSE,object_name(path,L"-calibration-call").c_str());
        calibration_data.value=CreateFileMappingW(INVALID_HANDLE_VALUE,nullptr,PAGE_READWRITE,0,sizeof(MappingState),object_name(path,L"-calibration-state").c_str());
        if(!calibration_mutex.value || !calibration_call_mutex.value || !calibration_data.value) throw std::runtime_error("cannot create calibration control");
        calibration_state.value=static_cast<MappingState*>(MapViewOfFile(calibration_data.value,FILE_MAP_ALL_ACCESS,0,0,sizeof(MappingState)));
        if(!calibration_state.value) throw std::runtime_error("cannot map calibration control");
        { MutexScope lock(calibration_mutex.value); *calibration_state.value=MappingState{}; calibration_state.value->version=1;
          copy_response(*calibration_state.value,input->calibration_status());
          const auto snapshot="{\"calibration\":"+input->calibration_status()+",\"stabilization\":"+input->status()+"}";
          std::memcpy(calibration_state.value->snapshot,snapshot.c_str(),snapshot.size()+1); }
        watcher = std::thread([this] {
            auto last_refresh = GetTickCount64();
            while (running.load()) {
                if (WaitForSingleObject(stop_event.value, 100) == WAIT_OBJECT_0) {
                    running.store(false);
                    break;
                }
                try {
                    if (GetTickCount64() - last_refresh >= 500) {
                        display_mapping->refresh(); last_refresh = GetTickCount64();
                    }
                    MutexScope lock(mapping_mutex.value);
                    auto& request = *mapping_state.value;
                    if (request.requested != request.completed) {
                        try {
                            if (request.display[128] != '\0') throw std::invalid_argument("invalid display identifier");
                            display_mapping->request(request.action, request.display);
                            copy_response(request, display_mapping->status());
                        } catch (const std::exception& error) {
                            copy_response(request, "{\"error\":" + json_escape(error.what()) + "}");
                        }
                        request.completed = request.requested;
                    }
                    const auto snapshot = display_mapping->status();
                    if (snapshot.size() < sizeof(request.snapshot))
                        std::memcpy(request.snapshot, snapshot.c_str(), snapshot.size() + 1);
                } catch (const std::exception&) { /* A topology refresh must not stop the input service. */ }
                try {
                    MutexScope lock(calibration_mutex.value);
                    auto& request=*calibration_state.value;
                    if(request.requested!=request.completed) {
                        try {
                            copy_response(request,input->calibration(request.action,[this](double noise) {
                                ConfigLock config_lock(config_path);
                                auto latest=load_config(config_path);
                                latest.stabilization.noise=noise; save_config(latest,config_path);
                                startup_config.stabilization.noise=noise;
                                InterlockedExchange64(reinterpret_cast<volatile LONG64*>(&state.value->fingerprint),static_cast<LONG64>(fingerprint(startup_config)));
                            }));
                        } catch(const std::exception& error) { copy_response(request,"{\"error\":"+json_escape(error.what())+"}"); }
                        request.completed=request.requested;
                    }
                    const auto snapshot="{\"calibration\":"+input->calibration_status()+",\"stabilization\":"+input->status()+"}";
                    if(snapshot.size()<sizeof(request.snapshot)) std::memcpy(request.snapshot,snapshot.c_str(),snapshot.size()+1);
                } catch(const std::exception&) {}
            }
        });
    }
    ~Impl() {
        running.store(false);
        SetEvent(stop_event.value);
        if (watcher.joinable()) watcher.join();
        if (mapping_state.value) mapping_state.value->version = 0;
        if (calibration_state.value) calibration_state.value->version = 0;
        state.value->version = 0;
        ReleaseMutex(mutex.value);
    }
};

HostRuntime::HostRuntime(const std::filesystem::path& path, const Config& config,
                         bool dry_run, std::atomic_bool& running)
    : impl_(std::make_unique<Impl>(path, config, dry_run, running)) {}
HostRuntime::~HostRuntime() = default;
std::shared_ptr<DisplayMapping> HostRuntime::display_mapping() const { return impl_->display_mapping; }
std::shared_ptr<HostInput> HostRuntime::input() const { return impl_->input; }

std::string HostRuntime::calibration_request(const std::filesystem::path& path,unsigned action) {
    if(action>3) throw std::invalid_argument("invalid calibration command");
    Handle data,mutex,serial_mutex;
    data.value=OpenFileMappingW(FILE_MAP_ALL_ACCESS,FALSE,object_name(path,L"-calibration-state").c_str());
    if(!data.value) {
        if(action) throw std::runtime_error("start the updated host before calibration");
        ConfigLock lock(path); HostInput offline(load_config(path).stabilization); return offline.calibration_status();
    }
    mutex.value=OpenMutexW(SYNCHRONIZE|MUTEX_MODIFY_STATE,FALSE,object_name(path,L"-calibration-lock").c_str());
    serial_mutex.value=OpenMutexW(SYNCHRONIZE|MUTEX_MODIFY_STATE,FALSE,object_name(path,L"-calibration-call").c_str());
    if(!mutex.value || !serial_mutex.value) throw std::runtime_error("cannot access calibration control");
    MappingView view; view.value=static_cast<MappingState*>(MapViewOfFile(data.value,FILE_MAP_ALL_ACCESS,0,0,sizeof(MappingState)));
    if(!view.value) throw std::runtime_error("cannot map calibration control");
    MutexScope serial(serial_mutex.value);
    uint64_t id;
    { MutexScope lock(mutex.value); if(view.value->version!=1) throw std::runtime_error("calibration unavailable");
      id=++view.value->requested; view.value->action=action; }
    for(unsigned i=0;i<100;++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(20)); MutexScope lock(mutex.value);
        if(view.value->version!=1) throw std::runtime_error("host stopped during calibration");
        if(view.value->completed==id) {
            const std::string result(view.value->response,strnlen_s(view.value->response,sizeof(view.value->response)));
            if(result.rfind("{\"error\":",0)==0) throw std::runtime_error(result); return result;
        }
    }
    throw std::runtime_error("calibration command timed out");
}

std::string HostRuntime::mapping_request(const std::filesystem::path& path, unsigned action,
                                         const std::string& display) {
    if (action > 4 || display.size() > 128) throw std::invalid_argument("invalid mapping command");
    Handle mapping_data, mapping_mutex, mapping_call_mutex;
    mapping_data.value = OpenFileMappingW(FILE_MAP_ALL_ACCESS, FALSE, object_name(path, L"-mapping-state").c_str());
    if (!mapping_data.value) {
        if (status(path).find("\"running\":true") != std::string::npos)
            throw std::runtime_error("running host does not support live mapping; restart the updated host");
        ConfigLock lock(path);
        auto config = load_config(path);
        DisplayMapping current(config, displays);
        current.request(action, display);
        if (action) {
            // Offline commands persist a target so the next start uses the chosen display.
            if (action == 3) set_config(config, "mapping.target", "desktop");
            else {
                // Select/next/previous resolution is available without decoding a JSON string.
                // Read back the requested identifier from the typed mapping selection.
                set_config(config, "mapping.target", "display:" + current.selected_display());
            }
            save_config(config, path);
        }
        return current.status();
    }
    mapping_mutex.value = OpenMutexW(SYNCHRONIZE | MUTEX_MODIFY_STATE, FALSE,
        object_name(path, L"-mapping-lock").c_str());
    mapping_call_mutex.value = OpenMutexW(SYNCHRONIZE | MUTEX_MODIFY_STATE, FALSE,
        object_name(path, L"-mapping-call").c_str());
    if (!mapping_mutex.value || !mapping_call_mutex.value) throw std::runtime_error("cannot access live mapping control");
    MappingView view;
    view.value = static_cast<MappingState*>(MapViewOfFile(mapping_data.value, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(MappingState)));
    if (!view.value) throw std::runtime_error("cannot map live mapping control");
    MutexScope serial(mapping_call_mutex.value);
    uint64_t request_id;
    {
        MutexScope lock(mapping_mutex.value);
        if (view.value->version != 1) throw std::runtime_error("live mapping control is unavailable");
        if (!action) return std::string(view.value->snapshot, strnlen_s(view.value->snapshot, sizeof(view.value->snapshot)));
        request_id = ++view.value->requested;
        view.value->action = action;
        std::memset(view.value->display, 0, sizeof(view.value->display));
        std::memcpy(view.value->display, display.data(), display.size());
    }
    for (unsigned i = 0; i < 100; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        MutexScope lock(mapping_mutex.value);
        if (view.value->version != 1) throw std::runtime_error("host stopped during mapping command");
        if (view.value->completed == request_id) {
            const std::string result(view.value->response, strnlen_s(view.value->response, sizeof(view.value->response)));
            if (result.rfind("{\"error\":", 0) == 0) throw std::runtime_error(result);
            return result;
        }
    }
    throw std::runtime_error("live mapping command timed out");
}

void HostRuntime::event(const std::string& kind, unsigned port, uint64_t samples, const std::string& detail) {
    auto& state = *impl_->state.value;
    if (kind == "input_mode") {
        InterlockedExchange(&state.input_mode, detail == "driver" ? 1 : 0);
    } else if (kind == "listening") {
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
    if (!view.value || view.value->version != 2)
        return "{\"running\":false,\"processAlive\":false,\"controlAvailable\":false,\"restartRequired\":false}";
    const auto& state = *view.value;
    bool restart = true;
    try {
        ConfigLock lock(path);
        auto current = load_config(path);
        // Input mode applies live; all other settings retain their restart semantics.
        restart = state.fingerprint != fingerprint(current);
    } catch (const std::exception&) {}
    std::ostringstream out;
    out << "{\"running\":true,\"processAlive\":true,\"controlAvailable\":true,\"pid\":" << state.pid
        << ",\"listening\":" << (state.listening ? "true" : "false")
        << ",\"connected\":" << (state.connected ? "true" : "false")
        << ",\"port\":" << state.port << ",\"samples\":" << state.samples
        << ",\"dry_run\":" << (state.dry_run ? "true" : "false")
        << ",\"inputMode\":" << json_escape(state.input_mode < 0 ? "starting" : state.input_mode ? "driver" : "api")
        << ",\"uptime_ms\":" << GetTickCount64() - state.started
        << ",\"restartRequired\":" << (restart ? "true" : "false") << '}';
    Handle calibration_data,calibration_mutex;
    calibration_data.value=OpenFileMappingW(FILE_MAP_READ,FALSE,object_name(path,L"-calibration-state").c_str());
    calibration_mutex.value=OpenMutexW(SYNCHRONIZE|MUTEX_MODIFY_STATE,FALSE,object_name(path,L"-calibration-lock").c_str());
    if(calibration_data.value && calibration_mutex.value) {
        MappingView extra; extra.value=static_cast<MappingState*>(MapViewOfFile(calibration_data.value,FILE_MAP_READ,0,0,sizeof(MappingState)));
        if(extra.value) {
            MutexScope lock(calibration_mutex.value);
            const std::string snapshot(extra.value->snapshot,strnlen_s(extra.value->snapshot,sizeof(extra.value->snapshot)));
            if(extra.value->version==1 && snapshot.size()>2) { auto result=out.str(); result.pop_back(); return result+","+snapshot.substr(1); }
        }
    }
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
