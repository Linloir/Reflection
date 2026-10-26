#include "reflection/driver.hpp"
#include "../../shared/tablet_report.h"

namespace reflection {
TabletReport tablet_report(const MappedSample& sample, const Rect& desktop) {
    if (!std::isfinite(desktop.x) || !std::isfinite(desktop.y) ||
        !std::isfinite(desktop.width) || !std::isfinite(desktop.height) ||
        desktop.width < 1 || desktop.height < 1 ||
        !std::isfinite(sample.x) || !std::isfinite(sample.y) ||
        !std::isfinite(sample.pressure) || !std::isfinite(sample.tilt_x) || !std::isfinite(sample.tilt_y))
        throw std::invalid_argument("invalid HID report geometry");
    TabletReport report{};
    report[0] = REFLECTION_TABLET_REPORT_ID;
    const auto phase = sample.source.phase;
    const bool contact = phase == Phase::down || phase == Phase::move;
    if (phase == Phase::cancel || phase == Phase::exit) return report;
    report[1] = REFLECTION_TABLET_RANGE;
    if (sample.source.eraser) report[1] |= REFLECTION_TABLET_INVERT;
    if (contact) report[1] |= sample.source.eraser ? REFLECTION_TABLET_ERASER : REFLECTION_TABLET_TIP;
    if (sample.source.barrel) report[1] |= REFLECTION_TABLET_BARREL;
    const auto put = [&](size_t offset, double value, unsigned maximum) {
        const auto n = static_cast<unsigned>(std::llround(std::clamp(value, 0.0, 1.0) * maximum));
        report[offset] = static_cast<uint8_t>(n);
        report[offset + 1] = static_cast<uint8_t>(n >> 8);
    };
    put(2, (sample.x - desktop.x) / std::max(1.0, desktop.width - 1), 32767);
    put(4, (sample.y - desktop.y) / std::max(1.0, desktop.height - 1), 32767);
    put(6, contact ? sample.pressure : 0, 65535);
    report[8] = static_cast<uint8_t>(static_cast<int8_t>(std::llround(std::clamp(sample.tilt_x, -90.0, 90.0))));
    report[9] = static_cast<uint8_t>(static_cast<int8_t>(std::llround(std::clamp(sample.tilt_y, -90.0, 90.0))));
    return report;
}
TabletReport tablet_release(TabletReport report, bool in_range) {
    report[1] = in_range ? (REFLECTION_TABLET_RANGE | (report[1] & REFLECTION_TABLET_INVERT)) : 0;
    report[6] = report[7] = 0;
    return report;
}
}
