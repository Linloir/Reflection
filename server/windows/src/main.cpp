#include "reflection/windows.hpp"
#include "reflection/runtime.hpp"
#include <cstdlib>
#include <iostream>
#include <shellapi.h>

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
        "  reflection-host [--config PATH] config snapshot\n"
        "  reflection-host [--config PATH] config set KEY VALUE\n"
        "  reflection-host [--config PATH] config apply [--if-current REVISION] KEY VALUE [KEY VALUE ...]\n"
        "  reflection-host [--config PATH] config validate\n"
        "  reflection-host [--config PATH] profiles list|save|show|load|delete [NAME] [--replace]\n"
        "  reflection-host [--config PATH] serve [--dry-run] [--transport tcp|bluetooth]\n"
        "  reflection-host [--config PATH] status|stop\n"
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

int main() {
    using namespace reflection;
    try {
        // Physical desktop coordinates must not be virtualized by per-monitor DPI.
        SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
        SetConsoleCtrlHandler(console_handler, TRUE);
        int argc = 0;
        auto wide_args = CommandLineToArgvW(GetCommandLineW(), &argc);
        if (!wide_args) throw std::runtime_error("cannot decode command line");
        struct FreeArgs { wchar_t** value; ~FreeArgs() { LocalFree(value); } } free_args{wide_args};
        std::vector<std::string> argv;
        for (int i = 0; i < argc; ++i) {
            const int length = WideCharToMultiByte(CP_UTF8, 0, wide_args[i], -1, nullptr, 0, nullptr, nullptr);
            if (!length) throw std::runtime_error("cannot decode command line argument");
            std::string text(static_cast<size_t>(length), '\0');
            WideCharToMultiByte(CP_UTF8, 0, wide_args[i], -1, text.data(), length, nullptr, nullptr);
            text.pop_back();
            argv.push_back(std::move(text));
        }
        std::vector<std::string> args;
        std::filesystem::path path;
        for (int i = 1; i < argc; ++i) {
            if (argv[i] == "--config") {
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
        if (command == "status" || command == "stop") {
            if (args.size() != 1) throw std::invalid_argument("status/stop take no arguments");
            if (command == "status") std::cout << HostRuntime::status(path) << '\n';
            else std::cout << "{\"stopRequested\":" << (HostRuntime::stop(path) ? "true" : "false") << "}\n";
            return 0;
        }
        std::unique_ptr<ConfigLock> config_lock;
        if (command == "init" || command == "config" || command == "profile" || command == "profiles")
            config_lock = std::make_unique<ConfigLock>(path);
        if (command == "init") {
            if (args.size() != 1) throw std::invalid_argument("init takes no arguments");
            if (std::filesystem::exists(path)) throw std::runtime_error("configuration already exists");
            Config config;
            config.token = hex(random_bytes(32));
            save_config(config, path);
            std::cout << "{\"created\":" << json_escape(path.u8string()) << "}\n";
            return 0;
        }
        auto config = [&] {
            if (config_lock) return load_config(path);
            ConfigLock lock(path);
            return load_config(path);
        }();
        if (command == "config") {
            if (args.size() < 2) throw std::invalid_argument("config requires show, set or validate");
            if (args[1] == "snapshot") {
                if (args.size() != 2) throw std::invalid_argument("config snapshot takes no arguments");
                std::cout << "{\"revision\":" << json_escape(config_revision(config)) << ",\"values\":{";
                bool first = true;
                for (const auto& [key, value] : config_values(config, false)) {
                    if (!first) std::cout << ',';
                    first = false; std::cout << json_escape(key) << ':' << json_escape(value);
                }
                std::cout << "}}\n";
            } else if (args[1] == "show") {
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
            } else if (args[1] == "set" || args[1] == "apply") {
                size_t begin = 2;
                if (args[1] == "apply" && args.size() > 2 && args[2] == "--if-current") {
                    if (args.size() < 4) throw std::invalid_argument("--if-current requires a revision");
                    if (args[3] != config_revision(config))
                        throw std::runtime_error("configuration changed outside this window; refresh before saving");
                    begin = 4;
                }
                if ((args[1] == "set" && args.size() != 4) || args.size() < begin + 2 || args.size() % 2 != 0)
                    throw std::invalid_argument("config set/apply requires KEY VALUE pairs");
                std::map<std::string, bool> seen;
                for (size_t i = begin; i < args.size(); i += 2) {
                    if (seen[args[i]]) throw std::invalid_argument("duplicate configuration key");
                    seen[args[i]] = true;
                    set_config(config, args[i], args[i + 1]);
                }
                config.validate();
                save_config(config, path);
                std::cout << "{\"saved\":true,\"revision\":" << json_escape(config_revision(config)) << "}\n";
            } else if (args[1] == "validate") {
                if (args.size() != 2) throw std::invalid_argument("validate takes no arguments");
                config.validate();
                std::cout << "{\"valid\":true}\n";
            } else throw std::invalid_argument("unknown config command");
            return 0;
        }
        if (command == "profile" || command == "profiles") {
            if (args.size() < 2) throw std::invalid_argument("profiles requires list, save, show, load or delete");
            const auto& action = args[1];
            if (action == "list") {
                if (args.size() != 2) throw std::invalid_argument("profile list takes no arguments");
                std::cout << "{\"profiles\":[";
                bool first = true;
                for (const auto& name : list_profiles(path)) {
                    if (!first) std::cout << ',';
                    first = false; std::cout << json_escape(name);
                }
                std::cout << "]}\n";
            } else if (action == "save") {
                if (args.size() < 3 || args.size() > 4 || (args.size() == 4 && args[3] != "--replace"))
                    throw std::invalid_argument("profile save requires NAME [--replace]");
                save_profile(config, path, args[2], args.size() == 4);
                std::cout << "{\"saved\":true}\n";
            } else if (action == "show") {
                if (args.size() != 3) throw std::invalid_argument("profiles show requires NAME");
                const auto profile = load_profile(config, path, args[2]);
                std::cout << '{';
                bool first = true;
                for (const auto& [key, value] : config_values(profile, false)) {
                    if (key.rfind("mapping.", 0) != 0) continue;
                    if (!first) std::cout << ',';
                    first = false; std::cout << json_escape(key) << ':' << json_escape(value);
                }
                std::cout << "}\n";
            } else if (action == "load") {
                if (args.size() != 3) throw std::invalid_argument("profile load requires NAME");
                save_config(load_profile(config, path, args[2]), path);
                std::cout << "{\"loaded\":true,\"applies\":\"next-start\"}\n";
            } else if (action == "delete") {
                if (args.size() != 3) throw std::invalid_argument("profile delete requires NAME");
                delete_profile(path, args[2]);
                std::cout << "{\"deleted\":true}\n";
            } else throw std::invalid_argument("unknown profile command");
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
            config.validate();
            HostRuntime runtime(path, config, dry_run, running);
            serve(config, dry_run, running, [&](const HostEvent& event) {
                runtime.event(event.kind, event.port, event.samples);
                if (event.kind != "samples") print_event(event);
            });
            return 0;
        }
        throw std::invalid_argument("unknown command: " + command);
    } catch (const std::exception& error) {
        std::cerr << "{\"error\":" << reflection::json_escape(error.what()) << "}\n";
        return 1;
    }
}
