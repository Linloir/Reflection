#include "reflection/raw_touchpad.hpp"
#include <cstring>
#include <set>

namespace reflection {
namespace {
double coordinate(const uint8_t* bytes) {
    const auto bits = read_u32(bytes);
    float value;
    std::memcpy(&value, &bits, sizeof(value));
    if (!std::isfinite(value) || value < 0 || value > 1)
        throw std::invalid_argument("invalid raw touchpad coordinate");
    return value;
}
void append_u32(std::vector<uint8_t>& bytes, uint32_t value) {
    for (int shift = 24; shift >= 0; shift -= 8) bytes.push_back(static_cast<uint8_t>(value >> shift));
}
}

RawTouchpadFrame decode_raw_touchpad(const std::vector<uint8_t>& payload) {
    if (payload.size() < 20 || payload[16] > 5 || payload[17] > 1 || payload[18] || payload[19] ||
        payload.size() != 20 + size_t(payload[16]) * 16 ||
        ((payload[17] == 1) != (payload[16] == 0)))
        throw std::invalid_argument("invalid raw touchpad frame");
    RawTouchpadFrame result;
    result.sequence = read_u64(payload.data()); result.timestamp_ns = read_u64(payload.data() + 8);
    result.cancel = payload[17] == 1;
    std::set<uint32_t> ids;
    for (size_t offset = 20; offset < payload.size(); offset += 16) {
        const auto* p = payload.data() + offset;
        if (p[4] < 1 || p[4] > 3 || p[5] > 1 || p[6] || p[7])
            throw std::invalid_argument("invalid raw touchpad contact");
        RawTouchpadContact contact{read_u32(p), static_cast<RawTouchpadPhase>(p[4]), p[5] == 1,
                                  coordinate(p + 8), coordinate(p + 12)};
        if (!ids.insert(contact.id).second) throw std::invalid_argument("duplicate raw touchpad contact");
        result.contacts.push_back(contact);
    }
    return result;
}

std::vector<uint8_t> raw_touchpad_ack(RawTouchpadSize size) {
    if (size.width < 500 || size.width > 100000 || size.height < 500 || size.height > 100000)
        throw std::invalid_argument("invalid raw touchpad physical size");
    std::vector<uint8_t> bytes{1};
    append_u32(bytes, size.width); append_u32(bytes, size.height);
    return bytes;
}

std::vector<NativeTouchpadContact> RawTouchpadState::accept(const RawTouchpadFrame& frame) {
    if (frame.sequence <= sequence_ || (!sequence_ && frame.sequence != 1) || frame.timestamp_ns < timestamp_)
        throw std::invalid_argument("replayed or unordered raw touchpad frame");
    if (frame.cancel) {
        if (!frame.contacts.empty()) throw std::invalid_argument("cancel with raw contacts");
        sequence_ = frame.sequence; timestamp_ = frame.timestamp_ns; cancel(); return {};
    }
    if (frame.contacts.empty() || frame.contacts.size() > 5) throw std::invalid_argument("invalid raw contact count");
    auto next = active_;
    std::set<uint32_t> seen, used_slots;
    for (const auto& [id, contact] : active_) { (void)id; used_slots.insert(contact.slot); }
    std::vector<NativeTouchpadContact> result;
    for (const auto& contact : frame.contacts) {
        if (!seen.insert(contact.id).second || !std::isfinite(contact.x) || !std::isfinite(contact.y) ||
            contact.x < 0 || contact.x > 1 || contact.y < 0 || contact.y > 1)
            throw std::invalid_argument("invalid raw contact snapshot");
        const auto previous = active_.find(contact.id);
        if (contact.phase == RawTouchpadPhase::down) {
            if (previous != active_.end()) throw std::invalid_argument("duplicate raw contact down");
            uint32_t slot = 1;
            while (used_slots.count(slot)) ++slot;
            if (slot > 5) throw std::invalid_argument("too many raw contact transitions");
            used_slots.insert(slot);
            NativeTouchpadContact native{slot, contact}; result.push_back(native); next[contact.id] = native;
        } else {
            if (previous == active_.end()) throw std::invalid_argument("raw contact without down");
            NativeTouchpadContact native{previous->second.slot, contact}; result.push_back(native);
            if (contact.phase == RawTouchpadPhase::up) next.erase(contact.id);
            else if (contact.phase == RawTouchpadPhase::update) next[contact.id] = native;
            else throw std::invalid_argument("unknown raw contact phase");
        }
    }
    for (const auto& [id, contact] : active_) {
        (void)contact;
        if (!seen.count(id)) throw std::invalid_argument("missing active raw contact");
    }
    active_ = std::move(next); sequence_ = frame.sequence; timestamp_ = frame.timestamp_ns;
    return result;
}

std::vector<NativeTouchpadContact> RawTouchpadState::active() const {
    std::vector<NativeTouchpadContact> result;
    for (const auto& [id, contact] : active_) { (void)id; result.push_back(contact); }
    return result;
}
} // namespace reflection
