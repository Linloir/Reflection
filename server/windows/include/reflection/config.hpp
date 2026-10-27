#pragma once
#include "reflection/core.hpp"
#include "reflection/stabilization.hpp"
#include <filesystem>
#include <map>

namespace reflection {

struct Config {
    std::string transport = "tcp";
    std::string bind = "0.0.0.0";
    uint16_t port = 24819;
    std::string token;
    Mapping mapping;
    StabilizationConfig stabilization;
    bool target_virtual_desktop = true;
    std::string target_display;
    bool show_cursor = true;
    bool native_touchpad = true;
    void validate() const;
};

std::map<std::string, std::string> config_values(const Config& config, bool secrets);
std::string config_revision(const Config& config);
void set_config(Config& config, const std::string& key, const std::string& value);
Config load_config(const std::filesystem::path& path);
void save_config(const Config& config, const std::filesystem::path& path);
std::vector<std::string> list_profiles(const std::filesystem::path& config_path);
void save_profile(const Config& config, const std::filesystem::path& config_path,
                  const std::string& name, bool replace = false);
Config load_profile(Config config, const std::filesystem::path& config_path, const std::string& name);
void delete_profile(const std::filesystem::path& config_path, const std::string& name);
std::string json_escape(const std::string& text);

} // namespace reflection
