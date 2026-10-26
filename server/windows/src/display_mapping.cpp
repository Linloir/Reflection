#include "reflection/display_mapping.hpp"
#include "reflection/protocol.hpp"
#include <sstream>
#include <iomanip>
#include <cctype>

namespace reflection {
namespace {
bool equal(const Rect& a, const Rect& b) {
    return a.x == b.x && a.y == b.y && a.width == b.width && a.height == b.height;
}
std::string rect_json(const Rect& r) {
    std::ostringstream out;
    out << std::setprecision(12) << "{\"x\":" << r.x << ",\"y\":" << r.y
        << ",\"width\":" << r.width << ",\"height\":" << r.height << '}';
    return out.str();
}

bool valid_utf8(const std::string& text) {
    for (size_t i = 0; i < text.size();) {
        const auto first = static_cast<unsigned char>(text[i++]);
        if (first < 0x80) continue;
        unsigned count = 0, value = 0, minimum = 0;
        if (first >= 0xc2 && first <= 0xdf) { count = 1; value = first & 0x1f; minimum = 0x80; }
        else if (first >= 0xe0 && first <= 0xef) { count = 2; value = first & 0x0f; minimum = 0x800; }
        else if (first >= 0xf0 && first <= 0xf4) { count = 3; value = first & 7; minimum = 0x10000; }
        else return false;
        if (i + count > text.size()) return false;
        while (count--) {
            const auto next = static_cast<unsigned char>(text[i++]);
            if ((next & 0xc0) != 0x80) return false;
            value = (value << 6) | (next & 0x3f);
        }
        if (value < minimum || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff)) return false;
    }
    return true;
}

// Small bounded JSON reader used only to negotiate an optional capability. It validates
// the entire document, so text inside strings, duplicates, and trailing junk cannot opt in.
class CapabilityJSON {
public:
    explicit CapabilityJSON(std::string text, std::string key = "mappingControl") : text_(std::move(text)), key_(std::move(key)) {}
    bool mapping() {
        if (!valid_utf8(text_)) return false;
        space();
        if (!object(0)) return false;
        space();
        return position_ == text_.size() && enabled_;
    }
private:
    std::string text_;
    std::string key_;
    size_t position_ = 0;
    bool seen_ = false, enabled_ = false;
    void space() {
        while (position_ < text_.size() && (text_[position_] == ' ' || text_[position_] == '\t' ||
            text_[position_] == '\r' || text_[position_] == '\n')) ++position_;
    }
    bool take(char ch) {
        space();
        if (position_ == text_.size() || text_[position_] != ch) return false;
        ++position_; return true;
    }
    bool hex4(unsigned& value) {
        value = 0;
        for (unsigned i = 0; i < 4; ++i) {
            if (position_ == text_.size()) return false;
            const char ch = text_[position_++];
            const int digit = ch >= '0' && ch <= '9' ? ch - '0' :
                ch >= 'a' && ch <= 'f' ? ch - 'a' + 10 : ch >= 'A' && ch <= 'F' ? ch - 'A' + 10 : -1;
            if (digit < 0) return false;
            value = value * 16 + static_cast<unsigned>(digit);
        }
        return true;
    }
    bool string(std::string& result) {
        if (!take('"')) return false;
        while (position_ < text_.size()) {
            const auto ch = static_cast<unsigned char>(text_[position_++]);
            if (ch == '"') return true;
            if (ch < 0x20) return false;
            if (ch != '\\') { result += static_cast<char>(ch); continue; }
            if (position_ == text_.size()) return false;
            const char escaped = text_[position_++];
            if (escaped == 'u') {
                unsigned value;
                if (!hex4(value)) return false;
                if (value >= 0xd800 && value <= 0xdbff) {
                    if (position_ + 2 > text_.size() || text_[position_] != '\\' || text_[position_ + 1] != 'u') return false;
                    position_ += 2;
                    unsigned low;
                    if (!hex4(low) || low < 0xdc00 || low > 0xdfff) return false;
                    value = 0x10000 + ((value - 0xd800) << 10) + low - 0xdc00;
                } else if (value >= 0xdc00 && value <= 0xdfff) return false;
                // Only ASCII keys can match mappingControl; retain a non-ASCII marker otherwise.
                result += value < 0x80 ? static_cast<char>(value) : static_cast<char>(0x80);
            } else {
                const std::string escapes = "\"\\/bfnrt";
                const auto index = escapes.find(escaped);
                if (index == std::string::npos) return false;
                const char decoded[] = {'"', '\\', '/', '\b', '\f', '\n', '\r', '\t'};
                result += decoded[index];
            }
        }
        return false;
    }
    bool number() {
        if (position_ < text_.size() && text_[position_] == '-') ++position_;
        if (position_ == text_.size()) return false;
        if (text_[position_] == '0') ++position_;
        else {
            if (text_[position_] < '1' || text_[position_] > '9') return false;
            while (position_ < text_.size() && text_[position_] >= '0' && text_[position_] <= '9') ++position_;
        }
        if (position_ < text_.size() && text_[position_] == '.') {
            const auto start = ++position_;
            while (position_ < text_.size() && text_[position_] >= '0' && text_[position_] <= '9') ++position_;
            if (start == position_) return false;
        }
        if (position_ < text_.size() && (text_[position_] == 'e' || text_[position_] == 'E')) {
            ++position_;
            if (position_ < text_.size() && (text_[position_] == '+' || text_[position_] == '-')) ++position_;
            const auto start = position_;
            while (position_ < text_.size() && text_[position_] >= '0' && text_[position_] <= '9') ++position_;
            if (start == position_) return false;
        }
        return true;
    }
    bool value(unsigned depth) {
        if (depth > 32) return false;
        space();
        if (position_ == text_.size()) return false;
        const char ch = text_[position_];
        if (ch == '{') return object(depth);
        if (ch == '[') {
            ++position_;
            if (take(']')) return true;
            do { if (!value(depth + 1)) return false; } while (take(','));
            return take(']');
        }
        if (ch == '"') { std::string ignored; return string(ignored); }
        for (const auto literal : {"true", "false", "null"}) {
            const auto length = std::char_traits<char>::length(literal);
            if (text_.compare(position_, length, literal) == 0) { position_ += length; return true; }
        }
        return number();
    }
    bool object(unsigned depth) {
        if (depth > 32 || !take('{')) return false;
        if (take('}')) return true;
        do {
            std::string key;
            if (!string(key) || !take(':')) return false;
            space(); const auto start = position_;
            if (!value(depth + 1)) return false;
            if (depth == 0 && key == key_) {
                if (seen_) return false;
                seen_ = true; enabled_ = text_.substr(start, position_ - start) == "1";
            }
        } while (take(','));
        return take('}');
    }
};
}

