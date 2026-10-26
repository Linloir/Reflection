#include "reflection/runtime.hpp"
#include <chrono>
#include <iostream>
#include <thread>

using namespace reflection;

static unsigned checks = 0;
static void require(bool condition, const char* message) {
    ++checks;
    if (!condition) throw std::runtime_error(message);
}

int main() {
    const auto directory = std::filesystem::temp_directory_path() /
        ("reflection-runtime-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    struct Cleanup {
        std::filesystem::path path;
        ~Cleanup() { std::error_code error; std::filesystem::remove_all(path, error); }
    } cleanup{directory};
    try {
        const auto path = directory / "server.conf";
        Config config;
        config.token = std::string(64, 'a');
        save_config(config, path);
        require(HostRuntime::status(path).find("\"running\":false") != std::string::npos,
                "a saved configuration is not a running host");
        require(!HostRuntime::stop(path), "stop is safe when no host runs");
        std::atomic_bool running{true};
        {
            HostRuntime runtime(path, config, true, running);
            runtime.event("listening", 24819, 0);
            auto status = HostRuntime::status(path);
            require(status.find("\"controlAvailable\":true") != std::string::npos &&
                    status.find("\"listening\":true") != std::string::npos &&
                    status.find("\"restartRequired\":false") != std::string::npos,
                    "live host status and matching configuration");
            bool rejected = false;
            std::thread contender([&] {
                std::atomic_bool flag{true};
                try { HostRuntime duplicate(path, config, true, flag); }
                catch (const std::exception&) { rejected = true; }
            });
            contender.join();
            require(rejected, "configuration ownership rejects another host thread");
            runtime.event("connected", 0, 0);
            runtime.event("samples", 0, 123);
            status = HostRuntime::status(path);
            require(status.find("\"connected\":true") != std::string::npos &&
                    status.find("\"samples\":123") != std::string::npos, "session status reports samples");
            config.mapping.pressure_gamma = 0.7;
            save_config(config, path);
            require(HostRuntime::status(path).find("\"restartRequired\":true") != std::string::npos,
                    "disk configuration changes require restart");
            require(HostRuntime::stop(path), "cooperative stop event is available");
            for (int i = 0; i < 30 && running.load(); ++i)
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
            require(!running.load(), "cooperative stop is observed without killing a PID");
        }
        require(HostRuntime::status(path).find("\"running\":false") != std::string::npos,
                "status is removed when owner exits");
        running.store(true);
        {
            HostRuntime restarted(path, config, false, running);
            require(HostRuntime::status(path).find("\"restartRequired\":false") != std::string::npos,
                    "restart uses the saved configuration");
        }
        std::cout << checks << " runtime checks passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
