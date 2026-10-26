#include "reflection/driver.hpp"
#include "../../shared/tablet_report.h"
#include <iostream>
#include <limits>

using namespace reflection;
static void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
int main() {
    try {
        Config config;
        config.token = std::string(64, 'a');
        require(config.input_mode == "api", "old/new configurations default to API");
        set_config(config, "input.mode", "driver"); config.validate();
        require(config_values(config, false).at("input.mode") == "driver", "mode persists in config snapshots");
        set_config(config, "input.mode", "unknown");
        bool rejected = false;
        try { config.validate(); } catch (const std::invalid_argument&) { rejected = true; }
        require(rejected, "invalid mode rejected");

        MappedSample sample;
        sample.source.phase = Phase::down;
        sample.x = -1; sample.y = -100; sample.pressure = .5;
        sample.tilt_x = 90; sample.tilt_y = -90;
        const auto report = tablet_report(sample, {-1920, -100, 1920, 1080});
        const TabletReport expected{1, 0x11, 0xff, 0x7f, 0, 0, 0, 0x80, 90, 166};
        require(report == expected, "HID ABI vector: signed desktop, inclusive endpoint, 16-bit pressure, signed tilt");
        require(reflection_tablet_report_valid(report.data()), "valid pen report");
        sample.source.eraser = true;
        auto eraser = tablet_report(sample, {-1920, -100, 1920, 1080});
        require(eraser[1] == 0x1c, "eraser contact sets inverted and erase, not tip");
        auto lifted = tablet_release(eraser, true);
        require(lifted[1] == 0x14 && lifted[6] == 0 && lifted[7] == 0 &&
            lifted[2] == eraser[2] && lifted[3] == eraser[3], "release retains tool and last position, clears pressure");
        const auto exited = tablet_release(lifted, false);
        require(exited[1] == 0 && reflection_tablet_report_valid(exited.data()), "exit clears all switches");
        auto invalid = eraser;
        invalid[1] |= 1;
        require(!reflection_tablet_report_valid(invalid.data()), "reject simultaneous tip and eraser");
        invalid = report; invalid[1] = 0x10;
        require(!reflection_tablet_report_valid(invalid.data()), "reject hover pressure");
        invalid = report; invalid[3] = 0xff;
        require(!reflection_tablet_report_valid(invalid.data()), "reject coordinate outside descriptor range");
        invalid = report; invalid[8] = 127;
        require(!reflection_tablet_report_valid(invalid.data()), "reject invalid tilt at driver boundary");
        sample.source.phase = Phase::up;
        auto up = tablet_report(sample, {-1920, -100, 1920, 1080});
        require(up[1] == 0x14 && up[6] == 0 && up[7] == 0, "lift forces zero pressure");
        sample.source.phase = Phase::cancel;
        require(tablet_report(sample, {-1920, -100, 1920, 1080}) == TabletReport{1}, "cancel is out of range");
        sample.x = std::numeric_limits<double>::quiet_NaN();
        rejected = false;
        try { tablet_report(sample, {0, 0, 1920, 1080}); } catch (const std::invalid_argument&) { rejected = true; }
        require(rejected, "reject non-finite report input before integer conversion");
        std::cout << "Input mode and virtual HID report checks passed\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
