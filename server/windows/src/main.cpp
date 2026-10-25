#include "reflection/windows.hpp"
#include <cstdlib>
#include <iostream>

namespace {

std::atomic_bool running{true};
BOOL WINAPI console_handler(DWORD event) {
    if (event == CTRL_C_EVENT || event == CTRL_BREAK_EVENT || event == CTRL_CLOSE_EVENT) {
        running.store(false);
        return TRUE;
    }
    return FALSE;
}

std::filesystem::path default_config() {
    wchar_t* local = nullptr;
    size_t size = 0;
    if (_wdupenv_s(&local, &size, L"LOCALAPPDATA") || !local)
        throw std::runtime_error("LOCALAPPDATA is unavailable; provide --config PATH");
    std::filesystem::path result = std::filesystem::path(local) / "Reflection" / "server.conf";
    free(local);
    return result;
}

void usage() {
    std::cout <<
        "Reflection Windows host\n"
        "  reflection-host [--config PATH] init\n"
        "  reflection-host [--config PATH] config show [--secrets]\n"
        "  reflection-host [--config PATH] config set KEY VALUE\n"
        "  reflection-host [--config PATH] config validate\n"
        "  reflection-host [--config PATH] serve [--dry-run] [--transport tcp|bluetooth]\n"
        "  reflection-host doctor\n"
        "  reflection-host displays\n"
        "  reflection-host version\n"
        "\nConfiguration and diagnostics are JSON. Stop a server with Ctrl+C.\n";
}

void print_event(const reflection::HostEvent& event) {
    using reflection::json_escape;
    auto& output = event.kind == "disconnected" ? std::cerr : std::cout;
    output << "{\"event\":" << json_escape(event.kind);
    if (event.kind == "listening")
        output << ",\"transport\":" << json_escape(event.detail) << ",\"port\":" << event.port;
    else if (event.kind == "disconnected") output << ",\"reason\":" << json_escape(event.detail);
    else if (event.kind == "connected") output << ",\"authenticated\":true";
    else if (event.kind == "session_end") output << ",\"samples\":" << event.samples;
    if (event.pen) {
        const auto& pen = *event.pen;
        output << ",\"sequence\":" << pen.source.sequence
               << ",\"phase\":" << static_cast<unsigned>(pen.source.phase)
               << ",\"x\":" << pen.x << ",\"y\":" << pen.y << ",\"pressure\":" << pen.pressure;
    }
    output << ",\"dry_run\":" << (event.dry_run ? "true" : "false") << "}\n" << std::flush;
}

} // namespace

int main(int argc, char** argv) {
    using namespace reflection;
    try {
        // Physical desktop coordinates must not be virtualized by per-monitor DPI.
        SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
        SetConsoleCtrlHandler(console_handler, TRUE);
        std::vector<std::string> args;
        std::filesystem::path path;
        for (int i = 1; i < argc; ++i) {
            if (std::string(argv[i]) == "--config") {
                if (++i == argc) throw std::invalid_argument("--config requires a path");
                path = std::filesystem::u8path(argv[i]);
            } else args.emplace_back(argv[i]);
        }
        if (args.empty() || args[0] == "help" || args[0] == "--help") { usage(); return 0; }
        const auto& command = args[0];
        if (command == "version") { std::cout << "{\"version\":\"0.1.0\",\"protocol\":1}\n"; return 0; }
        if (command == "doctor") {
            const bool available = pen_api_available();
            std::cout << "{\"backend\":\"windows-synthetic-pen\",\"available\":"
                      << (available ? "true" : "false")
                      << ",\"pressure_max\":1024,\"wintab\":false,\"transports\":[\"tcp\",\"bluetooth-rfcomm\"],"
                         "\"note\":\"API availability does not verify injection into an application\"}\n";
            return available ? 0 : 1;
        }
        if (command == "displays") {
            std::cout << '[';
            bool first = true;
            for (const auto& display : displays()) {
                if (!first) std::cout << ',';
                first = false;
                const auto& r = display.bounds;
                std::cout << "{\"name\":" << json_escape(display.name)
                          << ",\"x\":" << r.x << ",\"y\":" << r.y
                          << ",\"width\":" << r.width << ",\"height\":" << r.height
                          << ",\"primary\":" << (display.primary ? "true" : "false") << '}';
            }
            std::cout << "]\n";
            return 0;
        }
        if (path.empty()) path = default_config();
        if (command == "init") {
            if (args.size() != 1) throw std::invalid_argument("init takes no arguments");
            if (std::filesystem::exists(path)) throw std::runtime_error("configuration already exists");
            Config config;
            config.token = hex(random_bytes(32));
            save_config(config, path);
            std::cout << "{\"created\":" << json_escape(path.u8string()) << "}\n";
            return 0;
        }
        auto config = load_config(path);
        if (command == "config") {
            if (args.size() < 2) throw std::invalid_argument("config requires show, set or validate");
            if (args[1] == "show") {
                if (args.size() > 3 || (args.size() == 3 && args[2] != "--secrets"))
                    throw std::invalid_argument("show accepts only --secrets");
                std::cout << '{';
                bool first = true;
                for (const auto& [key, value] : config_values(config, args.size() == 3)) {
                    if (!first) std::cout << ',';
                    first = false;
                    std::cout << json_escape(key) << ':' << json_escape(value);
                }
                std::cout << "}\n";
            } else if (args[1] == "set") {
                if (args.size() != 4) throw std::invalid_argument("config set requires KEY VALUE");
                set_config(config, args[2], args[3]);
                config.validate();
                save_config(config, path);
                std::cout << "{\"saved\":true}\n";
            } else if (args[1] == "validate") {
                if (args.size() != 2) throw std::invalid_argument("validate takes no arguments");
                config.validate();
                std::cout << "{\"valid\":true}\n";
            } else throw std::invalid_argument("unknown config command");
            return 0;
        }
        if (command == "serve" || command == "start") {
            bool dry_run = false;
            for (size_t i = 1; i < args.size(); ++i) {
                if (args[i] == "--dry-run") dry_run = true;
                else if (args[i] == "--transport") {
                    if (++i == args.size()) throw std::invalid_argument("--transport requires tcp or bluetooth");
                    config.transport = args[i];
                } else throw std::invalid_argument("unknown serve option: " + args[i]);
            }
            serve(config, dry_run, running, print_event);
            return 0;
        }
        throw std::invalid_argument("unknown command: " + command);
    } catch (const std::exception& error) {
        std::cerr << "{\"error\":" << reflection::json_escape(error.what()) << "}\n";
        return 1;
    }
}
