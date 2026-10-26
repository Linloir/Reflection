#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace reflection {

enum class Phase : uint8_t { hover = 0, down = 1, move = 2, up = 3, cancel = 4, exit = 5 };

struct Sample {
    uint64_t sequence = 0;
    uint64_t timestamp_ns = 0;
    Phase phase = Phase::hover;
    bool eraser = false;
    bool barrel = false;
    bool tilt = false;
    bool secondary = false;
    double x = 0;
    double y = 0;
    double pressure = 0;
    double tilt_x = 0;
    double tilt_y = 0;
    double rotation = 0;
    double distance = 0;
};

struct Rect {
    double x = 0, y = 0, width = 1, height = 1;
};

struct Mapping {
    Rect source;
    Rect target{0, 0, 1920, 1080};
    bool relative = false;
    unsigned rotation = 0;
    double pressure_gamma = 1;
    std::vector<std::pair<double, double>> pressure_curve;
    std::string pressure_interpolation = "linear";
    unsigned position_steps_x = 0, position_steps_y = 0;
    bool tilt_enabled = true;
    double tilt_scale = 1;
    double sensitivity = 1;

    void validate() const;
};

struct MappedSample {
    Sample source;
    double x = 0, y = 0, pressure = 0, tilt_x = 0, tilt_y = 0;
};

class Mapper {
public:
    explicit Mapper(Mapping mapping);
    MappedSample map(const Sample& sample);
    void reset();
    void seed(double x, double y);
private:
    Mapping mapping_;
    std::vector<double> pressure_slopes_;
    bool tracking_ = false;
    double last_x_ = 0, last_y_ = 0, cursor_x_ = 0, cursor_y_ = 0;
};

class InputBackend {
public:
    virtual ~InputBackend() = default;
    virtual void submit(const MappedSample& sample) = 0;
    virtual void tick() {}
    virtual void release() noexcept = 0;
};

bool constant_time_equal(const std::string& a, const std::string& b);
void validate_sample(const Sample& sample);
std::vector<uint8_t> frame(const std::vector<uint8_t>& payload);

} // namespace reflection
