#include "reflection/config.hpp"
#include "reflection/protocol.hpp"
#include <cstring>
#include <functional>
#include <iostream>
#include <limits>

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
        sample.tilt_x = 30; sample.tilt_y = -20;
        p = rotated.map(sample);
        require(near(p.x, -1920) && near(p.y, 0), "cropped rotated corner");
        require(near(p.tilt_x, 20) && near(p.tilt_y, 30), "rotate tilt with coordinates");
        require(near(p.pressure, 0.25), "gamma pressure");

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
        require(json_escape("a\n\"") == "\"a\\u000a\\\"\"", "safe JSON diagnostics");
        std::cout << checks << " core checks passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
