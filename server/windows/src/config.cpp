#include "reflection/config.hpp"
#include <fstream>
#include <iomanip>
#include <sstream>
#ifdef _WIN32
#include <windows.h>
#endif

namespace reflection {

static double number(const std::string& value) {
    size_t used = 0;
    const double n = std::stod(value, &used);
    if (used != value.size() || !std::isfinite(n)) throw std::invalid_argument("invalid number");
    return n;
}

static std::string text(double n) {
    std::ostringstream s;
    s << std::setprecision(12) << n;
    return s.str();
}

static Rect rectangle(const std::string& text) {
    std::istringstream stream(text);
    std::string part;
    std::vector<double> values;
    while (std::getline(stream, part, ',')) values.push_back(number(part));
    if (values.size() != 4 || text.back() == ',')
        throw std::invalid_argument("rectangle requires x,y,width,height");
    return {values[0], values[1], values[2], values[3]};
}

static std::string rect_text(const Rect& rect) {
    return text(rect.x) + "," + text(rect.y) + "," + text(rect.width) + "," + text(rect.height);
}

void Config::validate() const {
    mapping.validate();
    if (transport != "tcp" && transport != "bluetooth")
        throw std::invalid_argument("transport must be tcp or bluetooth");
    if (!port) throw std::invalid_argument("port must be in 1..65535");
    if (bind.empty() || bind.find_first_of("\r\n") != std::string::npos)
        throw std::invalid_argument("bind address is required");
    if (token.size() != 64 || token.find_first_not_of("0123456789abcdefABCDEF") != std::string::npos)
        throw std::invalid_argument("token must contain 64 hexadecimal characters");
}

std::map<std::string, std::string> config_values(const Config& c, bool secrets) {
    return {
        {"transport", c.transport},
        {"bind", c.bind}, {"port", std::to_string(c.port)}, {"token", secrets ? c.token : "<redacted>"},
        {"mapping.mode", c.mapping.relative ? "relative" : "absolute"},
        {"mapping.source", rect_text(c.mapping.source)},
        {"mapping.target", c.target_virtual_desktop ? "desktop" : rect_text(c.mapping.target)},
        {"mapping.rotation", std::to_string(c.mapping.rotation)},
        {"mapping.pressure_gamma", text(c.mapping.pressure_gamma)},
        {"mapping.sensitivity", text(c.mapping.sensitivity)}
    };
}

void set_config(Config& c, const std::string& key, const std::string& value) {
    if (key == "transport") c.transport = value;
    else if (key == "bind") c.bind = value;
    else if (key == "token") c.token = value;
    else if (key == "port") {
        const auto n = number(value);
        if (n != std::floor(n) || n < 1 || n > 65535) throw std::invalid_argument("invalid port");
        c.port = static_cast<uint16_t>(n);
    } else if (key == "mapping.mode") {
        if (value != "absolute" && value != "relative") throw std::invalid_argument("invalid mapping mode");
        c.mapping.relative = value == "relative";
    } else if (key == "mapping.source") c.mapping.source = rectangle(value);
    else if (key == "mapping.target") {
        c.target_virtual_desktop = value == "desktop";
        if (!c.target_virtual_desktop) c.mapping.target = rectangle(value);
    } else if (key == "mapping.rotation") {
        const auto n = number(value);
        if (n != 0 && n != 90 && n != 180 && n != 270) throw std::invalid_argument("invalid rotation");
        c.mapping.rotation = static_cast<unsigned>(n);
    } else if (key == "mapping.pressure_gamma") c.mapping.pressure_gamma = number(value);
    else if (key == "mapping.sensitivity") c.mapping.sensitivity = number(value);
    else throw std::invalid_argument("unknown configuration key: " + key);
}

Config load_config(const std::filesystem::path& path) {
    std::ifstream stream(path);
    if (!stream) throw std::runtime_error("cannot read configuration; run init first");
    Config config;
    std::map<std::string, bool> seen;
    std::string line;
    while (std::getline(stream, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty() || line[0] == '#') continue;
        const auto equal = line.find('=');
        if (equal == std::string::npos) throw std::invalid_argument("invalid configuration line");
        const auto key = line.substr(0, equal);
        if (seen[key]) throw std::invalid_argument("duplicate configuration key: " + key);
        seen[key] = true;
        set_config(config, key, line.substr(equal + 1));
    }
    config.validate();
    return config;
}

void save_config(const Config& config, const std::filesystem::path& path) {
    config.validate();
    if (!path.parent_path().empty()) std::filesystem::create_directories(path.parent_path());
    const auto temporary = std::filesystem::path(path.string() + ".tmp");
    {
        std::ofstream stream(temporary, std::ios::trunc);
        if (!stream) throw std::runtime_error("cannot create configuration");
        for (const auto& [key, value] : config_values(config, true)) stream << key << '=' << value << '\n';
        stream.flush();
        if (!stream) throw std::runtime_error("cannot write configuration");
    }
#ifdef _WIN32
    if (!MoveFileExW(temporary.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
        throw std::runtime_error("cannot atomically replace configuration");
#else
    std::filesystem::rename(temporary, path);
#endif
}

std::string json_escape(const std::string& text) {
    std::ostringstream out;
    out << '"';
    for (unsigned char ch : text) {
        if (ch == '\\' || ch == '"') out << '\\' << ch;
        else if (ch < 0x20) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << unsigned(ch);
        else out << ch;
    }
    out << '"';
    return out.str();
}

} // namespace reflection
