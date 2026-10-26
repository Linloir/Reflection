#pragma once
#include "reflection/config.hpp"
#include <array>
#include <memory>

namespace reflection {
using TabletReport = std::array<uint8_t, 10>;
TabletReport tablet_report(const MappedSample& sample, const Rect& desktop);
TabletReport tablet_release(TabletReport report, bool in_range);

struct DriverPackageStatus {
    bool available = false;
    std::string state = "missing";
    std::string detail;
    std::filesystem::path directory;
    std::string json() const;
};
DriverPackageStatus inspect_driver_package(const std::filesystem::path& directory);

struct DriverStatus {
    bool installed = false;
    bool available = false;
    DriverPackageStatus package;
    std::string detail;
    std::string json() const;
};
DriverStatus driver_status(bool inspect_package = true);
void install_driver();
void uninstall_driver();
void driver_system_action(bool install);
std::unique_ptr<InputBackend> make_driver_backend();
}
