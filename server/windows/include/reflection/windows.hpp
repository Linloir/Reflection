#pragma once
#ifndef _WIN32
#error Windows API required
#endif

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include "reflection/config.hpp"
#include <atomic>
#include <functional>
#include <memory>
#include <optional>

namespace reflection {

std::vector<uint8_t> random_bytes(size_t count);
std::vector<uint8_t> hmac_sha256(const std::vector<uint8_t>& key, const std::vector<uint8_t>& message);
std::vector<uint8_t> unhex(const std::string& text);
std::string hex(const std::vector<uint8_t>& bytes);

struct Display {
    std::string name;
    Rect bounds;
    bool primary;
};

std::vector<Display> displays();
Rect virtual_desktop();
bool pen_api_available();
std::unique_ptr<InputBackend> make_pen_backend(bool dry_run);

struct HostEvent {
    std::string kind;
    std::string detail;
    uint64_t samples = 0;
    unsigned port = 0;
    bool dry_run = false;
    std::optional<MappedSample> pen;
};

using EventSink = std::function<void(const HostEvent&)>;
void serve(const Config& config, bool dry_run, std::atomic_bool& running, const EventSink& events);

} // namespace reflection
