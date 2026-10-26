#pragma once
#include "reflection/config.hpp"
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
    void event(const std::string& kind, unsigned port, uint64_t samples);
    static std::string status(const std::filesystem::path& path);
    static bool stop(const std::filesystem::path& path);
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace reflection
