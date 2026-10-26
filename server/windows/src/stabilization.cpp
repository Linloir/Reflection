#include "reflection/stabilization.hpp"
#include "reflection/config.hpp"
#include <iomanip>
#include <sstream>

namespace reflection {
namespace {
double distance(const Sample& a, const Sample& b) { return std::hypot(a.x-b.x, a.y-b.y); }
double alpha(double cutoff, double dt) { const double r = 6.283185307179586 * cutoff * dt; return r/(1+r); }
double quantile(std::vector<double> values, double fraction) {
    std::sort(values.begin(), values.end());
    const double index = fraction * (values.size()-1);
    const auto lo = static_cast<size_t>(index), hi = std::min(lo+1, values.size()-1);
    return values[lo] + (values[hi]-values[lo])*(index-lo);
}
double residual_noise(const std::vector<Sample>& samples, size_t begin, size_t end, bool y) {
    double mt=0, mv=0, covariance=0, variance=0;
    const double n = double(end-begin);
    for (size_t i=begin;i<end;++i) { mt += double(samples[i].timestamp_ns-samples[begin].timestamp_ns)*1e-9; mv += y?samples[i].y:samples[i].x; }
    mt/=n; mv/=n;
    for (size_t i=begin;i<end;++i) {
        const double t=double(samples[i].timestamp_ns-samples[begin].timestamp_ns)*1e-9-mt;
        covariance+=t*((y?samples[i].y:samples[i].x)-mv); variance+=t*t;
    }
    const double slope=variance>0?covariance/variance:0;
    std::vector<double> residuals;
    for (size_t i=begin;i<end;++i)
        residuals.push_back((y?samples[i].y:samples[i].x)-mv-slope*(double(samples[i].timestamp_ns-samples[begin].timestamp_ns)*1e-9-mt));
    const double median=quantile(residuals,.5);
    for (auto& value:residuals) value=std::abs(value-median);
    return 1.4826*quantile(residuals,.5);
}
}
void StabilizationConfig::validate() const {
    auto range=[](double v,double lo,double hi,const char* key) { if(!std::isfinite(v)||v<lo||v>hi) throw std::invalid_argument(std::string("invalid stabilization.")+key); };
    range(strength,0,1,"strength"); range(min_cutoff_hz,.1,60,"min_cutoff_hz");
    range(beta,0,1000,"beta"); range(derivative_cutoff_hz,.1,120,"derivative_cutoff_hz");
    range(noise,.00001,.02,"noise"); range(max_jump,.05,1,"max_jump");
}
HostInput::HostInput(StabilizationConfig config):config_(config),previous_noise_(config.noise) { config_.validate(); reset_noise_locked(true); }
void HostInput::reset_locked() { contact_=false; anchored_=false; filter_ready_=false; hover_candidate_=false; reset_noise_locked(false); }
void HostInput::begin_session(bool raw) { std::lock_guard<std::mutex> lock(mutex_); reset_locked(); reset_noise_locked(true); connected_=true; raw_=raw; suppressed_=false; fresh_down_=collecting_; }
void HostInput::negotiate(bool raw) { std::lock_guard<std::mutex> lock(mutex_); raw_=raw; filter_ready_=false; reset_noise_locked(true); }
void HostInput::end_session() {
    std::lock_guard<std::mutex> lock(mutex_); reset_locked(); reset_noise_locked(true); connected_=false; raw_=false;
    if(collecting_) { collecting_=false; phase_="failed"; result_="failed"; reason_="Tablet disconnected during calibration; reconnect and start again."; }
}
void HostInput::reset_noise_locked(bool reset_estimate) {
    noise_history_.clear(); noise_window_.clear(); noise_count_=0; noise_update_time_=0;
    noise_updated_=false; noise_eligible_=false; noise_phase_known_=false;
    if(reset_estimate) { noise_x_=noise_y_=config_.noise; noise_has_time_=false; noise_latest_time_=0; }
}
void HostInput::observe_noise_locked(const Sample& raw) {
    if(!config_.adaptive_noise) return;
    if(noise_has_time_ && raw.timestamp_ns>=noise_latest_time_ && raw.timestamp_ns-noise_latest_time_>1000000000)
        reset_noise_locked(true);
    noise_latest_time_=raw.timestamp_ns; noise_has_time_=true;
    const bool hover=raw.phase==Phase::hover;
    if(raw.phase==Phase::down || (noise_phase_known_ && (hover!=noise_hover_ || raw.eraser!=noise_eraser_)) ||
       (!noise_history_.empty() && (raw.timestamp_ns<noise_history_.back().timestamp_ns ||
        raw.timestamp_ns-noise_history_.back().timestamp_ns>100000000))) reset_noise_locked(false);
    noise_phase_known_=true; noise_hover_=hover; noise_eraser_=raw.eraser;
    if(!noise_history_.empty() && raw.timestamp_ns-noise_history_.back().timestamp_ns<1000) return;
    noise_history_.push_back(raw);
    if(noise_history_.size()>3) noise_history_.pop_front();
    ++noise_count_;
    if(noise_history_.size()==3) {
        const auto dt1=noise_history_[1].timestamp_ns-noise_history_[0].timestamp_ns;
        const auto dt2=noise_history_[2].timestamp_ns-noise_history_[1].timestamp_ns;
        const double ratio=dt1?double(dt2)/double(dt1):0;
        if(dt1>=1000 && dt2>=1000 && dt1<=20000000 && dt2<=20000000 && ratio>=.5 && ratio<=2) {
            // This annihilates constant velocity even with unequal intervals.
            // The denominator is the independent-noise coefficient energy;
            // equal intervals reduce to second differences divided by sqrt(6).
            const double scale=std::sqrt(1+(1+ratio)*(1+ratio)+ratio*ratio);
            noise_window_.push_back({raw.timestamp_ns,
                (noise_history_[2].x-(1+ratio)*noise_history_[1].x+ratio*noise_history_[0].x)/scale,
                (noise_history_[2].y-(1+ratio)*noise_history_[1].y+ratio*noise_history_[0].y)/scale});
            if(noise_window_.size()>48) noise_window_.pop_front();
        } else {
            noise_window_.clear(); noise_updated_=false; noise_eligible_=false;
        }
    }
    while(!noise_window_.empty() && raw.timestamp_ns-noise_window_.front().timestamp_ns>250000000)
        noise_window_.pop_front();
    if(noise_window_.size()<16 || noise_window_.back().timestamp_ns-noise_window_.front().timestamp_ns<40000000) {
        noise_eligible_=false; return;
    }
    if(noise_count_%4!=0) return;
    const double elapsed=noise_updated_?std::min(.1,double(raw.timestamp_ns-noise_update_time_)*1e-9):.016;
    auto update_axis=[&](bool y,double& sigma) {
        std::vector<double> values,recent;
        values.reserve(noise_window_.size()); recent.reserve(8);
        for(size_t i=0;i<noise_window_.size();++i) {
            const double value=y?noise_window_[i].y:noise_window_[i].x;
            values.push_back(value);
            if(i+8>=noise_window_.size()) recent.push_back(value);
        }
        const double center=quantile(values,.5);
        auto deviations=values;
        for(auto& value:deviations) value=std::abs(value-center);
        double estimate=std::clamp(1.4826*quantile(deviations,.5),config_.noise,.02);
        // Do not teach persistent curvature or a few quantizer steps as Gaussian
        // jitter. The calibrated prior remains the conservative lower bound.
        const bool curvature=std::abs(quantile(recent,.5))>3*std::max(sigma,estimate);
        std::sort(values.begin(),values.end());
        const double resolution=std::max(1e-7,config_.noise*.05);
        unsigned levels=1; double anchor=values.front();
        for(size_t i=1;i<values.size();++i) if(values[i]-anchor>=resolution) { ++levels; anchor=values[i]; }
        const bool eligible=!curvature && levels>=8;
        // An uninformative quiet/quantized window must not keep a past burst's
        // larger budget forever. It cannot raise sigma, but gently releases back
        // toward the trusted calibration baseline rather than learning its steps.
        if(!eligible) estimate=config_.noise;
        const double blend=1-std::exp(-elapsed/(estimate>sigma?.06:.5));
        sigma+=blend*(estimate-sigma);
        return eligible;
    };
    const bool x=update_axis(false,noise_x_),y=update_axis(true,noise_y_);
    noise_eligible_=x||y; noise_update_time_=raw.timestamp_ns; noise_updated_=true;
}
bool HostInput::take_release() { std::lock_guard<std::mutex> lock(mutex_); const bool value=release_; release_=false; return value; }
Sample HostInput::cancel_locked(const Sample& raw) {
    Sample result=safe_; result.sequence=raw.sequence; result.timestamp_ns=raw.timestamp_ns;
    result.phase=Phase::cancel; result.pressure=0;
    ++rejected_; ++suppressed_strokes_; reset_locked(); suppressed_=true;
    return result;
}
Sample HostInput::filter_locked(Sample s) {
    if (!raw_ || !config_.enabled || config_.strength==0) { filter_ready_=false; return s; }
    if(filter_ready_ && s.phase==Phase::hover && s.eraser!=safe_.eraser) {
        filter_ready_=false; reset_noise_locked(false);
    }
    if (filter_ready_ && s.phase != Phase::down && s.timestamp_ns >= filter_time_ && s.timestamp_ns-filter_time_<1000) {
        // Android history may contain multiple samples within one timestamp tick.
        // Keep the previous final position without inventing velocity or reseeding
        // the filter; pressure and button changes still reach the backend.
        s.x = safe_.x; s.y = safe_.y;
        return s;
    }
    observe_noise_locked(s);
    const double dt=filter_ready_ && s.timestamp_ns>filter_time_ ? double(s.timestamp_ns-filter_time_)*1e-9 : 0;
    if(!filter_ready_ || dt<=0 || dt>.1 || s.phase==Phase::down) {
        fx_=rx_=s.x; fy_=ry_=s.y; dx_=dy_=0; filter_ready_=true;
    } else {
        const double a=alpha(config_.derivative_cutoff_hz,dt);
        dx_+=a*((s.x-rx_)/dt-dx_); dy_+=a*((s.y-ry_)/dt-dy_);
        const double speed=std::hypot(dx_,dy_);
        const double cutoff=config_.min_cutoff_hz/(.25+1.5*config_.strength)+config_.beta*speed;
        const double b=alpha(cutoff,dt);
        fx_+=b*(s.x-fx_); fy_+=b*(s.y-fy_);
        const double mix=std::min(1.0,2*config_.strength);
        double ex=mix*(fx_-s.x), ey=mix*(fy_-s.y);
        auto budget=[&](double noise,double motion) { return std::max(noise*.5,noise*(1+4*config_.strength)/(1+motion/(noise*120+1e-6))); };
        const double bx=budget(config_.adaptive_noise?noise_x_:config_.noise,config_.independent_axes?std::abs(dx_):speed);
        const double by=budget(config_.adaptive_noise?noise_y_:config_.noise,config_.independent_axes?std::abs(dy_):speed);
        if(config_.independent_axes) {
            // An opt-in straight-stroke mode: motion and lag in one axis cannot
            // consume the other's correction budget. The shared cutoff above
            // remains unchanged. This may alter tiny circles and diagonal turns.
            ex=std::clamp(ex,-bx,bx); ey=std::clamp(ey,-by,by);
        } else {
            const double bound=std::max(bx,by);
            const double error=std::hypot(ex,ey);
            if(error>bound) { ex*=bound/error; ey*=bound/error; }
            if(config_.adaptive_noise) { ex=std::clamp(ex,-bx,bx); ey=std::clamp(ey,-by,by); }
        }
        rx_=s.x; ry_=s.y; s.x+=ex; s.y+=ey;
    }
    filter_time_=s.timestamp_ns;
    return s;
}
std::optional<Sample> HostInput::process(const Sample& raw) {
    validate_sample(raw);
    std::lock_guard<std::mutex> lock(mutex_);
    expire_locked();
    if(collecting_) { collect_locked(raw); return {}; }
    if(fresh_down_) {
        if(raw.phase!=Phase::down) return {};
        fresh_down_=false; suppressed_=false; anchored_=false;
    }
    if(suppressed_) {
        if(raw.phase==Phase::up || raw.phase==Phase::cancel || raw.phase==Phase::exit) { suppressed_=false; anchored_=false; }
        else return {};
        return {};
    }
    if(contact_) {
        if(raw.phase==Phase::up) {
            auto result=raw; result.x=safe_.x; result.y=safe_.y; result.eraser=safe_.eraser; result.pressure=0;
            if(distance(raw,safe_)>config_.max_jump || raw.eraser!=safe_.eraser) ++rejected_;
            contact_=false; filter_ready_=false; anchored_=false; reset_noise_locked(false);
            return result;
        }
        if(raw.phase==Phase::cancel) { auto result=cancel_locked(raw); suppressed_=false; return result; }
        if(raw.phase==Phase::exit) { auto result=cancel_locked(raw); suppressed_=false; return result; }
        if(raw.eraser!=safe_.eraser || raw.phase==Phase::hover || raw.phase==Phase::down)
            return cancel_locked(raw);
        if(raw.phase==Phase::move && distance(raw,safe_)>config_.max_jump) return cancel_locked(raw);
    } else {
        if(raw.phase==Phase::move || raw.phase==Phase::up) { ++rejected_; return {}; }
        if(raw.phase==Phase::cancel || raw.phase==Phase::exit) { reset_locked(); return raw; }
        if(raw.phase==Phase::down) {
            const bool recent=anchored_ && raw.timestamp_ns>=safe_.timestamp_ns && raw.timestamp_ns-safe_.timestamp_ns<=150000000;
            if(recent && distance(raw,safe_)>config_.max_jump) { ++rejected_; ++suppressed_strokes_; suppressed_=true; filter_ready_=false; return {}; }
            contact_=true;
            hover_candidate_=false;
        } else if(raw.phase==Phase::hover) {
            const bool recent=anchored_ && raw.timestamp_ns>=safe_.timestamp_ns && raw.timestamp_ns-safe_.timestamp_ns<=150000000;
            if(recent && distance(raw,safe_)>config_.max_jump) {
                if(!hover_candidate_ || distance(raw,candidate_)>config_.max_jump) {
                    candidate_=raw; hover_candidate_=true; ++rejected_; return {};
                }
                // Two consistent hover samples establish a deliberate new area; a
                // single stray hover cannot authorize a distant contact immediately.
                filter_ready_=false;
            }
            hover_candidate_=false;
        }
    }
    auto result=filter_locked(raw);
    safe_=result; anchored_=true;
    return result;
}
double HostInput::duration_locked() const { return samples_.size()<2?0:double(samples_.back().timestamp_ns-samples_.front().timestamp_ns)/1e6; }
void HostInput::expire_locked() {
    if(collecting_ && std::chrono::steady_clock::now()-started_>=std::chrono::seconds(10)) {
        collecting_=false; phase_="failed"; result_="failed"; reason_="Calibration timed out after 10 seconds; hold the pen still and try again."; suppressed_=true;
    }
}
void HostInput::collect_locked(const Sample& raw) {
    if(!raw_) { collecting_=false; phase_="failed"; result_="failed"; reason_="Update the tablet client to calibrate raw coordinates."; return; }
    if(raw.phase==Phase::down) {
        if(calibration_seen_) { calibration_invalid_=true; reason_="Use one continuous stationary pen contact."; }
        calibration_seen_=true; calibration_contact_=true;
    } else if(raw.phase==Phase::up || raw.phase==Phase::cancel || raw.phase==Phase::exit) {
        if(raw.phase!=Phase::up && calibration_contact_) { calibration_invalid_=true; reason_="The pen contact was interrupted."; }
        calibration_contact_=false; return;
    } else if(raw.phase==Phase::hover) {
        if(calibration_contact_) { calibration_invalid_=true; reason_="Hover arrived during contact; restart calibration."; }
        return;
    }
    if(!calibration_contact_) return;
    if(!samples_.empty() && (raw.eraser!=samples_.front().eraser || raw.timestamp_ns<samples_.back().timestamp_ns)) {
        calibration_invalid_=true; reason_="Tool or sample timing changed; restart calibration."; return;
    }
    // Deduplicate positions after lifecycle processing: an UP/CANCEL/EXIT or a
    // duplicate DOWN at the same timestamp must still close/invalidate the hold.
    if(!samples_.empty() && raw.timestamp_ns==samples_.back().timestamp_ns) return;
    if(samples_.size()>=4096) { collecting_=false; phase_="failed"; result_="failed"; reason_="Too many calibration samples; try again."; return; }
    samples_.push_back(raw);
    if(samples_.size()>=120 && duration_locked()>=1200) phase_="ready";
}
std::string HostInput::calibration_json_locked() const {
    std::ostringstream out; out<<std::setprecision(12);
    out<<"{\"active\":"<<(collecting_?"true":"false")<<",\"phase\":"<<json_escape(phase_)<<",\"samples\":"<<samples_.size()
       <<",\"duration_ms\":"<<duration_locked()<<",\"progress\":"<<std::min({1.0,double(samples_.size())/120,duration_locked()/1200})
       <<",\"noise\":"<<config_.noise<<",\"previous_noise\":"<<previous_noise_<<",\"result\":"<<json_escape(result_)<<",\"reason\":"<<json_escape(reason_)<<'}';
    return out.str();
}
std::string HostInput::calibration_status() { std::lock_guard<std::mutex> lock(mutex_); expire_locked(); return calibration_json_locked(); }
std::string HostInput::calibration(unsigned action,const std::function<void(double)>& persist) {
    std::lock_guard<std::mutex> lock(mutex_); expire_locked();
    if(action==1) {
        if(connected_ && !raw_) { result_="failed"; phase_="failed"; reason_="Update the tablet client to calibrate raw coordinates."; return calibration_json_locked(); }
        reset_locked(); reset_noise_locked(true); suppressed_=false; fresh_down_=true; release_=true; collecting_=true;
        calibration_contact_=calibration_seen_=calibration_invalid_=false; samples_.clear(); previous_noise_=config_.noise;
        phase_="collecting"; result_="collecting"; reason_="Lift the pen, then hold it still in one continuous contact for at least 1.2 seconds."; started_=std::chrono::steady_clock::now();
    } else if(action==3) {
        if(!collecting_) return calibration_json_locked();
        collecting_=false; phase_="cancelled"; result_="cancelled"; reason_.clear(); suppressed_=true; reset_locked();
    } else if(action==2) {
        if(!collecting_) { result_="failed"; reason_="Start calibration first."; }
        else if(samples_.size()<120 || duration_locked()<1200) { result_="failed"; phase_="collecting"; reason_="Collect at least 120 stationary samples over 1.2 seconds, then finish."; }
        else {
            try {
                if(calibration_invalid_) throw std::runtime_error(reason_);
                double minx=1,maxx=0,miny=1,maxy=0;
                for(const auto& s:samples_) { minx=std::min(minx,s.x); maxx=std::max(maxx,s.x); miny=std::min(miny,s.y); maxy=std::max(maxy,s.y); }
                if(std::hypot(maxx-minx,maxy-miny)>.08) throw std::runtime_error("The pen moved too far; hold it still and retry.");
                std::vector<double> estimates;
                for(size_t begin=0;begin<samples_.size();) {
                    size_t end=begin+1;
                    while(end<samples_.size() && samples_[end].timestamp_ns-samples_[begin].timestamp_ns<160000000) ++end;
                    if(end-begin>=8) {
                        if(distance(samples_[begin],samples_[end-1])>.02) throw std::runtime_error("The pen moved within a calibration window; retry while stationary.");
                        estimates.push_back(std::max(residual_noise(samples_,begin,end,false),residual_noise(samples_,begin,end,true)));
                    }
                    begin=end;
                }
                if(estimates.size()<3) throw std::runtime_error("Not enough evenly timed samples; try again.");
                const double noise=quantile(estimates,.75);
                if(noise>.02) throw std::runtime_error("Calibration noise is too high; try again.");
                const double measured=std::clamp(noise,.00001,.02);
                if(persist) persist(measured);
                config_.noise=measured; filter_ready_=false; reset_noise_locked(true); phase_="complete"; result_="success"; reason_.clear();
            } catch(const std::exception& error) { phase_="failed"; result_="failed"; reason_=error.what(); }
            collecting_=false; suppressed_=true; reset_locked();
        }
    } else if(action!=0) throw std::invalid_argument("invalid calibration action");
    return calibration_json_locked();
}
std::string HostInput::status() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::ostringstream noise;
    noise<<std::setprecision(12)<<",\"adaptive_noise\":"<<(config_.adaptive_noise?"true":"false")
        <<",\"independent_axes\":"<<(config_.independent_axes?"true":"false")
        <<",\"estimated_noise_x\":"<<noise_x_<<",\"estimated_noise_y\":"<<noise_y_
        <<",\"noise_samples\":"<<(noise_eligible_?noise_window_.size():0);
    return std::string("{\"enabled\":")+(config_.enabled?"true":"false")+",\"active\":"+(connected_&&raw_&&!collecting_&&config_.enabled&&config_.strength>0?"true":"false")+
        ",\"guard_enabled\":true,\"guard_rejections\":"+std::to_string(rejected_)+",\"suppressed_strokes\":"+std::to_string(suppressed_strokes_)+
        ",\"contact_down\":"+(contact_?"true":"false")+
        ",\"legacy_client\":"+(connected_&&!raw_?"true":"false")+noise.str()+"}";
}
} // namespace reflection
