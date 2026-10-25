#pragma once
#include "reflection/core.hpp"
#include <cstddef>

namespace reflection {

constexpr uint8_t protocol_version = 1;
constexpr size_t maximum_body = 4096;
constexpr size_t pen_payload_size = 48;
constexpr size_t authentication_tag_size = 32;

uint32_t read_u32(const uint8_t* bytes);
uint64_t read_u64(const uint8_t* bytes);
Sample decode_pen(const std::vector<uint8_t>& payload);

class SequenceGuard {
public:
    void accept(const Sample& sample);
    void accept_ping(uint64_t value);
private:
    uint64_t sequence_ = 0, timestamp_ = 0, ping_ = 0;
    bool ping_seen_ = false;
};

class ContactGuard {
public:
    void accept(const Sample& sample);
private:
    bool contact_ = false;
    bool eraser_ = false;
};

} // namespace reflection
