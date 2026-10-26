#include "reflection/config.hpp"
#include "reflection/protocol.hpp"
#include <cstring>
#include <functional>
#include <iostream>
#include <limits>
#include <fstream>
#include <chrono>

using namespace reflection;

static unsigned checks = 0;
static void require(bool condition, const char* name) {
    ++checks;
    if (!condition) throw std::runtime_error(name);
}
static void reject(const std::function<void()>& fn, const char* name) {
    bool rejected = false;
    try { fn(); } catch (const std::exception&) { rejected = true; }
    require(rejected, name);
}
static bool near(double a, double b) { return std::abs(a - b) < 0.001; }
static void put_float(std::vector<uint8_t>& p, size_t offset, float value) {
    uint32_t bits;
    std::memcpy(&bits, &value, sizeof(bits));
    p[offset] = static_cast<uint8_t>(bits >> 24);
    p[offset + 1] = static_cast<uint8_t>(bits >> 16);
    p[offset + 2] = static_cast<uint8_t>(bits >> 8);
    p[offset + 3] = static_cast<uint8_t>(bits);
}
static std::vector<uint8_t> fixture_bytes(const std::string& text) {
    std::vector<uint8_t> result;
    for (size_t i = 0; i < text.size(); i += 2)
        result.push_back(static_cast<uint8_t>(std::stoul(text.substr(i, 2), nullptr, 16)));
    return result;
}

