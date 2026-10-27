#include "reflection/runtime.hpp"
#include "reflection/windows.hpp"
#include <chrono>
#include <iostream>
#include <thread>

using namespace reflection;

static unsigned checks = 0;
static void require(bool condition, const char* message) {
    ++checks;
    if (!condition) throw std::runtime_error(message);
}

int main() {
    const auto directory = std::filesystem::temp_directory_path() /
        ("reflection-runtime-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    struct Cleanup {
        std::filesystem::path path;
        ~Cleanup() { std::error_code error; std::filesystem::remove_all(path, error); }
    } cleanup{directory};
    try {
        {
            const Rect desktop{-3840, 0, 7680, 2160};
            require(!synthetic_pen_refresh_due(true,49999,desktop,desktop) &&
                    synthetic_pen_refresh_due(true,50000,desktop,desktop),
                    "stationary pointer refresh becomes due at 50 ms");
            require(!synthetic_pen_refresh_due(false,100000,desktop,desktop) &&
                    !synthetic_pen_refresh_due(true,100000,desktop,Rect{0,0,3840,2160}),
                    "released pointer and changed topology never replay old pointer state");
            auto expect = [&](double x, double y, const Rect& bounds, LONG expected_x, LONG expected_y) {
                const auto position = synthetic_pointer_position(x, y, bounds);
                require(position.x == expected_x && position.y == expected_y,
                        "synthetic pointer position uses virtual-screen origin and inclusive pixel bounds");
            };
            expect(-3840, 0, desktop, 0, 0);
            expect(-1920, 1080, desktop, 1920, 1080);
            expect(0, 1080, desktop, 3840, 1080);
            expect(1920, 1080, desktop, 5760, 1080);
            expect(3839, 2159, desktop, 7679, 2159);
            expect(100, 200, {0, 0, 1920, 1080}, 100, 200);
            expect(100, -1080, {0, -2160, 3840, 4320}, 100, 1080);
            expect(-100, -100, {-1920, -1080, 5760, 3240}, 1820, 980);
            // Releasing after the left display is removed must use the new origin and clamp safely.
            expect(-1920, 1080, {0, 0, 3840, 2160}, 0, 1080);
            expect(1920, 1080, {0, 0, 3840, 2160}, 1920, 1080);
            MappedSample pen; pen.source.phase=Phase::down; pen.source.barrel=true;
            pen.x=-1920; pen.y=400; pen.pressure=.5;
            const auto down=synthetic_pen_frame(pen,desktop,nullptr);
            require((down.penInfo.pointerInfo.pointerFlags & POINTER_FLAG_SECONDBUTTON) &&
                    !(down.penInfo.pointerInfo.pointerFlags & POINTER_FLAG_FIRSTBUTTON),
                    "barrel contact sets only the secondary pointer button");
            pen.source.phase=Phase::up; pen.x=3000; pen.y=1000;
            const auto up=synthetic_pen_frame(pen,{0,0,3840,2160},&down);
            require(up.penInfo.pointerInfo.ptPixelLocation.x==down.penInfo.pointerInfo.ptPixelLocation.x &&
                    up.penInfo.pointerInfo.ptPixelLocation.y==down.penInfo.pointerInfo.ptPixelLocation.y &&
                    up.penInfo.pressure==0 && !(up.penInfo.pointerInfo.pointerFlags & (POINTER_FLAG_FIRSTBUTTON|POINTER_FLAG_SECONDBUTTON)),
                    "up uses last successful API position even after sample or topology motion");
            pen.source.phase=Phase::hover;
            const auto hover=synthetic_pen_frame(pen,desktop,&up);
            pen.source.phase=Phase::move;
            const auto move=synthetic_pen_frame(pen,desktop,&down);
            require(identical_pen_update(move,move), "identical pen update can be coalesced");
            require(!identical_pen_update(down,down) && !identical_pen_update(up,up), "contact boundaries cannot be coalesced");
            auto changed=move; changed.penInfo.pressure++;
            require(!identical_pen_update(changed,move), "stationary pressure changes survive coalescing");
            changed=move; changed.penInfo.penFlags^=PEN_FLAG_BARREL;
            require(!identical_pen_update(changed,move), "stationary barrel changes survive coalescing");
            changed=move; changed.penInfo.tiltX++;
            require(!identical_pen_update(changed,move), "stationary tilt changes survive coalescing");
            changed=move; changed.penInfo.penMask^=PEN_MASK_TILT_X;
            require(!identical_pen_update(changed,move), "tilt availability changes survive coalescing");
            changed=move; changed.penInfo.pointerInfo.ptPixelLocation.x++;
            require(!identical_pen_update(changed,move), "position updates survive coalescing");
            const auto repeated_down=synthetic_pen_repeat(down,true);
            require((repeated_down.penInfo.pointerInfo.pointerFlags&POINTER_FLAG_UPDATE) &&
                    (repeated_down.penInfo.pointerInfo.pointerFlags&POINTER_FLAG_INCONTACT) &&
                    !(repeated_down.penInfo.pointerInfo.pointerFlags&(POINTER_FLAG_NEW|POINTER_FLAG_DOWN|POINTER_FLAG_UP)) &&
                    repeated_down.penInfo.pressure==down.penInfo.pressure &&
                    repeated_down.penInfo.pointerInfo.ptPixelLocation.x==down.penInfo.pointerInfo.ptPixelLocation.x,
                    "stationary contact refresh never repeats DOWN and preserves pressure and position");
            require((repeated_down.penInfo.pointerInfo.pointerFlags&POINTER_FLAG_SECONDBUTTON) &&
                    !(repeated_down.penInfo.pointerInfo.pointerFlags&POINTER_FLAG_FIRSTBUTTON),
                    "stationary barrel refresh preserves the secondary action");
            const auto repeated_up=synthetic_pen_repeat(up,false);
            require(repeated_up.penInfo.pressure==0 &&
                    (repeated_up.penInfo.pointerInfo.pointerFlags&POINTER_FLAG_UPDATE) &&
                    !(repeated_up.penInfo.pointerInfo.pointerFlags&(POINTER_FLAG_NEW|POINTER_FLAG_DOWN|POINTER_FLAG_UP|
                        POINTER_FLAG_INCONTACT|POINTER_FLAG_FIRSTBUTTON|POINTER_FLAG_SECONDBUTTON)),
                    "post-UP refresh stays hover and cannot create contact");
            require((hover.penInfo.penFlags&PEN_FLAG_BARREL) &&
                    !(hover.penInfo.pointerInfo.pointerFlags&(POINTER_FLAG_FIRSTBUTTON|POINTER_FLAG_SECONDBUTTON|POINTER_FLAG_INCONTACT)),
                    "barrel hover reports pen button without creating pointer contact buttons");
            for(bool inverted:{false,true}) for(bool barrel:{false,true}) {
                MappedSample tool;tool.x=-1800;tool.y=500;tool.pressure=.5;
                tool.source.eraser=inverted;tool.source.barrel=barrel;
                POINTER_TYPE_INFO prior{};bool have_prior=false;
                for(Phase phase:{Phase::hover,Phase::down,Phase::move,Phase::up}) {
                    tool.source.phase=phase;
                    const bool touching=phase==Phase::down || phase==Phase::move;
                    const auto frame=synthetic_pen_frame(tool,desktop,have_prior?&prior:nullptr);
                    const auto& p=frame.penInfo;
                    require(bool(p.penFlags&PEN_FLAG_INVERTED)==inverted &&
                            bool(p.penFlags&PEN_FLAG_ERASER)==(inverted&&touching) &&
                            bool(p.penFlags&PEN_FLAG_BARREL)==barrel,
                            "eraser identity is separate from erase contact across hover/down/move/up");
                    require(bool(p.pointerInfo.pointerFlags&POINTER_FLAG_INCONTACT)==touching &&
                            bool(p.pointerInfo.pointerFlags&POINTER_FLAG_FIRSTBUTTON)==(touching&&!barrel) &&
                            bool(p.pointerInfo.pointerFlags&POINTER_FLAG_SECONDBUTTON)==(touching&&barrel) &&
                            p.pressure==(touching?512u:0u),
                            "tip/eraser hover and up never press pointer buttons or report pressure");
                    const auto refresh=synthetic_pen_repeat(frame,touching);
                    require(bool(refresh.penInfo.penFlags&PEN_FLAG_ERASER)==(inverted&&touching) &&
                            bool(refresh.penInfo.penFlags&PEN_FLAG_INVERTED)==inverted &&
                            bool(refresh.penInfo.penFlags&PEN_FLAG_BARREL)==barrel &&
                            refresh.penInfo.pressure==p.pressure,
                            "keepalive preserves eraser identity and only repeats actual erase contact");
                    const auto finish=synthetic_pen_release(frame,touching);
                    require(finish.penInfo.pressure==0 && !(finish.penInfo.penFlags&(PEN_FLAG_ERASER|PEN_FLAG_BARREL)) &&
                            bool(finish.penInfo.penFlags&PEN_FLAG_INVERTED)==(inverted&&touching) &&
                            !(finish.penInfo.pointerInfo.pointerFlags&(POINTER_FLAG_INCONTACT|POINTER_FLAG_FIRSTBUTTON|POINTER_FLAG_SECONDBUTTON)),
                            "cancel/release clears erase and button switches without inventing contact");
                    require(finish.penInfo.pointerInfo.ptPixelLocation.x==p.pointerInfo.ptPixelLocation.x &&
                            finish.penInfo.pointerInfo.ptPixelLocation.y==p.pointerInfo.ptPixelLocation.y &&
                            bool(finish.penInfo.pointerInfo.pointerFlags&POINTER_FLAG_UP)==touching &&
                            bool(finish.penInfo.pointerInfo.pointerFlags&POINTER_FLAG_CANCELED)==touching,
                            "terminal cancel clears eraser at the last injected contact position");
                    const auto exit=synthetic_pen_release(finish,false);
                    require(exit.penInfo.penFlags==PEN_FLAG_NONE && exit.penInfo.pressure==0 &&
                            exit.penInfo.pointerInfo.pointerFlags==POINTER_FLAG_UPDATE,
                            "range exit leaves no inverted erase or button state latched");
                    // Even an old in-contact frame must become safe if refreshed
                    // with the backend's current hover state after a terminal event.
                    const auto hover_refresh=synthetic_pen_repeat(frame,false);
                    require(!(hover_refresh.penInfo.penFlags&PEN_FLAG_ERASER) && hover_refresh.penInfo.pressure==0 &&
                            !(hover_refresh.penInfo.pointerInfo.pointerFlags&(POINTER_FLAG_INCONTACT|POINTER_FLAG_FIRSTBUTTON|POINTER_FLAG_SECONDBUTTON)),
                            "hover keepalive cannot resurrect a prior erasing contact");
                    prior=frame;have_prior=true;
                }
                tool.source.phase=Phase::hover;tool.source.eraser=!inverted;
                const auto flipped=synthetic_pen_frame(tool,desktop,nullptr);
                require((flipped.penInfo.pointerInfo.pointerFlags&POINTER_FLAG_NEW) &&
                        bool(flipped.penInfo.penFlags&PEN_FLAG_INVERTED)==!inverted &&
                        !(flipped.penInfo.penFlags&PEN_FLAG_ERASER),
                        "a flipped hover starts the new tool in range without erasing");
            }
            for (const auto target : {Rect{-3840, 0, 3840, 2160}, Rect{0, 0, 3840, 2160},
                                      Rect{-3840, 0, 7680, 2160}}) {
                Mapping mapping; mapping.target = target;
                Mapper mapper(mapping);
                Sample point; point.phase = Phase::hover;
                for (const double normalized : {0.0, 0.25, 0.75, 1.0, std::nextafter(1.0, 0.0)}) {
                    point.x = point.y = normalized;
                    const auto mapped = mapper.map(point);
                    const auto position = synthetic_pointer_position(mapped.x, mapped.y, desktop);
                    // Convert the injected coordinates back as Windows does, then check the locked region.
                    const auto landed_x = position.x + desktop.x, landed_y = position.y + desktop.y;
                    require(landed_x >= target.x && landed_x < target.x + target.width &&
                            landed_y >= target.y && landed_y < target.y + target.height,
                            "all-screen and individual left/right mappings land inside their signed desktop target");
                }
            }
        }
        const auto path = directory / "server.conf";
        Config config;
        config.token = std::string(64, 'a');
        save_config(config, path);
        require(HostRuntime::status(path).find("\"running\":false") != std::string::npos,
                "a saved configuration is not a running host");
        require(!HostRuntime::stop(path), "stop is safe when no host runs");
        std::atomic_bool running{true};
        {
            HostRuntime runtime(path, config, true, running);
            runtime.event("listening", 24819, 0);
            auto status = HostRuntime::status(path);
            require(status.find("\"controlAvailable\":true") != std::string::npos &&
                    status.find("\"listening\":true") != std::string::npos &&
                    status.find("\"restartRequired\":false") != std::string::npos,
                    "live host status and matching configuration");
            bool rejected = false;
            std::thread contender([&] {
                std::atomic_bool flag{true};
                try { HostRuntime duplicate(path, config, true, flag); }
                catch (const std::exception&) { rejected = true; }
            });
            contender.join();
            require(rejected, "configuration ownership rejects another host thread");
            require(HostRuntime::pairing_request(path) == "[]",
                    "asynchronous pairing controls return without enabling discovery in tests");
            bool pairing_rejected = false;
            try { HostRuntime::pairing_request(path, std::string(32, 'a')); }
            catch (const std::exception&) { pairing_rejected = true; }
            require(pairing_rejected && HostRuntime::pairing_request(path) == "[]" && running.load(),
                    "failed asynchronous trust completes its request and leaves the host responsive");
            const auto before_switch = config_revision(load_config(path));
            require(HostRuntime::mapping_request(path, 1).find("\"mode\":\"display\"") != std::string::npos,
                    "live local command switches to a display");
            require(config_revision(load_config(path)) == before_switch, "live switch does not overwrite saved settings");
            require(HostRuntime::mapping_request(path, 3).find("\"mode\":\"all\"") != std::string::npos,
                    "live local command restores all-display mapping");
            bool unknown_rejected = false;
            try { HostRuntime::mapping_request(path, 4, "missing-display"); }
            catch (const std::exception&) { unknown_rejected = true; }
            require(unknown_rejected && running.load(), "invalid mapping command leaves host running");
            auto input=runtime.input(); input->begin_session(true);
            require(HostRuntime::calibration_request(path,1).find("\"active\":true")!=std::string::npos,
                    "runtime calibration command starts acquisition");
            require(input->take_release(),"runtime start requests input release");
            Config externally_edited=load_config(path); externally_edited.mapping.pressure_gamma=.9; save_config(externally_edited,path);
            for(uint64_t i=0;i<150;++i) {
                Sample s; s.sequence=i+1; s.timestamp_ns=1000000000+i*10000000;
                s.phase=i?Phase::move:Phase::down; s.x=.5+.0004*std::sin(double(i)); s.y=.5+.0004*std::cos(double(i)); s.pressure=.5;
                require(!input->process(s),"runtime calibration never injects sampled contacts");
            }
            require(HostRuntime::calibration_request(path,2).find("\"result\":\"success\"")!=std::string::npos,
                    "runtime finishes calibration and saves noise");
            auto after=load_config(path);
            require(after.stabilization.noise!=config.stabilization.noise && after.mapping.pressure_gamma==.9,
                    "calibration merges only noise into latest disk settings");
            require(HostRuntime::status(path).find("\"restartRequired\":true")!=std::string::npos,
                    "calibration fingerprint retains unrelated pending restart changes");
            config.stabilization.noise=after.stabilization.noise; save_config(config,path);
            require(HostRuntime::status(path).find("\"restartRequired\":false")!=std::string::npos,
                    "live calibrated noise itself requires no restart");
            input->end_session();
            runtime.event("connected", 0, 0);
            runtime.event("samples", 0, 123);
            status = HostRuntime::status(path);
            require(status.find("\"connected\":true") != std::string::npos &&
                    status.find("\"samples\":123") != std::string::npos, "session status reports samples");
            config.mapping.pressure_gamma = 0.7;
            save_config(config, path);
            require(HostRuntime::status(path).find("\"restartRequired\":true") != std::string::npos,
                    "disk configuration changes require restart");
            require(HostRuntime::stop(path), "cooperative stop event is available");
            for (int i = 0; i < 30 && running.load(); ++i)
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
            require(!running.load(), "cooperative stop is observed without killing a PID");
        }
        require(HostRuntime::status(path).find("\"running\":false") != std::string::npos,
                "status is removed when owner exits");
        running.store(true);
        {
            HostRuntime restarted(path, config, false, running);
            require(HostRuntime::status(path).find("\"restartRequired\":false") != std::string::npos,
                    "restart uses the saved configuration");
        }
        std::cout << checks << " runtime checks passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
