#pragma once
#include "reflection/config.hpp"
#include <memory>
#include <optional>

namespace reflection {

struct DeviceAnnouncement {
    std::string id, name, nonce;
    std::vector<uint8_t> modulus, exponent;
};
struct DiscoveryQuery { std::string nonce, device_id; };
std::string base64_encode(const std::vector<uint8_t>& bytes);
std::vector<uint8_t> base64_decode(const std::string& text);
std::optional<DeviceAnnouncement> parse_device_announcement(const std::string& text);
std::optional<DeviceAnnouncement> bluetooth_pairing_request(const std::vector<uint8_t>& body);
std::optional<DiscoveryQuery> discovery_query(const std::string& text);
std::vector<std::string> adb_usb_devices(const std::vector<uint8_t>& protobuf);

#ifdef _WIN32
class AutomaticConnectivity {
public:
    AutomaticConnectivity(const Config& config, const std::filesystem::path& path, bool allow_usb);
    ~AutomaticConnectivity();
    void cancel() noexcept;
    AutomaticConnectivity(const AutomaticConnectivity&) = delete;
    AutomaticConnectivity& operator=(const AutomaticConnectivity&) = delete;
    std::string devices();
    std::string trust(const std::string& id);
    std::vector<std::string> authentication_tokens();
    uint64_t selection_revision();
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
#endif
} // namespace reflection