DisplayMapping::DisplayMapping(Config config, Provider provider)
    : desired_(std::move(config)), provider_(std::move(provider)) { refresh_locked(); }

void DisplayMapping::refresh_locked() {
    auto current = provider_();
    if (current.empty()) throw std::runtime_error("No active displays are available");
    if (current.size() > 16) current.resize(16);
    std::sort(current.begin(), current.end(), [](const Display& a, const Display& b) {
        if (a.primary != b.primary) return a.primary;
        if (a.bounds.x != b.bounds.x) return a.bounds.x < b.bounds.x;
        if (a.bounds.y != b.bounds.y) return a.bounds.y < b.bounds.y;
        return a.name < b.name;
    });
    bool changed = current.size() != displays_.size();
    for (size_t i = 0; !changed && i < current.size(); ++i)
        changed = current[i].name != displays_[i].name || current[i].primary != displays_[i].primary ||
            !equal(current[i].bounds, displays_[i].bounds);
    if (changed) { displays_ = std::move(current); pending_ = true; ++revision_; }
    if (!touching_) apply_locked();
}

void DisplayMapping::refresh() { std::lock_guard<std::mutex> lock(mutex_); refresh_locked(); }

void DisplayMapping::apply_locked() {
    if (mapper_ && !pending_) return;
    auto target = desired_.mapping.target;
    std::string mode = "custom", selected;
    bool fallback = false;
    if (!desired_.target_display.empty()) {
        const auto found = std::find_if(displays_.begin(), displays_.end(), [&](const Display& d) {
            return d.name == desired_.target_display;
        });
        const auto& display = found == displays_.end() ? displays_.front() : *found;
        target = display.bounds; selected = display.name; mode = "display";
        fallback = found == displays_.end();
    } else if (desired_.target_virtual_desktop) {
        mode = "all";
        double left = displays_.front().bounds.x, top = displays_.front().bounds.y;
        double right = left + displays_.front().bounds.width, bottom = top + displays_.front().bounds.height;
        for (const auto& display : displays_) {
            const auto& r = display.bounds;
            left = std::min(left, r.x); top = std::min(top, r.y);
            right = std::max(right, r.x + r.width); bottom = std::max(bottom, r.y + r.height);
        }
        target = {left, top, right - left, bottom - top};
    }
    const bool changed = !mapper_ || !equal(target, target_) || mode != mode_ || selected != selected_;
    if (changed) {
        auto mapping = desired_.mapping;
        mapping.target = target;
        mapper_ = std::make_unique<Mapper>(mapping);
        mapper_->seed(cursor_x_, cursor_y_);
    }
    target_ = target; mode_ = mode; selected_ = selected; fallback_ = fallback;
    // Publish the topology and selection together at the same safe boundary.
    // During a stroke an unplugged active display remains in the advertised active layout.
    active_displays_ = displays_;
    pending_ = false;
    ++revision_;
}

