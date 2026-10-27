#include "reflection/config.hpp"
#include <fstream>
#include <chrono>
#include <atomic>
#include <iomanip>
#include <limits>
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
    s << std::setprecision(std::numeric_limits<double>::max_digits10) << n;
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

static std::string curve_text(const Mapping& mapping) {
    std::string result;
    for (const auto& [x, y] : mapping.pressure_curve) {
        if (!result.empty()) result += ';';
        result += text(x) + ',' + text(y);
    }
    return result;
}

void Config::validate() const {
    mapping.validate();
    stabilization.validate();
    if (target_display.size() > 128 || std::any_of(target_display.begin(), target_display.end(),
        [](unsigned char ch) { return ch < 0x20; }))
        throw std::invalid_argument("invalid display identifier");
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
        {"mapping.target", !c.target_display.empty() ? "display:" + c.target_display :
            c.target_virtual_desktop ? "desktop" : rect_text(c.mapping.target)},
        {"mapping.show_cursor", c.show_cursor ? "true" : "false"},
        {"touchpad.native", c.native_touchpad ? "true" : "false"},
        {"mapping.rotation", std::to_string(c.mapping.rotation)},
        {"mapping.pressure_gamma", text(c.mapping.pressure_gamma)},
        {"mapping.pressure_curve", curve_text(c.mapping)},
        {"mapping.pressure_interpolation", c.mapping.pressure_interpolation},
        {"stabilization.enabled", c.stabilization.enabled ? "true" : "false"},
        {"stabilization.adaptive_noise", c.stabilization.adaptive_noise ? "true" : "false"},
        {"stabilization.independent_axes", c.stabilization.independent_axes ? "true" : "false"},
        {"stabilization.strength", text(c.stabilization.strength)},
        {"stabilization.min_cutoff_hz", text(c.stabilization.min_cutoff_hz)},
        {"stabilization.beta", text(c.stabilization.beta)},
        {"stabilization.derivative_cutoff_hz", text(c.stabilization.derivative_cutoff_hz)},
        {"stabilization.noise", text(c.stabilization.noise)},
        {"stabilization.max_jump", text(c.stabilization.max_jump)},
        {"mapping.position_steps_x", std::to_string(c.mapping.position_steps_x)},
        {"mapping.position_steps_y", std::to_string(c.mapping.position_steps_y)},
        {"mapping.tilt_enabled", c.mapping.tilt_enabled ? "true" : "false"},
        {"mapping.tilt_scale", text(c.mapping.tilt_scale)},
        {"mapping.sensitivity", text(c.mapping.sensitivity)}
    };
}

std::string config_revision(const Config& config) {
    uint64_t value = 14695981039346656037ull;
    for (const auto& [key, text] : config_values(config, true)) {
        for (unsigned char ch : key + '=' + text + '\n') {
            value ^= ch;
            value *= 1099511628211ull;
        }
    }
    std::ostringstream result;
    result << std::hex << std::setw(16) << std::setfill('0') << value;
    return result.str();
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
        c.target_display.clear();
        c.target_virtual_desktop = value == "desktop";
        if (value.rfind("display:", 0) == 0) {
            c.target_display = value.substr(8);
            if (c.target_display.empty()) throw std::invalid_argument("display target requires an identifier");
        } else if (!c.target_virtual_desktop) c.mapping.target = rectangle(value);
    } else if (key == "mapping.show_cursor") {
        if (value != "true" && value != "false") throw std::invalid_argument("show_cursor requires true or false");
        c.show_cursor = value == "true";
    } else if (key == "touchpad.native") {
        if (value != "true" && value != "false") throw std::invalid_argument("touchpad.native requires true or false");
        c.native_touchpad = value == "true";
    } else if (key == "mapping.rotation") {
        const auto n = number(value);
        if (n != 0 && n != 90 && n != 180 && n != 270) throw std::invalid_argument("invalid rotation");
        c.mapping.rotation = static_cast<unsigned>(n);
    } else if (key == "mapping.pressure_gamma") c.mapping.pressure_gamma = number(value);
    else if (key == "mapping.pressure_interpolation") c.mapping.pressure_interpolation = value;
    else if (key == "mapping.pressure_curve") {
        std::vector<std::pair<double, double>> points;
        std::istringstream stream(value);
        std::string knot;
        if (!value.empty() && value.back() == ';') throw std::invalid_argument("invalid pressure curve");
        while (std::getline(stream, knot, ';')) {
            const auto comma = knot.find(',');
            if (comma == std::string::npos) throw std::invalid_argument("curve knots require input,output");
            points.emplace_back(number(knot.substr(0, comma)), number(knot.substr(comma + 1)));
            if (points.size() > 32) throw std::invalid_argument("too many pressure curve knots");
        }
        c.mapping.pressure_curve = std::move(points);
    } else if (key == "mapping.position_steps_x" || key == "mapping.position_steps_y") {
        const auto n = number(value);
        if (n != std::floor(n) || (n != 0 && (n < 2 || n > 65536)))
            throw std::invalid_argument("position_steps must be 0 or 2..65536");
        (key == "mapping.position_steps_x" ? c.mapping.position_steps_x : c.mapping.position_steps_y)
            = static_cast<unsigned>(n);
    } else if (key == "mapping.tilt_enabled") {
        if (value != "true" && value != "false") throw std::invalid_argument("tilt_enabled requires true or false");
        c.mapping.tilt_enabled = value == "true";
    } else if (key == "mapping.tilt_scale") c.mapping.tilt_scale = number(value);
    else if (key == "mapping.sensitivity") c.mapping.sensitivity = number(value);
    else if (key == "stabilization.enabled") {
        if(value!="true" && value!="false") throw std::invalid_argument("stabilization.enabled requires true or false");
        c.stabilization.enabled=value=="true";
    }
    else if (key == "stabilization.adaptive_noise") {
        if(value!="true" && value!="false") throw std::invalid_argument("stabilization.adaptive_noise requires true or false");
        c.stabilization.adaptive_noise=value=="true";
    }
    else if (key == "stabilization.independent_axes") {
        if(value!="true" && value!="false") throw std::invalid_argument("stabilization.independent_axes requires true or false");
        c.stabilization.independent_axes=value=="true";
    }
    else if (key == "stabilization.strength") c.stabilization.strength=number(value);
    else if (key == "stabilization.min_cutoff_hz") c.stabilization.min_cutoff_hz=number(value);
    else if (key == "stabilization.beta") c.stabilization.beta=number(value);
    else if (key == "stabilization.derivative_cutoff_hz") c.stabilization.derivative_cutoff_hz=number(value);
    else if (key == "stabilization.noise") c.stabilization.noise=number(value);
    else if (key == "stabilization.max_jump") c.stabilization.max_jump=number(value);
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

