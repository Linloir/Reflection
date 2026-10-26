#pragma once
#ifndef _WIN32
#error Windows API required
#endif

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include "reflection/config.hpp"
#include "reflection/display_mapping.hpp"
#include <atomic>
#include <functional>
#include <memory>
#include <optional>

namespace reflection {

std::vector<uint8_t> random_bytes(size_t count);
std::vector<uint8_t> hmac_sha256(const std::vector<uint8_t>& key, const std::vector<uint8_t>& message);
std::vector<uint8_t> unhex(const std::string& text);
std::string hex(const std::vector<uint8_t>& bytes);

std::vector<Display> displays();
Rect virtual_desktop();
// Synthetic pointer injection uses the virtual screen's top-left as (0,0),
// unlike the signed desktop coordinates used by monitor enumeration and mapping.
POINT synthetic_pointer_position(double desktop_x, double desktop_y, const Rect& virtual_bounds);
POINTER_TYPE_INFO synthetic_pen_frame(const MappedSample& sample, const Rect& virtual_bounds,
                                     const POINTER_TYPE_INFO* previous);
bool identical_pen_update(const POINTER_TYPE_INFO& next, const POINTER_TYPE_INFO& previous);
POINTER_TYPE_INFO synthetic_pen_repeat(const POINTER_TYPE_INFO& previous, bool contact);
// A terminal contact frame (contact=true) precedes the out-of-range frame (false).
POINTER_TYPE_INFO synthetic_pen_release(const POINTER_TYPE_INFO& previous, bool contact);
bool synthetic_pen_refresh_due(bool in_range, int64_t elapsed_us, const Rect& previous, const Rect& current);
bool pen_api_available();
std::unique_ptr<InputBackend> make_pen_backend(bool dry_run, bool show_cursor = true);

struct HostEvent {
    std::string kind;
    std::string detail;
    uint64_t samples = 0;
    unsigned port = 0;
    bool dry_run = false;
    std::optional<MappedSample> pen;
};

using EventSink = std::function<void(const HostEvent&)>;
void serve(const Config& config, bool dry_run, std::atomic_bool& running, const EventSink& events,
           std::shared_ptr<DisplayMapping> mapping = {}, std::shared_ptr<HostInput> input = {});

} // namespace reflection
