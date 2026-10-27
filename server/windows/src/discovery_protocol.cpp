#include "reflection/discovery.hpp"
#include <sstream>

namespace reflection {
namespace {
bool hex32(const std::string& text) {
    return text.size() == 32 && text.find_first_not_of("0123456789abcdef") == std::string::npos;
}
bool printable_utf8(const std::vector<uint8_t>& text) {
    if (text.empty() || text.size() > 128) return false;
    for (size_t i = 0; i < text.size();) {
        const auto first = text[i++];
        if (first < 0x20 || first == 0x7f) return false;
        if (first < 0x80) continue;
        unsigned count, value, minimum;
        if (first >= 0xc2 && first <= 0xdf) { count = 1; value = first & 0x1f; minimum = 0x80; }
        else if (first >= 0xe0 && first <= 0xef) { count = 2; value = first & 0xf; minimum = 0x800; }
        else if (first >= 0xf0 && first <= 0xf4) { count = 3; value = first & 7; minimum = 0x10000; }
        else return false;
        if (i + count > text.size()) return false;
        while (count--) {
            const auto next = text[i++];
            if ((next & 0xc0) != 0x80) return false;
            value = (value << 6) | (next & 0x3f);
        }
        if (value < minimum || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff)) return false;
    }
    return true;
}
uint64_t varint(const std::vector<uint8_t>& bytes, size_t& offset, size_t end) {
    uint64_t result = 0;
    for (unsigned shift = 0; shift < 64 && offset < end; shift += 7) {
        const auto value = bytes[offset++];
        if (shift == 63 && (value & 0xfe)) throw std::invalid_argument("oversized protobuf varint");
        result |= uint64_t(value & 0x7f) << shift;
        if (!(value & 0x80)) return result;
    }
    throw std::invalid_argument("truncated protobuf varint");
}
void skip_field(const std::vector<uint8_t>& bytes, size_t& offset, size_t end, unsigned wire) {
    if (wire == 0) { varint(bytes, offset, end); return; }
    uint64_t size;
    if (wire == 1) size = 8;
    else if (wire == 5) size = 4;
    else if (wire == 2) size = varint(bytes, offset, end);
    else throw std::invalid_argument("unsupported protobuf field");
    if (size > end - offset) throw std::invalid_argument("truncated protobuf field");
    offset += static_cast<size_t>(size);
}
}

std::string base64_encode(const std::vector<uint8_t>& bytes) {
    constexpr char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string result;
    for (size_t i = 0; i < bytes.size(); i += 3) {
        const uint32_t bits = uint32_t(bytes[i]) << 16 |
            (i + 1 < bytes.size() ? uint32_t(bytes[i + 1]) << 8 : 0) |
            (i + 2 < bytes.size() ? bytes[i + 2] : 0);
        result += alphabet[(bits >> 18) & 63]; result += alphabet[(bits >> 12) & 63];
        result += i + 1 < bytes.size() ? alphabet[(bits >> 6) & 63] : '=';
        result += i + 2 < bytes.size() ? alphabet[bits & 63] : '=';
    }
    return result;
}

std::vector<uint8_t> base64_decode(const std::string& text) {
    if (text.empty() || text.size() > 1024 || text.size() % 4) throw std::invalid_argument("invalid base64 length");
    const std::string alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::vector<uint8_t> result;
    for (size_t i = 0; i < text.size(); i += 4) {
        uint32_t bits = 0;
        unsigned padding = 0;
        for (size_t j = 0; j < 4; ++j) {
            const char ch = text[i + j];
            if (ch == '=') {
                if (j < 2 || i + 4 != text.size()) throw std::invalid_argument("invalid base64 padding");
                ++padding; bits <<= 6;
            } else {
                const auto digit = alphabet.find(ch);
                if (digit == std::string::npos || padding) throw std::invalid_argument("invalid base64 character");
                bits = (bits << 6) | static_cast<uint32_t>(digit);
            }
        }
        result.push_back(static_cast<uint8_t>(bits >> 16));
        if (padding < 2) result.push_back(static_cast<uint8_t>(bits >> 8));
        if (padding < 1) result.push_back(static_cast<uint8_t>(bits));
    }
    if (base64_encode(result) != text) throw std::invalid_argument("noncanonical base64");
    return result;
}