void DisplayMapping::request(unsigned action, const std::string& display) {
    std::lock_guard<std::mutex> lock(mutex_);
    refresh_locked();
    if (action == 0) return;
    if (action > 4) throw std::invalid_argument("unknown mapping action");
    if (action == 3) {
        desired_.target_virtual_desktop = true; desired_.target_display.clear();
    } else {
        std::string selected = display;
        if (action == 1 || action == 2) {
            const auto found = std::find_if(displays_.begin(), displays_.end(), [&](const Display& d) {
                return d.name == desired_.target_display;
            });
            size_t index = found == displays_.end() ? (action == 1 ? 0 : displays_.size() - 1) :
                (static_cast<size_t>(found - displays_.begin()) + (action == 1 ? 1 : displays_.size() - 1)) % displays_.size();
            selected = displays_[index].name;
        }
        if (std::none_of(displays_.begin(), displays_.end(), [&](const Display& d) { return d.name == selected; }))
            throw std::invalid_argument("selected display is not connected");
        desired_.target_virtual_desktop = false; desired_.target_display = selected;
    }
    pending_ = true; ++revision_;
    if (!touching_) apply_locked();
}

MappedSample DisplayMapping::map(const Sample& sample) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!touching_) apply_locked();
    auto result = mapper_->map(sample);
    cursor_x_ = result.x; cursor_y_ = result.y;
    touching_ = sample.phase == Phase::down || sample.phase == Phase::move;
    // Map UP with the old target. The subsequent stroke starts on the new target.
    if (!touching_) apply_locked();
    return result;
}
void DisplayMapping::release() {
    std::lock_guard<std::mutex> lock(mutex_);
    touching_ = false; mapper_->reset(); apply_locked();
}
void DisplayMapping::seed(double x, double y) {
    std::lock_guard<std::mutex> lock(mutex_);
    cursor_x_ = x; cursor_y_ = y; mapper_->seed(x, y);
}
uint64_t DisplayMapping::revision() const { std::lock_guard<std::mutex> lock(mutex_); return revision_; }
std::string DisplayMapping::selected_display() const { std::lock_guard<std::mutex> lock(mutex_); return selected_; }

std::string DisplayMapping::status(uint64_t response_revision) const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::ostringstream out;
    out << "{\"revision\":" << (response_revision ? response_revision : revision_)
        << ",\"mode\":" << json_escape(mode_) << ",\"selected\":" << json_escape(selected_)
        << ",\"target\":" << rect_json(target_) << ",\"pending\":" << (pending_ ? "true" : "false")
        << ",\"requested\":" << json_escape(desired_.target_display)
        << ",\"fallback\":" << (fallback_ ? "true" : "false") << ",\"displays\":[";
    bool first = true;
    for (const auto& display : active_displays_) {
        if (!first) out << ',';
        first = false;
        auto rect = rect_json(display.bounds);
        out << "{\"id\":" << json_escape(display.name) << ",\"name\":" << json_escape(display.name)
            << ",\"primary\":" << (display.primary ? "true" : "false") << ',' << rect.substr(1);
    }
    out << "]}";
    return out.str();
}

bool mapping_capability(const std::vector<uint8_t>& payload) {
    return CapabilityJSON(std::string(payload.begin(), payload.end())).mapping();
}
bool stabilization_capability(const std::vector<uint8_t>& payload) {
    return CapabilityJSON(std::string(payload.begin(), payload.end()), "hostStabilization").mapping();
}

MappingRequest decode_mapping_request(const std::vector<uint8_t>& payload, uint64_t previous_sequence) {
    if (payload.size() < 9 || payload.size() > 137) throw std::invalid_argument("invalid mapping request length");
    MappingRequest request{read_u64(payload.data()), payload[8], std::string(payload.begin() + 9, payload.end())};
    if (request.sequence <= previous_sequence || request.action > 4 ||
        (request.action == 4 ? request.display.empty() : !request.display.empty()) ||
        !valid_utf8(request.display) ||
        request.display.find_first_of("\r\n\0", 0, 3) != std::string::npos)
        throw std::invalid_argument("invalid or replayed mapping request");
    return request;
}

} // namespace reflection
