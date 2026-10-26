#include "reflection/config.hpp"
#include "reflection/display_mapping.hpp"
#include <iostream>
#include <iomanip>
#include <limits>

using namespace reflection;
static unsigned checks=0;
static void require(bool value,const char* message) { ++checks; if(!value) throw std::runtime_error(message); }
static Sample sample(uint64_t i,Phase phase,double x=.5,double y=.5) {
    Sample s; s.sequence=i+1; s.timestamp_ns=1000000000+i*10000000; s.phase=phase; s.x=x; s.y=y;
    s.pressure=phase==Phase::down || phase==Phase::move?.6:0; return s;
}
static double noise(uint32_t& seed) {
    double sum=0; for(unsigned i=0;i<12;++i) { seed=seed*1664525u+1013904223u; sum+=double(seed)/4294967296.0; }
    return sum-6;
}
static double status_number(const std::string& status,const std::string& key) {
    const auto start=status.find('"'+key+"\":");
    if(start==std::string::npos) throw std::runtime_error("missing status number");
    return std::stod(status.substr(start+key.size()+3));
}
int main() {
    try {
        StabilizationConfig config;
        HostInput guard(config); guard.begin_session(true);
        require(bool(guard.process(sample(0,Phase::down,.2,.2))),"initial down accepted");
        require(bool(guard.process(sample(1,Phase::move,.21,.2))),"continuous move accepted");
        const auto cancel=guard.process(sample(2,Phase::move,.9,.9));
        require(cancel && cancel->phase==Phase::cancel && cancel->x<.22 && cancel->y==.2 && cancel->pressure==0,"impulse cancels at last safe coordinate");
        require(!guard.process(sample(3,Phase::move,.8,.8)),"poisoned stroke remains suppressed");
        require(!guard.process(sample(4,Phase::down,.8,.8)),"duplicate down cannot resume poisoned stroke");
        require(!guard.process(sample(5,Phase::up,.8,.8)),"suppressed up ends stroke without a distant click");
        require(bool(guard.process(sample(6,Phase::down,.2,.2))),"next fresh stroke resumes");
        const auto up=guard.process(sample(7,Phase::up,.9,.9));
        require(up && up->phase==Phase::up && up->x==.2 && up->pressure==0,"legitimate up is always delivered at safe position");
        require(!guard.process(sample(8,Phase::move)),"orphan move never becomes hover");
        guard.process(sample(9,Phase::down,.2,.2));
        require(guard.process(sample(10,Phase::hover,.9,.9))->phase==Phase::cancel,"contact hover conservatively cancels");
        guard.process(sample(11,Phase::exit));
        require(bool(guard.process(sample(12,Phase::down,.2,.2))),"exit permits next stroke");
        require(guard.process(sample(13,Phase::exit))->phase==Phase::cancel,"contact exit emits safe cancel");
        require(bool(guard.process(sample(14,Phase::down,.2,.2))),"contact exit itself is a reliable boundary");
        guard.process(sample(15,Phase::up));
        guard.process(sample(16,Phase::hover,.2,.2));
        require(!guard.process(sample(17,Phase::hover,.9,.9)),"isolated hover jump is quarantined");
        require(!guard.process(sample(18,Phase::down,.9,.9)),"quarantined hover cannot authorize distant down");
        guard.process(sample(19,Phase::up));
        guard.process(sample(20,Phase::hover,.2,.2));
        guard.process(sample(21,Phase::hover,.9,.9));
        require(bool(guard.process(sample(22,Phase::hover,.91,.9))),"two coherent hover samples establish deliberate reposition");
        require(bool(guard.process(sample(23,Phase::down,.91,.9))),"confirmed hover permits contact");
        auto invalid=sample(24,Phase::move); invalid.x=std::numeric_limits<double>::quiet_NaN();
        bool rejected=false; try { guard.process(invalid); } catch(const std::invalid_argument&) { rejected=true; }
        require(rejected,"nonfinite coordinates are rejected, never clamped to an edge");

        HostInput legacy(config); legacy.begin_session(false);
        auto a=sample(0,Phase::down),b=sample(1,Phase::move,.501,.499);
        legacy.process(a); const auto passed=legacy.process(b);
        require(passed && passed->x==b.x && passed->y==b.y,"legacy coordinates are not filtered twice");
        require(legacy.calibration(1).find("Update the tablet")!=std::string::npos,"legacy calibration does not estimate already filtered noise");
        legacy.calibration(3);
        require(bool(legacy.process(sample(2,Phase::move,.502,.499))) &&
                legacy.process(sample(3,Phase::up))->phase==Phase::up,
                "inactive calibration cancel cannot orphan an existing OS contact");

        HostInput stationary(config); stationary.begin_session(true);
        uint32_t seed=17; double raw_rms=0,filtered_rms=0;
        for(uint64_t i=0;i<1000;++i) {
            auto s=sample(i,i?Phase::move:Phase::down,.5+.0006*noise(seed),.5+.0006*noise(seed));
            const auto out=stationary.process(s); require(bool(out),"stationary sample remains valid");
            if(i>=100) { raw_rms+=std::pow(s.x-.5,2)+std::pow(s.y-.5,2); filtered_rms+=std::pow(out->x-.5,2)+std::pow(out->y-.5,2); }
        }
        raw_rms=std::sqrt(raw_rms/900); filtered_rms=std::sqrt(filtered_rms/900);
        require(filtered_rms<raw_rms*.55,"stationary RMS jitter reduced substantially");
        auto line=[&](double speed) {
            HostInput input(config); input.begin_session(true); double maximum=0;
            for(uint64_t i=0;i<120;++i) {
                auto s=sample(i,i?Phase::move:Phase::down,.1+speed*.01*double(i),.4);
                const auto out=input.process(s); require(bool(out),"straight line is not suppressed"); maximum=std::max(maximum,std::abs(out->x-s.x));
            }
            return maximum;
        };
        const double slow=line(.03),fast=line(.6);
        require(slow<.0018 && fast<.001,"slow and fast motion have bounded lag");
        std::cout<<std::setprecision(9)<<"benchmark stationary_rms_raw="<<raw_rms<<" filtered="<<filtered_rms
            <<" slow_max_error="<<slow<<" slow_equivalent_ms="<<slow/.03*1000
            <<" fast_max_error="<<fast<<" fast_equivalent_ms="<<fast/.6*1000<<'\n';
        HostInput reset(config); reset.begin_session(true); reset.process(sample(0,Phase::down));
        auto same_time=sample(0,Phase::move,.501,.501);
        same_time.pressure=.9; same_time.barrel=true;
        const auto repeated=reset.process(same_time);
        require(repeated && repeated->x==.5 && repeated->y==.5 && repeated->pressure==.9 && repeated->barrel,
                "equal timestamp preserves filtered position while updating pressure and buttons");
        auto gap=sample(30,Phase::move,.51,.51);
        require(reset.process(gap)->x==gap.x,"long sample gap resets smoother");

        HostInput calibration(config); calibration.begin_session(true);
        require(calibration.calibration(1).find("\"active\":true")!=std::string::npos,"calibration starts");
        require(calibration.take_release() && !calibration.take_release(),"start requests one backend release");
        require(calibration.calibration(2).find("\"active\":true")!=std::string::npos,"insufficient calibration remains available to collect");
        seed=29;
        for(uint64_t i=0;i<180;++i) {
            auto s=sample(i,i?Phase::move:Phase::down,.5+.0007*noise(seed),.5+.0007*noise(seed));
            require(!calibration.process(s),"calibration samples cannot click or draw");
        }
        require(calibration.calibration_status().find("\"phase\":\"ready\"")!=std::string::npos,"minimum sample count and duration make calibration ready");
        double saved=0; const auto completed=calibration.calibration(2,[&](double n){saved=n;});
        require(completed.find("\"result\":\"success\"")!=std::string::npos && saved>.0003 && saved<.0012,"robust short-window MAD estimates noise");
        HostInput duplicate_times(config); duplicate_times.begin_session(true); duplicate_times.calibration(1);
        seed=29;
        for(uint64_t i=0;i<180;++i) {
            auto s=sample(i,i?Phase::move:Phase::down,.5+.0007*noise(seed),.5+.0007*noise(seed));
            duplicate_times.process(s);
            s.phase=Phase::move; s.sequence+=1000; s.x+=.01;
            duplicate_times.process(s);
        }
        duplicate_times.process(sample(179,Phase::up));
        duplicate_times.process(sample(180,Phase::move));
        require(duplicate_times.calibration_status().find("\"samples\":180")!=std::string::npos,
                "equal timestamps do not add samples and same-timestamp up closes acquisition contact");
        double duplicate_noise=0;
        require(duplicate_times.calibration(2,[&](double n){duplicate_noise=n;}).find("\"result\":\"success\"")!=std::string::npos &&
                std::abs(duplicate_noise-saved)<1e-12,"timestamp duplicates do not fail or bias noise calibration");
        for(bool backwards:{false,true}) {
            HostInput invalid_time(config); invalid_time.begin_session(true); invalid_time.calibration(1);
            for(uint64_t i=0;i<180;++i) invalid_time.process(sample(i,i?Phase::move:Phase::down));
            invalid_time.process(sample(backwards?178:179,backwards?Phase::move:Phase::down));
            bool persisted=false;
            require(invalid_time.calibration(2,[&](double){persisted=true;}).find("\"result\":\"failed\"")!=std::string::npos && !persisted,
                    "strictly backwards time and same-time duplicate down still invalidate calibration");
        }
        require(!calibration.process(sample(181,Phase::move)),"finish cannot resume existing contact");
        require(bool(calibration.process(sample(182,Phase::down))),"fresh down resumes after finish");
        calibration.calibration(1);
        for(uint64_t i=0;i<180;++i) calibration.process(sample(i,i?Phase::move:Phase::down,.1+.003*i,.5));
        bool wrote=false;
        require(calibration.calibration(2,[&](double){wrote=true;}).find("\"result\":\"failed\"")!=std::string::npos && !wrote,"motion calibration fails without overwriting saved noise");
        calibration.calibration(1); calibration.calibration(3);
        require(calibration.calibration_status().find("\"phase\":\"cancelled\"")!=std::string::npos,"calibration cancel has a terminal status");
        calibration.calibration(1); calibration.process(sample(0,Phase::down)); calibration.end_session();
        require(calibration.calibration_status().find("\"active\":false")!=std::string::npos &&
                calibration.calibration_status().find("disconnected")!=std::string::npos,"disconnect ends acquisition without saving partial samples");
        calibration.begin_session(true);
        require(calibration.calibration_status().find("\"active\":false")!=std::string::npos,"reconnect cannot continue another session's calibration");
        HostInput terminal(config); terminal.begin_session(true); terminal.process(sample(0,Phase::down));
        auto switched_up=sample(1,Phase::up,.9,.9); switched_up.eraser=true;
        require(terminal.process(switched_up)->phase==Phase::up && bool(terminal.process(sample(2,Phase::down))),
                "up remains a terminal boundary despite a tool flag anomaly");
        terminal.end_session(); terminal.begin_session(true);
        auto barrel=sample(0,Phase::down); barrel.barrel=barrel.secondary=true; terminal.process(barrel);
        const auto barrel_up=terminal.process(sample(1,Phase::up));
        require(barrel_up && !barrel_up->barrel && !barrel_up->secondary,
                "up keeps current button metadata while freezing its position");

        StabilizationConfig adaptive_config; adaptive_config.noise=.000115678203379;
        auto fixed_config=adaptive_config; fixed_config.adaptive_noise=false;
        HostInput adaptive(adaptive_config),fixed(fixed_config);
        adaptive.begin_session(true);fixed.begin_session(true);
        seed=512; double adaptive_error=0,fixed_error=0;
        Sample last;
        for(uint64_t i=0;i<700;++i) {
            auto s=sample(i,i?Phase::move:Phase::down,.5+(i<100?.00012:.002)*noise(seed),.5+.0002*noise(seed));
            s.timestamp_ns=1000000000+i*4000000; last=s;
            auto filtered=adaptive.process(s),reference=fixed.process(s);
            require(filtered && reference,"adaptive noise never changes valid contact lifecycle");
            if(i>=300) {
                adaptive_error+=std::pow(filtered->x-.5,2)+std::pow(filtered->y-.5,2);
                fixed_error+=std::pow(reference->x-.5,2)+std::pow(reference->y-.5,2);
            }
        }
        require(adaptive_error<fixed_error*.5,"online noise budgets reduce a sustained white-noise burst");
        const auto learned=adaptive.status();
        const double learned_x=status_number(learned,"estimated_noise_x"),learned_y=status_number(learned,"estimated_noise_y");
        require(learned_x>.001 && learned_y<.0005 && status_number(learned,"noise_samples")==48,
                "noise diagnostics retain distinct bounded X/Y estimates and a bounded valid window");
        require(status_number(adaptive.calibration_status(),"noise")==adaptive_config.noise,
                "runtime learning never overwrites the calibrated prior");
        auto repeated_ns=last; repeated_ns.timestamp_ns+=1; repeated_ns.x+=.001; repeated_ns.pressure=.9; repeated_ns.barrel=true;
        const auto before_ns=adaptive.process(last);
        const auto held_ns=adaptive.process(repeated_ns);
        require(held_ns && before_ns && held_ns->x==before_ns->x && held_ns->y==before_ns->y && held_ns->pressure==.9 && held_ns->barrel,
                "fabricated sub-microsecond time cannot inject positional jitter but preserves metadata");
        require(adaptive.status()==learned,"sub-microsecond samples do not update noise statistics");
        auto lift=repeated_ns;lift.phase=Phase::up;
        require(adaptive.process(lift)->phase==Phase::up,"sub-microsecond up is still delivered");
        require(status_number(adaptive.status(),"noise_samples")==0 && status_number(adaptive.status(),"estimated_noise_x")==learned_x,
                "lift clears the observation window but keeps learned noise for the next stroke");
        auto next=sample(701,Phase::down);next.timestamp_ns=last.timestamp_ns+4000000;
        adaptive.process(next);
        require(status_number(adaptive.status(),"estimated_noise_x")==learned_x,"fresh down preserves learned noise without idle-time adaptation");
        adaptive.end_session();adaptive.begin_session(true);
        require(status_number(adaptive.status(),"estimated_noise_x")==adaptive_config.noise && status_number(adaptive.status(),"noise_samples")==0,
                "a new session resets noise estimates to its calibration prior");
        seed=786;
        for(uint64_t i=0;i<400;++i) {
            auto s=sample(i,i?Phase::move:Phase::down,.5+.002*noise(seed),.5+.002*noise(seed));
            s.timestamp_ns=1000000000+i*4000000;adaptive.process(s);
        }
        require(status_number(adaptive.status(),"estimated_noise_x")>.001,"recovery test establishes a learned noisy condition");
        for(uint64_t i=400;i<1050;++i) {
            auto s=sample(i,Phase::move);s.timestamp_ns=1000000000+i*4000000;adaptive.process(s);
        }
        require(status_number(adaptive.status(),"estimated_noise_x")<adaptive_config.noise*1.25,
                "uninformative quiet windows release stale noise back toward calibration instead of freezing it");
        auto quiet_lift=sample(1050,Phase::up);quiet_lift.timestamp_ns=1000000000+1050*4000000ull;adaptive.process(quiet_lift);
        auto idle_down=sample(1051,Phase::down);idle_down.timestamp_ns=quiet_lift.timestamp_ns+2000000000;adaptive.process(idle_down);
        require(status_number(adaptive.status(),"estimated_noise_x")==adaptive_config.noise && status_number(adaptive.status(),"noise_samples")==0,
                "long source-time idle between strokes discards a stale noise estimate");

        HostInput quantized(adaptive_config),quantized_fixed(fixed_config);
        quantized.begin_session(true);quantized_fixed.begin_session(true);
        for(uint64_t i=0;i<900;++i) {
            auto s=sample(i,i?Phase::move:Phase::down);
            s.timestamp_ns=1000000000+i*4000000;
            s.x=std::round((.2+.025*.004*i)*4096)/4096;
            s.y=std::round((.4+.00003*std::sin(double(i)))*2048)/2048;
            const auto a=quantized.process(s),b=quantized_fixed.process(s);
            require(a && b && a->x==b->x && a->y==b->y,
                    "low-diversity quantizer steps do not inflate noise or change the fixed-prior result");
        }

        HostInput circle(adaptive_config);circle.begin_session(true);seed=951;
        double circle_real=0,circle_imaginary=0;unsigned circle_count=0;
        for(uint64_t i=0;i<1600;++i) {
            const double angle=4*3.141592653589793*.004*i;
            auto s=sample(i,i?Phase::move:Phase::down,.5+.003*std::cos(angle)+.002*noise(seed),.5+.003*std::sin(angle)+.002*noise(seed));
            s.timestamp_ns=1000000000+i*4000000;
            const auto out=circle.process(s);require(bool(out),"small circle remains a continuous stroke");
            if(i>200) {
                circle_real+=((out->x-.5)*std::cos(angle)+(out->y-.5)*std::sin(angle))/.003;
                circle_imaginary+=((out->y-.5)*std::cos(angle)-(out->x-.5)*std::sin(angle))/.003;
                ++circle_count;
            }
        }
        const double gain=std::hypot(circle_real,circle_imaginary)/circle_count;
        const double lag=-std::atan2(circle_imaginary,circle_real)/(4*3.141592653589793);
        require(gain>.90 && gain<1.10 && lag<.025,"adaptive budgets preserve small-circle size and bounded phase lag");

        HostInput corner(adaptive_config);corner.begin_session(true);seed=987;
        double vertex=1,overshoot=0;
        for(uint64_t i=0;i<1500;++i) {
            const double time=.004*i;
            auto s=sample(i,i?Phase::move:Phase::down,.2+.12*std::min(time,3.0)+.0003*noise(seed),.4+.12*std::max(time-3,0.0)+.0003*noise(seed));
            s.timestamp_ns=1000000000+i*4000000;
            const auto out=corner.process(s);require(bool(out),"corner remains a continuous stroke");
            if(time>2.8 && time<3.2) vertex=std::min(vertex,std::hypot(out->x-.56,out->y-.4));
            if(time>=3 && time<3.2) overshoot=std::max(overshoot,out->x-.56);
        }
        require(vertex<.001 && overshoot<.0015,"corner vertex and overshoot remain bounded");
        std::cout<<"adaptive white_burst_energy_ratio="<<adaptive_error/fixed_error<<" circle_gain="<<gain<<" circle_lag_ms="<<lag*1000
                 <<" corner_vertex_miss="<<vertex<<" corner_overshoot="<<overshoot<<'\n';

        require(!StabilizationConfig{}.independent_axes,"straight-stroke mode is an explicit opt-in");
        for(bool learn:{false,true}) {
            auto ordinary_config=adaptive_config; ordinary_config.adaptive_noise=learn;
            auto independent_config=ordinary_config; independent_config.independent_axes=true;
            HostInput ordinary(ordinary_config),independent(independent_config);
            ordinary.begin_session(true); independent.begin_session(true);
            seed=12001; double ordinary_normal=0,independent_normal=0,along_difference=0;
            Sample final_point;
            for(uint64_t i=0;i<1000;++i) {
                auto s=sample(i,i?Phase::move:Phase::down,.5+.000134595*noise(seed),.1+.12*.004*i+.0000278048*noise(seed));
                s.timestamp_ns=1000000000+i*4000000; final_point=s;
                const auto a=ordinary.process(s),b=independent.process(s);
                require(a && b,"both straight-stroke modes retain a valid moving contact");
                if(i>100) {
                    ordinary_normal+=std::pow(a->x-.5,2);independent_normal+=std::pow(b->x-.5,2);
                    along_difference=std::max(along_difference,std::abs(a->y-b->y));
                }
            }
            std::cout<<"independent adaptive="<<learn<<" vertical_transverse_energy_ratio="<<independent_normal/ordinary_normal
                     <<" along_difference="<<along_difference<<'\n';
            require(independent_normal<ordinary_normal*.5 && along_difference<.00001,
                    "independent budgets reduce fast vertical transverse noise with less than 0.1 ms extra axial lag");
            for(const auto* key:{"estimated_noise_x","estimated_noise_y","noise_samples"})
                require(status_number(ordinary.status(),key)==status_number(independent.status(),key),
                        "independent budgets do not change adaptive-noise observation or saved calibration");
            require(independent.status().find("\"independent_axes\":true")!=std::string::npos &&
                    ordinary.status().find("\"independent_axes\":false")!=std::string::npos,
                    "runtime status distinguishes the opt-in mode from adaptive noise");
            const auto held=independent.process(final_point);
            final_point.x+=.01; final_point.pressure=.8; final_point.barrel=true;
            const auto metadata=independent.process(final_point);
            require(held && metadata && held->x==metadata->x && held->y==metadata->y && metadata->barrel && metadata->pressure==.8,
                    "independent-axis same-time sample preserves position and updates metadata");
            auto spike=final_point; spike.sequence++;spike.timestamp_ns+=4000000;spike.x=.05;spike.y=.9;
            const auto safe_cancel=independent.process(spike);
            require(safe_cancel && safe_cancel->phase==Phase::cancel && safe_cancel->x==held->x && safe_cancel->y==held->y,
                    "independent-axis filtering cannot bypass safe-position jump cancellation");
            for(bool independent_mode:{false,true}) {
                auto shape_config=ordinary_config;shape_config.independent_axes=independent_mode;
                HostInput shape(shape_config);shape.begin_session(true);
                double real=0,imaginary=0,maximum=0;unsigned count=0;
                for(uint64_t i=0;i<1000;++i) {
                    const double angle=4*3.141592653589793*.004*i+.7853981633974483;
                    auto s=sample(i,i?Phase::move:Phase::down,.5+.003*std::cos(angle),.5+.003*std::sin(angle));
                    s.timestamp_ns=1000000000+i*4000000;
                    const auto out=shape.process(s);require(bool(out),"all four noise/axis modes preserve a small-circle contact");
                    if(i>100) {
                        real+=((out->x-.5)*std::cos(angle)+(out->y-.5)*std::sin(angle))/.003;
                        imaginary+=((out->y-.5)*std::cos(angle)-(out->x-.5)*std::sin(angle))/.003;
                        maximum=std::max(maximum,std::hypot(out->x-s.x,out->y-s.y));++count;
                    }
                }
                const double radius=std::hypot(real,imaginary)/count;
                const double phase_lag=-std::atan2(imaginary,real)/(4*3.141592653589793);
                require(radius>.97 && radius<1.001 && phase_lag<.006 && maximum<.00035,
                        "opt-in circle distortion remains bounded without claiming identical shape");
                auto end=sample(1000,Phase::up,.9,.9);end.timestamp_ns=5000000000;
                require(shape.process(end)->phase==Phase::up,"all four noise/axis modes deliver safe up");
                std::cout<<"independent adaptive="<<learn<<" mode="<<independent_mode<<" circle_radius="<<radius
                         <<" circle_phase_ms="<<phase_lag*1000<<" circle_max_error="<<maximum<<'\n';
            }
        }

        Mapping mapping; mapping.pressure_interpolation="smooth";
        mapping.pressure_curve={{0,0},{.1,.2},{.4,.2},{.7,.85},{1,1}};
        Mapper mapper(mapping); double previous=-1;
        for(unsigned i=0;i<=1000;++i) {
            auto s=sample(i,Phase::move); s.pressure=double(i)/1000; const double p=mapper.map(s).pressure;
            require(p>=previous && p>=0 && p<=1,"PCHIP pressure remains monotone and bounded"); previous=p;
        }
        for(const auto& knot:mapping.pressure_curve) { auto s=sample(0,Phase::move); s.pressure=knot.first; require(std::abs(mapper.map(s).pressure-knot.second)<1e-12,"PCHIP interpolates exact knots"); }
        for(const auto& text:{std::string("{\"hostStabilization\":1}"),std::string("{\"mappingControl\":1,\"hostStabilization\":1}")})
            require(stabilization_capability({text.begin(),text.end()}),"raw host stabilization capability negotiated");
        for(const auto& text:{std::string("{\"hostStabilization\":true}"),std::string("{\"hostStabilization\":1.5}"),std::string("{\"hostStabilization\":1,\"hostStabilization\":1}")})
            require(!stabilization_capability({text.begin(),text.end()}),"malformed or noninteger capability does not opt in");
        std::cout<<"stabilization checks="<<checks<<'\n'; return 0;
    } catch(const std::exception& error) { std::cerr<<"FAILED: "<<error.what()<<'\n'; return 1; }
}