int main() {
    try {
        Mapping mapping;
        mapping.target = {-1920, 0, 1920, 1080};
        Mapper mapper(mapping);
        Sample sample;
        sample.x = 1; sample.y = 1; sample.phase = Phase::down; sample.pressure = 0.5;
        auto p = mapper.map(sample);
        require(near(p.x, -1) && near(p.y, 1079), "negative-display and inclusive edge mapping");
        require(near(p.pressure, 0.5), "linear pressure");
        sample.phase = Phase::up;
        require(mapper.map(sample).pressure == 0, "lift forces zero pressure");

        mapping.rotation = 90; mapping.source = {0.25, 0.25, 0.5, 0.5};
        mapping.pressure_gamma = 2;
        Mapper rotated(mapping);
        sample.x = 0.25; sample.y = 0.75; sample.phase = Phase::down;
        sample.tilt = true; sample.tilt_x = 30; sample.tilt_y = -20;
        p = rotated.map(sample);
        require(near(p.x, -1920) && near(p.y, 0), "cropped rotated corner");
        require(near(p.tilt_x, 20) && near(p.tilt_y, 30), "rotate tilt with coordinates");
        require(near(p.pressure, 0.25), "gamma pressure");
        mapping.pressure_curve = {{0,0},{0.25,0.1},{0.75,0.9},{1,1}};
        sample.pressure = 0.5;
        require(near(Mapper(mapping).map(sample).pressure, 0.5), "custom curve overrides gamma");
        sample.pressure = 0.25;
        require(near(Mapper(mapping).map(sample).pressure, 0.1), "curve reaches exact knot");
        sample.pressure = 0;
        require(Mapper(mapping).map(sample).pressure == 0, "custom curve zero boundary");
        sample.pressure = 1;
        require(Mapper(mapping).map(sample).pressure == 1, "custom curve full pressure boundary");
        mapping.tilt_scale = 2;
        require(near(Mapper(mapping).map(sample).tilt_y, 60), "tilt sensitivity after rotation");
        mapping.tilt_enabled = false;
        p = Mapper(mapping).map(sample);
        require(!p.source.tilt && p.tilt_x == 0 && p.tilt_y == 0, "disable tilt capability and values");
        mapping.pressure_curve[2].second = 0;
        reject([&] { mapping.validate(); }, "reject decreasing curve");
        mapping.pressure_curve = {{0,0},{0,0.5},{1,1}};
        reject([&] { mapping.validate(); }, "reject duplicate curve inputs");

        mapping = Mapping{}; mapping.target = {0, 0, 1001, 1001}; mapping.relative = true;
        Mapper relative(mapping);
        relative.seed(100, 100);
        sample.x = 0.5; sample.y = 0.5; sample.phase = Phase::hover;
        require(near(relative.map(sample).x, 100), "relative first hover preserves cursor");
        sample.x = 0.6;
        require(near(relative.map(sample).x, 200), "relative displacement");
        sample.phase = Phase::exit;
        relative.map(sample);
        sample.phase = Phase::hover; sample.x = 0;
        require(near(relative.map(sample).x, 200), "relative reentry does not jump");
        sample.x = 1;
        require(near(relative.map(sample).x, 1000), "relative clamps target");
        mapping.position_steps_x = 11;
        Mapper quantized(mapping);
        quantized.seed(100, 100);
        sample.x = 0; sample.phase = Phase::hover;
        quantized.map(sample);
        for (int i = 1; i <= 6; ++i) {
            sample.x = i * 0.01;
            p = quantized.map(sample);
        }
        require(near(p.x, 200), "quantized relative mapping preserves sub-step accumulated movement");
        mapping.relative = false;
        sample.x = 0.251;
        require(near(Mapper(mapping).map(sample).x, 300), "coordinate step quantization");
        sample.x = 1;
        require(near(Mapper(mapping).map(sample).x, 1000), "quantized inclusive endpoint");
        mapping.position_steps_y = 1;
        reject([&] { mapping.validate(); }, "reject invalid position steps");

        sample.x = std::numeric_limits<double>::quiet_NaN();
        reject([&] { mapper.map(sample); }, "reject NaN before OS conversion");
        mapping.source.width = 0;
        reject([&] { mapping.validate(); }, "reject zero crop");
        mapping = Mapping{}; mapping.pressure_gamma = std::numeric_limits<double>::infinity();
        reject([&] { mapping.validate(); }, "reject infinite gamma");

        std::vector<uint8_t> payload(48);
        payload[7] = 1; payload[15] = 20; payload[44] = 1; payload[45] = 2;
        put_float(payload, 16, 0.25f); put_float(payload, 20, 0.75f);
        put_float(payload, 24, 0.5f); put_float(payload, 28, -45.0f);
        payload[43] = 3;
        const auto decoded = decode_pen(payload);
        require(decoded.sequence == 1 && decoded.timestamp_ns == 20 && decoded.eraser &&
                decoded.barrel && decoded.secondary && near(decoded.tilt_x, -45), "big-endian pen decode");
        const auto golden = decode_pen(fixture_bytes(
            "000000000000000100000000499602d23e8000003f4000003f000000c1f0000042340000000000000000000101010000"));
        require(golden.sequence == 1 && golden.timestamp_ns == 1234567890 &&
                near(golden.x, 0.25) && near(golden.y, 0.75) && near(golden.pressure, 0.5) &&
                near(golden.tilt_x, -30) && near(golden.tilt_y, 45) && golden.barrel && !golden.eraser,
                "protocol/vectors.json golden sample");
        SequenceGuard guard;
        guard.accept(decoded);
        reject([&] { guard.accept(decoded); }, "reject pen replay");
        auto next = decoded; next.sequence = 2; next.timestamp_ns = 19;
        reject([&] { guard.accept(next); }, "reject reversed timestamp");
        next.timestamp_ns = 20;
        guard.accept(next);
        require(true, "same timestamp accepted for batched samples");
        guard.accept_ping(100);
        reject([&] { guard.accept_ping(100); }, "reject replayed heartbeat");
        ContactGuard contact;
        auto tip = decoded;
        tip.phase = Phase::move;
        reject([&] { contact.accept(tip); }, "reject move before down");
        tip.phase = Phase::up;
        reject([&] { contact.accept(tip); }, "reject up before down");
        tip.phase = Phase::down;
        contact.accept(tip);
        reject([&] { contact.accept(tip); }, "reject duplicate down");
        tip.phase = Phase::hover;
        reject([&] { contact.accept(tip); }, "reject hover while touching");
        tip.phase = Phase::move; tip.eraser = false;
        reject([&] { contact.accept(tip); }, "reject tool swap during contact");
        tip.phase = Phase::cancel;
        contact.accept(tip);
        tip.phase = Phase::down;
        contact.accept(tip);
        tip.phase = Phase::up;
        contact.accept(tip);
        tip.phase = Phase::hover;
        contact.accept(tip);
        require(true, "cancel releases contact and allows new tool");
        payload[47] = 1;
        reject([&] { decode_pen(payload); }, "reject reserved bytes");
        payload[47] = 0; put_float(payload, 16, std::numeric_limits<float>::infinity());
        reject([&] { decode_pen(payload); }, "reject nonfinite wire coordinates");
        payload.resize(47);
        reject([&] { decode_pen(payload); }, "reject truncated payload");
        require(frame({1, 1}) == std::vector<uint8_t>({0, 0, 0, 2, 1, 1}), "network frame header");
        reject([&] { frame({1}); }, "reject body without message type");
        reject([&] { frame(std::vector<uint8_t>(4097)); }, "frame length bound");
        require(constant_time_equal("secret", "secret") && !constant_time_equal("secret", "secreT") &&
                !constant_time_equal("secret", "secret!"), "authentication byte comparison");

        Config config; config.token = std::string(64, 'a');
        config.validate();
        set_config(config, "mapping.target", "-1920,0,1920,1080");
        require(!config.target_virtual_desktop && config.mapping.target.x == -1920, "parse target");
        reject([&] { set_config(config, "port", "65536"); }, "reject port overflow");
        reject([&] { set_config(config, "mapping.source", "0,0,1,1,"); }, "reject malformed crop");
        reject([&] { set_config(config, "unknown", "1"); }, "reject unknown config key");
        require(config_values(config, false).at("token") == "<redacted>", "redact token by default");
        const auto revision = config_revision(config);
        auto changed = config;
        changed.token = std::string(64, 'b');
        require(config_revision(changed) != revision, "configuration revision detects hidden credential changes");
        changed = config; changed.mapping.pressure_gamma = 0.9;
        require(config_revision(changed) != revision && config_revision(config) == revision,
                "configuration revision is stable and detects mapping changes");
        require(json_escape("a\n\"") == "\"a\\u000a\\\"\"", "safe JSON diagnostics");
        set_config(config, "mapping.pressure_curve", "0,0;0.5,0.25;1,1");
        set_config(config, "mapping.position_steps_x", "4096");
        set_config(config, "mapping.tilt_enabled", "false");
        set_config(config, "mapping.tilt_scale", "0.5");
        config.validate();
        require(config_values(config, false).at("mapping.pressure_curve") == "0,0;0.5,0.25;1,1",
                "curve text round-trip");
        reject([&] { set_config(config, "mapping.pressure_curve", "0,0;1,1;"); }, "reject trailing empty knot");
        reject([&] { set_config(config, "mapping.position_steps_x", "4096.5"); }, "reject fractional steps");
        reject([&] { set_config(config, "mapping.tilt_enabled", "yes"); }, "reject invalid boolean");
        const auto directory = std::filesystem::temp_directory_path() /
            ("reflection-profiles-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
        struct Cleanup {
            std::filesystem::path path;
            ~Cleanup() { std::error_code error; std::filesystem::remove_all(path, error); }
        } cleanup{directory};
        const auto path = directory / "server.conf";
        save_config(config, path);
        auto restored = load_config(path);
        require(restored.mapping.pressure_curve == config.mapping.pressure_curve &&
                restored.mapping.position_steps_x == 4096 && !restored.mapping.tilt_enabled,
                "full configuration persistence");
        save_profile(config, path, "Drawing");
        require(list_profiles(path) == std::vector<std::string>{"Drawing"}, "profile listing");
        reject([&] { save_profile(config, path, "Drawing"); }, "profile overwrite requires explicit replace");
        reject([&] { save_profile(config, path, "../secret"); }, "profile traversal rejected");
        {
            // Windows needs this read handle closed before the replacement checks below.
            std::ifstream profile_file(directory / "server.conf.profiles" / "profile-Drawing.conf");
            const std::string profile_text((std::istreambuf_iterator<char>(profile_file)), {});
            require(profile_text.find("token") == std::string::npos && profile_text.find("bind") == std::string::npos,
                    "profiles never store pairing credentials or transport");
        }
        config.token = std::string(64, 'b'); config.bind = "0.0.0.0";
        config.mapping.pressure_curve.clear(); config.mapping.position_steps_x = 0;
        restored = load_profile(config, path, "Drawing");
        require(restored.mapping.position_steps_x == 4096 && restored.token == config.token &&
                restored.bind == "0.0.0.0", "profile load restores mapping while preserving connection");
        save_profile(config, path, "Drawing", true);
        require(load_profile(config, path, "Drawing").mapping.pressure_curve.empty(), "explicit profile replacement");
        config.mapping.pressure_curve = {{0,0},{0.5,0.2},{0.5000000000000001,0.8},{1,1}};
        save_config(config, path);
        require(load_config(path).mapping.pressure_curve == config.mapping.pressure_curve,
                "persistence preserves adjacent representable curve inputs");
        {
            std::ofstream malformed(directory / "server.conf.profiles" / "profile-Invalid.conf");
            malformed << "token=" << config.token << '\n';
        }
        reject([&] { load_profile(config, path, "Invalid"); }, "reject secret injection through profile");
        delete_profile(path, "Drawing");
        reject([&] { load_profile(config, path, "Drawing"); }, "deleted profile cannot load");
        std::cout << checks << " core checks passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
