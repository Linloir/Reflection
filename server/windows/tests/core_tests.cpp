#include "reflection/config.hpp"
#include "reflection/protocol.hpp"
#include "reflection/display_mapping.hpp"
#include "reflection/discovery.hpp"
#include "reflection/raw_touchpad.hpp"
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
        auto calibration = fixture_bytes("00000000000000013b449ba6");
        const auto decoded_calibration = decode_tablet_calibration(calibration, 0);
        require(decoded_calibration.sequence == 1 && std::abs(decoded_calibration.noise - .003) < 1e-8,
                "cross-platform tablet calibration vector");
        reject([&] { decode_tablet_calibration(calibration, 1); }, "tablet calibration replay rejected");
        auto bad_calibration = calibration; put_float(bad_calibration, 8, std::numeric_limits<float>::quiet_NaN());
        reject([&] { decode_tablet_calibration(bad_calibration, 0); }, "tablet calibration NaN rejected");
        bad_calibration = calibration; put_float(bad_calibration, 8, .03f);
        reject([&] { decode_tablet_calibration(bad_calibration, 0); }, "tablet calibration bound enforced");
        bad_calibration = calibration; put_float(bad_calibration, 8, .00001f);
        require(decode_tablet_calibration(bad_calibration, 0).noise == .00001, "float lower bound normalized");
        bad_calibration = calibration; put_float(bad_calibration, 8, 0);
        require(decode_tablet_calibration(bad_calibration, 0).noise == 0, "tablet calibration reset sentinel");
        const auto calibration_ack = tablet_calibration_ack(1, .003);
        require(calibration_ack.size() == 13 && calibration_ack[0] == 1 &&
                std::vector<uint8_t>(calibration_ack.begin() + 1, calibration_ack.end()) == calibration,
                "tablet calibration acknowledgement encoding");
        auto touch = fixture_bytes("000000000000000100000000000004d241480000c1a000000402000000000000");
        const auto decoded_touch = decode_touchpad(touch);
        require(decoded_touch.sequence == 1 && decoded_touch.timestamp_ns == 1234 &&
            decoded_touch.kind == TouchpadKind::scroll && decoded_touch.fingers == 2 &&
            decoded_touch.x == 12.5 && decoded_touch.y == -20, "cross-platform touchpad wire vector");
        TouchpadSequenceGuard touch_guard;
        touch_guard.accept(decoded_touch);
        reject([&] { touch_guard.accept(decoded_touch); }, "touchpad replay rejected");
        auto malformed_touch = touch;
        malformed_touch[31] = 1;
        reject([&] { decode_touchpad(malformed_touch); }, "touchpad reserved bytes rejected");
        malformed_touch = touch; put_float(malformed_touch, 16, std::numeric_limits<float>::quiet_NaN());
        reject([&] { decode_touchpad(malformed_touch); }, "touchpad NaN rejected");
        malformed_touch = touch; malformed_touch[24] = 7;
        reject([&] { decode_touchpad(malformed_touch); }, "touchpad swipe count and direction validated");
        const std::string touch_caps = "{\"touchpad\":1}";
        require(touchpad_capability({touch_caps.begin(), touch_caps.end()}), "touchpad capability opt-in");
        const std::string duplicate_touch_caps = "{\"touchpad\":1,\"touchpad\":0}";
        require(!touchpad_capability({duplicate_touch_caps.begin(), duplicate_touch_caps.end()}),
                "duplicate touchpad capability rejected");
        const std::string raw_caps = "{\"rawTouchpad\":1,\"rawTouchpadWidth\":10000,\"rawTouchpadHeight\":6000}";
        Config native_config;
        require(native_config.native_touchpad, "native touchpad defaults to available backend");
        set_config(native_config, "touchpad.native", "false");
        require(!native_config.native_touchpad && config_values(native_config, false).at("touchpad.native") == "false",
                "native touchpad fallback can be selected through configuration");
        reject([&] { set_config(native_config, "touchpad.native", "automatic"); }, "native touchpad setting rejects ambiguous values");
        const auto raw_size = raw_touchpad_capability({raw_caps.begin(), raw_caps.end()});
        require(raw_size && raw_size->width == 10000 && raw_size->height == 6000, "raw touchpad physical geometry negotiation");
        for (const auto& bad : {
             "{\"rawTouchpad\":1,\"rawTouchpadWidth\":1,\"rawTouchpadHeight\":6000}",
             "{\"rawTouchpad\":1,\"rawTouchpadWidth\":10000.0,\"rawTouchpadHeight\":6000}",
             "{\"rawTouchpad\":1,\"rawTouchpadWidth\":10000,\"rawTouchpadWidth\":10000,\"rawTouchpadHeight\":6000}",
             "{\"rawTouchpad\":1,\"nested\":{\"rawTouchpadWidth\":10000},\"rawTouchpadHeight\":6000}",
             "{\"rawTouchpad\":0,\"rawTouchpadWidth\":10000,\"rawTouchpadHeight\":6000}"}) {
            const std::string value(bad);
            require(!raw_touchpad_capability({value.begin(), value.end()}), "raw capability rejects invalid geometry or opt-in");
        }
        require(raw_touchpad_ack({10000, 6000}) == fixture_bytes("010000271000001770"), "raw geometry acknowledgement vector");
        reject([] { raw_touchpad_ack({0, 6000}); }, "raw geometry acknowledgement rejects zero size");
        const auto raw_vector = fixture_bytes("000000000000000100000000000004d2010000000000002a010100003e8000003f400000");
        const auto raw_first = decode_raw_touchpad(raw_vector);
        require(raw_first.sequence == 1 && raw_first.timestamp_ns == 1234 && raw_first.contacts.size() == 1 &&
            raw_first.contacts[0].id == 42 && raw_first.contacts[0].x == .25 && raw_first.contacts[0].y == .75,
            "raw contact cross-platform wire vector");
        auto invalid_raw = raw_vector; invalid_raw[18] = 1;
        reject([&] { decode_raw_touchpad(invalid_raw); }, "raw frame reserved bits rejected");
        invalid_raw = raw_vector; invalid_raw[24] = 4;
        reject([&] { decode_raw_touchpad(invalid_raw); }, "raw unknown contact phase rejected");
        invalid_raw = raw_vector; put_float(invalid_raw, 28, std::numeric_limits<float>::quiet_NaN());
        reject([&] { decode_raw_touchpad(invalid_raw); }, "raw nonfinite coordinate rejected");
        invalid_raw = raw_vector; put_float(invalid_raw, 32, 1.01f);
        reject([&] { decode_raw_touchpad(invalid_raw); }, "raw coordinate bounds enforced");
        invalid_raw = raw_vector; invalid_raw[17] = 1;
        reject([&] { decode_raw_touchpad(invalid_raw); }, "raw cancel cannot carry contacts");
        invalid_raw = raw_vector; invalid_raw[16] = 2;
        invalid_raw.insert(invalid_raw.end(), raw_vector.begin() + 20, raw_vector.end());
        reject([&] { decode_raw_touchpad(invalid_raw); }, "raw duplicate contact IDs rejected");
        RawTouchpadState raw_state;
        require(raw_state.accept(raw_first).front().slot == 1, "raw pointer IDs use bounded Windows slots");
        reject([&] { raw_state.accept(raw_first); }, "raw replay rejected");
        auto raw_second = raw_first; raw_second.sequence = 2;
        raw_second.contacts[0].phase = RawTouchpadPhase::update;
        raw_second.contacts.push_back({0xffffffffu, RawTouchpadPhase::down, true, .8, .6});
        const auto raw_two = raw_state.accept(raw_second);
        require(raw_two[0].slot == 1 && raw_two[1].slot == 2, "raw contacts retain native slots across full frames");
        auto raw_missing = raw_second; raw_missing.sequence = 3; raw_missing.contacts.erase(raw_missing.contacts.begin());
        raw_missing.contacts[0].phase = RawTouchpadPhase::update;
        reject([&] { raw_state.accept(raw_missing); }, "raw frame cannot silently drop an active contact");
        auto raw_third = raw_second; raw_third.sequence = 3;
        raw_third.contacts[0].phase = RawTouchpadPhase::up; raw_third.contacts[1].phase = RawTouchpadPhase::update;
        require(raw_state.accept(raw_third).front().source.phase == RawTouchpadPhase::up && raw_state.active().size() == 1,
                "raw terminal contact survives delivery before removal");
        auto raw_fourth = raw_third; raw_fourth.sequence = 4; raw_fourth.contacts.erase(raw_fourth.contacts.begin());
        raw_fourth.contacts.push_back({7, RawTouchpadPhase::down, true, .2, .4});
        require(raw_state.accept(raw_fourth).back().slot == 1, "raw released slot can be reused next frame");
        auto raw_cancel = decode_raw_touchpad(fixture_bytes("000000000000000500000000000004d200010000"));
        require(raw_state.accept(raw_cancel).empty() && raw_state.active().empty(), "raw cancel clears all contacts");
        auto raw_restart = raw_first; raw_restart.sequence = 6;
        raw_restart.contacts[0].phase = RawTouchpadPhase::update;
        reject([&] { raw_state.accept(raw_restart); }, "raw orphan update after cancellation rejected");
        raw_restart.contacts[0].phase = RawTouchpadPhase::down;
        require(raw_state.accept(raw_restart).front().slot == 1, "raw new gesture after cancellation starts cleanly");
        raw_state.cancel();
        raw_restart.sequence = 7; raw_restart.contacts[0].phase = RawTouchpadPhase::up;
        reject([&] { raw_state.accept(raw_restart); }, "raw release from pen proximity cannot be revived by stale up");
        auto raw_backwards = raw_first; raw_backwards.sequence = 7; raw_backwards.timestamp_ns = 1;
        reject([&] { raw_state.accept(raw_backwards); }, "raw timestamp regression rejected after explicit release");
        require(base64_encode({0, 1, 2, 3, 255}) == "AAECA/8=", "canonical base64 encode");
        require(base64_decode("AAECA/8=") == std::vector<uint8_t>({0, 1, 2, 3, 255}), "canonical base64 decode");
        reject([] { base64_decode("AAECA/9="); }, "base64 rejects unused nonzero bits");
        reject([] { base64_decode("AA=A"); }, "base64 rejects internal padding");
        reject([] { base64_decode("AA==AAAA"); }, "base64 rejects trailing content");
        auto discovery = discovery_query("REFLECTION-DISCOVER/1 " + std::string(32, 'a') + " " + std::string(32, 'b'));
        require(discovery && discovery->nonce == std::string(32, 'a') && discovery->device_id == std::string(32, 'b'),
                "per-device discovery query");
        require(!discovery_query("REFLECTION-DISCOVER/1 " + std::string(32, 'a') + " extra"),
                "discovery query rejects malformed identity");
        std::vector<uint8_t> modulus(256, 1); modulus[0] = 0x80;
        const auto announcement = "REFLECTION-DEVICE/1\n" + std::string(32, 'a') + "\nVGFibGV0\n" +
            base64_encode(modulus) + "\nAQAB\n" + std::string(32, 'b');
        auto device = parse_device_announcement(announcement);
        require(device && device->name == "Tablet" && device->modulus == modulus, "strict device announcement decode");
        require(!parse_device_announcement(announcement + "\nextra"), "reject extra announcement fields");
        std::vector<uint8_t> pairing_body{1, 4};
        pairing_body.insert(pairing_body.end(), announcement.begin(), announcement.end());
        const auto pairing_device = bluetooth_pairing_request(pairing_body);
        require(pairing_device && pairing_device->name == "Tablet", "Bluetooth bootstrap accepts discovery identity");
        pairing_body[0] = 2;
        require(!bluetooth_pairing_request(pairing_body), "Bluetooth bootstrap rejects unknown protocol version");
        pairing_body[0] = 1; pairing_body[1] = 0x23;
        require(!bluetooth_pairing_request(pairing_body), "Bluetooth bootstrap never accepts input frames");
        require(!bluetooth_pairing_request({1, 4}), "Bluetooth bootstrap rejects missing identity");
        require(!bluetooth_pairing_request(std::vector<uint8_t>(1201)), "Bluetooth bootstrap bounds untrusted body");
        auto bad_announcement = announcement;
        bad_announcement.replace(bad_announcement.find("VGFibGV0"), 8, "Cg==");
        require(!parse_device_announcement(bad_announcement), "reject control characters in device names");
        bad_announcement = announcement;
        bad_announcement.replace(bad_announcement.find("AQAB"), 4, "Aw==");
        require(!parse_device_announcement(bad_announcement), "reject unsupported RSA exponent");
        const std::vector<uint8_t> usb_device = {10, 9, 10, 3, 'a', 'b', 'c', 16, 8, 56, 1};
        require(adb_usb_devices(usb_device) == std::vector<std::string>{"abc"}, "ADB USB type is authoritative without bus path");
        auto network_device = usb_device; network_device.back() = 2;
        require(adb_usb_devices(network_device).empty(), "ADB network transport never provisions USB credentials");
        auto unauthorized_device = usb_device; unauthorized_device[8] = 3;
        require(adb_usb_devices(unauthorized_device).empty(), "ADB unauthorized device is not provisioned");
        auto malformed_devices = usb_device; malformed_devices.pop_back();
        reject([&] { adb_usb_devices(malformed_devices); }, "ADB truncated protobuf rejected");
        malformed_devices = usb_device; malformed_devices[1] = 11; malformed_devices.push_back(56); malformed_devices.push_back(1);
        reject([&] { adb_usb_devices(malformed_devices); }, "ADB duplicate type field rejected");
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
        {
            std::vector<Display> screens{{"main", {0, 0, 1920, 1080}, true},
                {"left", {-1280, -200, 1280, 1024}, false}};
            DisplayMapping live(config, [&] { return screens; });
            Sample point; point.phase = Phase::hover;
            auto mapped = live.map(point);
            require(near(mapped.x, -1280) && near(mapped.y, -200), "all-display mapping follows negative OS layout");
            point.x = 1; point.y = 1;
            mapped = live.map(point);
            require(near(mapped.x, 1919) && near(mapped.y, 1079), "all-display union preserves monitor arrangement");
            for (const unsigned steps : {0u, 4096u}) {
                auto locked_config = config;
                set_config(locked_config, "mapping.target", "display:left");
                locked_config.mapping.position_steps_x = locked_config.mapping.position_steps_y = steps;
                DisplayMapping locked(locked_config, [&] { return screens; });
                for (const double edge : {1.0, std::nextafter(1.0, 0.0), 1.0 - 1e-7}) {
                    Sample edge_point; edge_point.phase = Phase::hover; edge_point.x = edge_point.y = edge;
                    const auto edge_mapped = locked.map(edge_point);
                    // Windows monitor rectangles are right/bottom exclusive. Include the backend's rounding.
                    require(std::llround(edge_mapped.x) <= -1 && std::llround(edge_mapped.y) <= 823,
                            "locked negative display right/bottom edge cannot spill after pixel rounding");
                }
            }
            live.request(4, "main");
            point.x = 0.5; point.y = 0.5; point.phase = Phase::down;
            const auto down = live.map(point);
            live.request(4, "left");
            require(live.status().find("\"pending\":true") != std::string::npos &&
                    live.selected_display() == "main", "switch during stroke is deferred");
            point.phase = Phase::move;
            require(near(live.map(point).x, down.x), "deferred switch cannot tear the current stroke");
            point.phase = Phase::up;
            require(near(live.map(point).x, down.x), "pen up is mapped using original target");
            point.phase = Phase::hover;
            require(live.map(point).x < 0 && live.selected_display() == "left", "switch applies after pen up");
            point.phase = Phase::down;
            const auto before_unplug = live.map(point);
            screens.pop_back(); live.refresh();
            const auto pending_topology = live.status();
            require(live.selected_display() == "left" && pending_topology.find("\"id\":\"left\"") != std::string::npos &&
                    pending_topology.find("\"pending\":true") != std::string::npos,
                    "unplug during contact retains consistent selected display and active topology");
            point.phase = Phase::move;
            require(near(live.map(point).x, before_unplug.x), "hot-unplug cannot remap an active stroke");
            point.phase = Phase::up;
            require(near(live.map(point).x, before_unplug.x), "hot-unplug maps up on original display");
            require(live.selected_display() == "main" && live.status().find("\"fallback\":true") != std::string::npos,
                    "unplugged selected monitor falls back to primary and reports it");
            require(live.status().find("\"id\":\"left\"") == std::string::npos,
                    "active topology commits atomically with fallback selection after release");
            screens.push_back({"left", {-1280, -200, 1280, 1024}, false}); live.refresh();
            require(live.selected_display() == "left", "reconnected selected display restores mapping");
            live.request(1);
            require(live.selected_display() == "main", "next display wraps deterministically");
            live.request(2);
            require(live.selected_display() == "left", "previous display wraps deterministically");
            reject([&] { live.request(4, "missing"); }, "unknown display selection rejected");
            live.request(3);
            require(live.status().find("\"mode\":\"all\"") != std::string::npos, "all command restores OS layout");
            auto bytes = [](const std::string& value) { return std::vector<uint8_t>(value.begin(), value.end()); };
            require(mapping_capability(bytes("{\"platform\":\"android\", \"mappingControl\" : 1}")), "mapping extension opt-in");
            require(!mapping_capability(bytes("{\"nested\":{\"mappingControl\":1}}")) &&
                    !mapping_capability(bytes("{\"mappingControl\":10}")) &&
                    !mapping_capability(bytes("{\"mappingControl\":\"1\"}")), "mapping capability must be top-level version one");
            require(!mapping_capability(bytes("{\"mappingControl\":1}junk")) &&
                    !mapping_capability(bytes("{\"mappingControl\":1,\"mappingControl\":0}")) &&
                    !mapping_capability(bytes("{\"mappingControl\":1,\"bad\":01}")) &&
                    !mapping_capability(bytes("{\"mappingCo\\ntrol\":1}")),
                    "entire capability JSON is validated without duplicate or escaped-key confusion");
            require(mapping_capability(bytes("{\"mapping\\u0043ontrol\":1,\"other\":[true,null,{\"x\":-1.5e2}]}")),
                    "valid nested unknown JSON fields and escaped capability keys are accepted");
            std::vector<uint8_t> request{0,0,0,0,0,0,0,1,1};
            require(decode_mapping_request(request, 0).action == 1, "decode mapping request");
            reject([&] { decode_mapping_request(request, 1); }, "reject replayed mapping switch");
            request.push_back('x');
            reject([&] { decode_mapping_request(request, 0); }, "reject unexpected request payload");
            request[8] = 4;
            require(decode_mapping_request(request, 0).display == "x", "decode selected display identifier");
            request[9] = 0xff;
            reject([&] { decode_mapping_request(request, 0); }, "reject invalid UTF8 display identifier");
        }
        set_config(config, "mapping.target", "display:main");
        set_config(config, "mapping.show_cursor", "false");
        require(config_values(config, false).at("mapping.target") == "display:main" && !config.show_cursor,
                "display target and native cursor policy round-trip");
        reject([&] { set_config(config, "mapping.show_cursor", "yes"); }, "reject invalid cursor policy");
        require(config.stabilization.adaptive_noise, "old configurations default to adaptive noise estimation");
        set_config(config, "stabilization.adaptive_noise", "false");
        require(!config.stabilization.adaptive_noise && config_values(config, false).at("stabilization.adaptive_noise") == "false",
                "adaptive noise can be disabled independently of smoothing");
        reject([&] { set_config(config, "stabilization.adaptive_noise", "yes"); }, "reject invalid adaptive noise policy");
        require(!config.stabilization.independent_axes, "direction-specific smoothing is opt-in");
        set_config(config, "stabilization.independent_axes", "true");
        require(config.stabilization.independent_axes && config_values(config,false).at("stabilization.independent_axes")=="true",
                "independent correction budgets persist separately from adaptive noise");
        reject([&] { set_config(config, "stabilization.independent_axes", "yes"); }, "reject invalid independent-axis policy");
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
