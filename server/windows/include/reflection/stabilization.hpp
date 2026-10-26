#pragma once
#include "reflection/core.hpp"
#include <chrono>
#include <functional>
#include <mutex>
#include <optional>
#include <deque>

namespace reflection {
struct StabilizationConfig {
    bool enabled = true;
    bool adaptive_noise = true;
    bool independent_axes = false;
    double strength = .5, min_cutoff_hz = 3, beta = 80, derivative_cutoff_hz = 12;
    double noise = .0006, max_jump = .25;
    void validate() const;
};

// All positions remain in the tablet's normalized coordinate space. The continuity
// guard runs for every client, independently of optional smoothing negotiation.
class HostInput {
public:
    explicit HostInput(StabilizationConfig config);
    void begin_session(bool raw_coordinates = false);
    void negotiate(bool raw_coordinates);
    void end_session();
    std::optional<Sample> process(const Sample& raw);
    bool take_release();
    std::string calibration(unsigned action, const std::function<void(double)>& persist = {});
    std::string calibration_status();
    std::string status();
private:
    std::mutex mutex_;
    StabilizationConfig config_;
    bool connected_ = false, raw_ = false, contact_ = false, suppressed_ = false, anchored_ = false;
    bool release_ = false, filter_ready_ = false;
    bool fresh_down_ = false;
    bool hover_candidate_ = false;
    Sample candidate_{};
    Sample safe_{};
    double fx_ = 0, fy_ = 0, dx_ = 0, dy_ = 0, rx_ = 0, ry_ = 0;
    uint64_t filter_time_ = 0, rejected_ = 0, suppressed_strokes_ = 0;
    struct NoiseResidual { uint64_t timestamp_ns; double x, y; };
    std::deque<Sample> noise_history_;
    std::deque<NoiseResidual> noise_window_;
    double noise_x_ = 0, noise_y_ = 0;
    uint64_t noise_count_ = 0, noise_update_time_ = 0;
    uint64_t noise_latest_time_ = 0;
    bool noise_has_time_ = false;
    bool noise_updated_ = false, noise_eligible_ = false;
    bool noise_phase_known_ = false, noise_hover_ = false, noise_eraser_ = false;
    bool collecting_ = false, calibration_contact_ = false, calibration_seen_ = false, calibration_invalid_ = false;
    std::string phase_ = "idle", result_ = "none", reason_;
    std::vector<Sample> samples_;
    double previous_noise_ = .0006;
    std::chrono::steady_clock::time_point started_{};
    void reset_locked();
    void reset_noise_locked(bool reset_estimate);
    void observe_noise_locked(const Sample& raw);
    Sample filter_locked(Sample s);
    Sample cancel_locked(const Sample& raw);
    void collect_locked(const Sample& raw);
    void expire_locked();
    double duration_locked() const;
    std::string calibration_json_locked() const;
};
} // namespace reflection