std::optional<DeviceAnnouncement> parse_device_announcement(const std::string& text) {
    if (text.size() > 1024) return {};
    std::istringstream stream(text);
    std::vector<std::string> lines;
    for (std::string line; std::getline(stream, line);) lines.push_back(line);
    if (lines.size() != 6 || lines[0] != "REFLECTION-DEVICE/1" || !hex32(lines[1]) || !hex32(lines[5]))
        return {};
    try {
        auto name = base64_decode(lines[2]);
        auto modulus = base64_decode(lines[3]);
        auto exponent = base64_decode(lines[4]);
        if (!printable_utf8(name) || modulus.size() != 256 || !(modulus[0] & 0x80) ||
            !(modulus.back() & 1) || exponent != std::vector<uint8_t>{1, 0, 1}) return {};
        return DeviceAnnouncement{lines[1], std::string(name.begin(), name.end()), lines[5],
                                  std::move(modulus), std::move(exponent)};
    } catch (const std::invalid_argument&) { return {}; }
}

std::optional<DeviceAnnouncement> bluetooth_pairing_request(const std::vector<uint8_t>& body) {
    if (body.size() < 3 || body.size() > 1200 || body[0] != 1 || body[1] != 4) return {};
    return parse_device_announcement(std::string(body.begin() + 2, body.end()));
}

std::optional<DiscoveryQuery> discovery_query(const std::string& text) {
    const std::string prefix = "REFLECTION-DISCOVER/1 ";
    if (text.rfind(prefix, 0) != 0) return {};
    const auto tail = text.substr(prefix.size());
    if (hex32(tail)) return DiscoveryQuery{tail, ""};
    if (tail.size() == 65 && tail[32] == ' ' && hex32(tail.substr(0, 32)) && hex32(tail.substr(33)))
        return DiscoveryQuery{tail.substr(0, 32), tail.substr(33)};
    return {};
}

std::vector<std::string> adb_usb_devices(const std::vector<uint8_t>& bytes) {
    if (bytes.size() > 65536) throw std::invalid_argument("ADB device list too large");
    std::vector<std::string> result;
    size_t offset = 0;
    while (offset < bytes.size()) {
        const auto tag = varint(bytes, offset, bytes.size());
        if (tag != 10) { skip_field(bytes, offset, bytes.size(), static_cast<unsigned>(tag & 7)); continue; }
        const auto size = varint(bytes, offset, bytes.size());
        if (size > bytes.size() - offset) throw std::invalid_argument("truncated ADB device");
        const size_t end = offset + static_cast<size_t>(size);
        std::string serial;
        uint64_t state = 0, type = 0;
        bool seen_serial = false, seen_state = false, seen_type = false;
        while (offset < end) {
            const auto field = varint(bytes, offset, end);
            if (field == 10) {
                if (seen_serial) throw std::invalid_argument("duplicate ADB serial");
                seen_serial = true;
                const auto length = varint(bytes, offset, end);
                if (length > 128 || length > end - offset) throw std::invalid_argument("invalid ADB serial");
                serial.assign(bytes.begin() + offset, bytes.begin() + offset + static_cast<size_t>(length));
                offset += static_cast<size_t>(length);
            } else if (field == 16) {
                if (seen_state) throw std::invalid_argument("duplicate ADB state");
                seen_state = true; state = varint(bytes, offset, end);
            } else if (field == 56) {
                if (seen_type) throw std::invalid_argument("duplicate ADB connection type");
                seen_type = true; type = varint(bytes, offset, end);
            } else skip_field(bytes, offset, end, static_cast<unsigned>(field & 7));
        }
        if (state == 8 && type == 1 && !serial.empty() &&
            serial.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._:-") == std::string::npos)
            result.push_back(serial);
        if (result.size() > 128) throw std::invalid_argument("too many ADB devices");
    }
    return result;
}
} // namespace reflection