static void write_values(const std::map<std::string, std::string>& values,
                         const std::filesystem::path& path, bool replace) {
    if (!path.parent_path().empty()) std::filesystem::create_directories(path.parent_path());
    static std::atomic<unsigned> counter{0};
    const auto temporary = std::filesystem::u8path(path.u8string() + "." +
        std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()) + "." +
        std::to_string(counter++) + ".tmp");
    struct Cleanup {
        std::filesystem::path path;
        ~Cleanup() { std::error_code error; std::filesystem::remove(path, error); }
    } cleanup{temporary};
    {
        std::ofstream stream(temporary, std::ios::trunc);
        if (!stream) throw std::runtime_error("cannot create configuration");
        for (const auto& [key, value] : values) stream << key << '=' << value << '\n';
        stream.flush();
        if (!stream) throw std::runtime_error("cannot write configuration");
    }
#ifdef _WIN32
    for (unsigned attempt = 0; ; ++attempt) {
        if (MoveFileExW(temporary.c_str(), path.c_str(),
                       MOVEFILE_WRITE_THROUGH | (replace ? MOVEFILE_REPLACE_EXISTING : 0))) break;
        const DWORD error = GetLastError();
        // A scanner may briefly retain the source or destination after the previous
        // command. Retrying the same atomic rename never exposes partial settings.
        if (attempt >= 5 || (error != ERROR_SHARING_VIOLATION && error != ERROR_LOCK_VIOLATION))
            throw std::runtime_error("cannot atomically replace configuration: Win32 " + std::to_string(error));
        Sleep(10 * (attempt + 1));
    }
#else
    if (replace) std::filesystem::rename(temporary, path);
    else std::filesystem::create_hard_link(temporary, path);
#endif
}

void save_config(const Config& config, const std::filesystem::path& path) {
    config.validate();
    write_values(config_values(config, true), path, true);
}

static std::filesystem::path profiles_directory(const std::filesystem::path& path) {
    return path.parent_path() / std::filesystem::u8path(path.filename().u8string() + ".profiles");
}

static std::filesystem::path profile_path(const std::filesystem::path& config_path, const std::string& name) {
    const std::string allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-";
    if (name.empty() || name.size() > 64 || name.find_first_not_of(allowed) != std::string::npos)
        throw std::invalid_argument("profile names require 1..64 ASCII letters, digits, underscores or hyphens");
    return profiles_directory(config_path) / ("profile-" + name + ".conf");
}

std::vector<std::string> list_profiles(const std::filesystem::path& config_path) {
    const auto directory = profiles_directory(config_path);
    std::vector<std::string> names;
    if (!std::filesystem::exists(directory)) return names;
    for (const auto& file : std::filesystem::directory_iterator(directory)) {
        const auto stem = file.path().stem().u8string();
        if (file.is_regular_file() && file.path().extension() == ".conf" && stem.rfind("profile-", 0) == 0) {
            const auto name = stem.substr(8);
            try { profile_path(config_path, name); names.push_back(name); }
            catch (const std::invalid_argument&) {}
        }
    }
    std::sort(names.begin(), names.end());
    return names;
}

void save_profile(const Config& config, const std::filesystem::path& config_path,
                  const std::string& name, bool replace) {
    config.validate();
    const auto path = profile_path(config_path, name);
    if (!replace && std::filesystem::exists(path))
        throw std::runtime_error("profile already exists; pass --replace to overwrite");
    std::map<std::string, std::string> values;
    for (const auto& [key, value] : config_values(config, false))
        if (key.rfind("mapping.", 0) == 0) values.emplace(key, value);
    write_values(values, path, replace);
}

Config load_profile(Config config, const std::filesystem::path& config_path, const std::string& name) {
    std::ifstream stream(profile_path(config_path, name));
    if (!stream) throw std::runtime_error("profile not found");
    // Missing keys in an older profile use mapping defaults, never the active profile's leftovers.
    config.mapping = Mapping{};
    config.target_virtual_desktop = true;
    config.target_display.clear();
    config.show_cursor = true;
    std::map<std::string, bool> seen;
    std::string line;
    while (std::getline(stream, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty() || line[0] == '#') continue;
        const auto equal = line.find('=');
        if (equal == std::string::npos) throw std::invalid_argument("invalid profile line");
        const auto key = line.substr(0, equal);
        if (key.rfind("mapping.", 0) != 0 || seen[key])
            throw std::invalid_argument("profile must contain only unique mapping keys");
        seen[key] = true;
        set_config(config, key, line.substr(equal + 1));
    }
    config.validate();
    return config;
}

void delete_profile(const std::filesystem::path& config_path, const std::string& name) {
    if (!std::filesystem::remove(profile_path(config_path, name)))
        throw std::runtime_error("profile not found");
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
