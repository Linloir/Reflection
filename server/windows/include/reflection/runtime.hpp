#pragma once
#include "reflection/config.hpp"
#include "reflection/display_mapping.hpp"
#include <atomic>
#include <memory>
#include <string>

namespace reflection {

class ConfigLock {
public:
    explicit ConfigLock(const std::filesystem::path& path);
    ~ConfigLock();
    ConfigLock(const ConfigLock&) = delete;
    ConfigLock& operator=(const ConfigLock&) = delete;
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// Process control belongs to the host, not to a particular desktop UI.
class HostRuntime {
public:
    HostRuntime(const std::filesystem::path& path, const Config& config, bool dry_run,
                std::atomic_bool& running);
    ~HostRuntime();
    HostRuntime(const HostRuntime&) = delete;
    HostRuntime& operator=(const HostRuntime&) = delete;
    void event(const std::string& kind, unsigned port, uint64_t samples, const std::string& detail = "");
    static std::string status(const std::filesystem::path& path);
    static bool stop(const std::filesystem::path& path);
    std::shared_ptr<DisplayMapping> display_mapping() const;
    std::shared_ptr<HostInput> input() const;
    static std::string calibration_request(const std::filesystem::path& path, unsigned action);
    static std::string mapping_request(const std::filesystem::path& path, unsigned action,
                                       const std::string& display = "");
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace reflection
