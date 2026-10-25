#pragma once
#include "reflection/core.hpp"
#include <filesystem>
#include <map>

namespace reflection {

struct Config {
    std::string transport = "tcp";
    std::string bind = "127.0.0.1";
    uint16_t port = 24819;
    std::string token;
    Mapping mapping;
    bool target_virtual_desktop = true;
    void validate() const;
};

std::map<std::string, std::string> config_values(const Config& config, bool secrets);
void set_config(Config& config, const std::string& key, const std::string& value);
Config load_config(const std::filesystem::path& path);
void save_config(const Config& config, const std::filesystem::path& path);
std::string json_escape(const std::string& text);

} // namespace reflection
