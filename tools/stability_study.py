"""Causal pen-filter study with known synthetic truth, NOT measured device data.

Run: python tools/stability_study.py --seeds 3
Writes to --output-dir (default .local/stability-study). NumPy/Matplotlib are used for analysis, never input injection.
"""
from pathlib import Path
from collections import deque
import argparse
import json
import numpy as np

PRIOR = .000115678203379
FS = 250.0
DT = 1 / FS
OUT = Path(__file__).resolve().parents[1] / '.local/stability-study'
METHODS = ['current', 'no_bound_ablation', 'MAD_bound', 'MAD_axis_bounds', 'MAD_ellipse_bound', 'MAD_motion_gate', 'MAD_motion_energy', 'Kalman_MAD', 'Kalman_innovation', 'adaptive_axis']
TRAJECTORIES = ['stationary', 'slow_line', 'fast_line', 'small_circle', 'sharp_corner', 'start_stop']
MODELS = ['white_variable', 'narrowband_60Hz', 'alias_260Hz', 'colored_bursts', 'quantization', 'single_spikes', 'source_jitter_loss', 'fabricated_1ns']

def alpha(cutoff, dt):
    r = 2*np.pi*cutoff*dt
    return r/(1+r)

def truth_at(t, name):
    p=np.tile([.2,.4],(len(t),1))
    phase=np.ones(len(t),dtype=int)*2
    phase[0]=1;phase[-1]=3
    if name=='slow_line':p[:,0]+=.025*t
    if name=='fast_line':p[:,0]=.1+.12*t
    if name=='small_circle':
        p[:,0]=.5+.003*np.cos(2*np.pi*2*t)
        p[:,1]=.5+.003*np.sin(2*np.pi*2*t)
    if name=='sharp_corner':
        p[:,0]+=.12*np.minimum(t,3)
        p[:,1]+=.12*np.maximum(t-3,0)
    if name=='start_stop':
        phase[:]=0
        for begin,end,offset in [(.25,2.75,0),(3.25,5.75,.18)]:
            s=np.clip((t-begin)/(end-begin),0,1)
            shape=3*s*s-2*s*s*s
            mask=(t>=begin)&(t<=end)
            p[mask,0]=.25+offset+.16*shape[mask]
            p[mask,1]=.4+.02*np.sin(2*np.pi*shape[mask])
            indices=np.flatnonzero(mask)
            phase[indices]=2
            if len(indices):phase[indices[0]]=1;phase[indices[-1]]=3
        # Between strokes the hover location is immaterial to drawing metrics.
    return p,phase

def generate(trajectory, model, seed):
    rng=np.random.default_rng(seed)
    t=np.arange(0,6,DT)
    if model=='source_jitter_loss':
        t=np.cumsum(np.clip(rng.normal(DT,.0012,len(t)),.0004,.009));t-=t[0]
        t=t[t<6]
        keep=rng.random(len(t))>.12
        keep&=~((t>2.7)&(t<2.76));keep&=~((t>4.5)&(t<4.61));keep[0]=True;keep[-1]=True
        t=t[keep]
    if model=='fabricated_1ns':t=np.repeat(t,2)
    truth,phase=truth_at(t,trajectory)
    sigma=np.where(t<1.5,.00012,np.where(t<3,.002,np.where(t<4.5,.0006,.0002)))
    noise=rng.normal(size=truth.shape)*sigma[:,None]
    if model in ('narrowband_60Hz','alias_260Hz'):
        frequency=60 if model=='narrowband_60Hz' else 260
        amplitude=np.where((t>1.5)&(t<4.5),.0015,.0003)
        noise=rng.normal(size=truth.shape)*.00012
        noise+=amplitude[:,None]*np.column_stack([np.sin(2*np.pi*frequency*t+.4),.45*np.sin(2*np.pi*frequency*t+1.3)])
    if model=='colored_bursts':
        noise[:]=0
        tau=-DT/np.log(.85)
        state=np.zeros(2)
        for i in range(1,len(t)):
            rho=np.exp(-(t[i]-t[i-1])/tau)
            burst=(1.5<t[i]<2.8) or (4.2<t[i]<4.4)
            scale=np.array([.0002,.002]) if burst else np.array([.0001,.0002])
            state=rho*state+np.sqrt(1-rho*rho)*rng.normal(size=2)*scale
            angle=np.pi/4 if t[i]>3 else 0
            c,s=np.cos(angle),np.sin(angle)
            noise[i]=np.array([[c,-s],[s,c]])@state
    if model=='quantization':
        noisy=truth+rng.normal(size=truth.shape)*.00004
        steps=np.array([1/4096,1/2048])
        noise=np.round(noisy/steps)*steps-truth
    if model=='single_spikes':
        noise=rng.normal(size=truth.shape)*.00012
        for when in [1.7,3.2,4.3]:noise[np.argmin(abs(t-when))]+=[.008,-.005]
    timestamps=t.copy()
    if model=='fabricated_1ns':
        timestamps[1::2]+=1e-9
        # Only the first contact of each stroke is a DOWN; no lifecycle duplication.
        phase[1::2]=np.where(phase[1::2]==1,2,phase[1::2])
        if phase[-2]==3:phase[-2]=2
    return dict(t=t,timestamp=timestamps,truth=truth,raw=truth+noise,phase=phase,trajectory=trajectory,model=model,seed=seed)

class OnlineNoise:
    """48-point second-difference MAD; freeze irregular-dt or coherent curvature.

    For uniform independent Gaussian coordinate noise, Var(delta2)=6*sigma**2.
    This deliberately makes no claim to identify colored, periodic or aliased noise.
    """
    def __init__(self,conservative=False):
        self.positions=deque(maxlen=3);self.times=deque(maxlen=3)
        self.dd=deque(maxlen=48);self.sigma=np.ones(2)*PRIOR;self.count=0
        self.last_update=None
        self.conservative=conservative;self.last_phase=None;self.residual_times=deque(maxlen=48)
        self.latest_time=None
    def reset_window(self):
        self.positions.clear();self.times.clear();self.dd.clear();self.residual_times.clear();self.count=0;self.last_update=None
    def step(self,z,t,phase=2):
        if self.conservative:
            if phase in (1,3,4,5) or (self.last_phase is not None and (phase==0)!=(self.last_phase==0)) or (self.times and t-self.times[-1]>.1):
                self.reset_window()
            self.last_phase=phase
            if phase in (3,4,5):return self.sigma.copy()
        if self.times and t-self.times[-1]<1e-6:return self.sigma.copy()
        if self.conservative:
            if self.latest_time is not None and t-self.latest_time>1:
                self.reset_window();self.sigma[:]=PRIOR
            self.latest_time=t
        self.positions.append(z.copy());self.times.append(t);self.count+=1
        if len(self.positions)==3:
            d1=self.times[1]-self.times[0];d2=self.times[2]-self.times[1]
            low,high=(.5,2) if self.conservative else (.8,1.25)
            if d1>=1e-6 and low<=d2/d1<=high and d2<=.02 and (not self.conservative or d1<=.02):
                # Remove constant velocity for nonidentical but close intervals;
                # normalize by the exact white-noise coefficient energy.
                ratio=d2/d1
                self.dd.append((self.positions[2]-(1+ratio)*self.positions[1]+ratio*self.positions[0])/
                    np.sqrt(1+(1+ratio)**2+ratio**2))
                self.residual_times.append(t)
            elif self.conservative:
                self.dd.clear();self.residual_times.clear();self.last_update=None
        if self.conservative:
            while self.residual_times and t-self.residual_times[0]>.25:
                self.residual_times.popleft();self.dd.popleft()
        sufficient=len(self.dd)>=16 and (not self.conservative or self.residual_times[-1]-self.residual_times[0]>=.04)
        if sufficient and self.count%4==0:
            a=np.array(self.dd);center=np.median(a,axis=0)
            estimate=np.clip(1.4826*np.median(abs(a-center),axis=0),PRIOR if self.conservative else 1e-5,.02)
            coherent=abs(np.median(a[-8:],axis=0))>3*np.maximum(self.sigma,estimate)
            if self.conservative:
                # A few quantizer levels are not a Gaussian noise population.
                # Learning their step height would smooth intended grid crossings.
                diverse=[]
                for axis in range(2):
                    ordered=np.sort(a[:,axis]);levels=1;anchor=ordered[0]
                    for value in ordered[1:]:
                        if value-anchor>=max(1e-7,PRIOR*.05):levels+=1;anchor=value
                    diverse.append(levels>=8)
                coherent |= ~np.array(diverse)
                # Confidence gates prevent upward learning, never retain a stale
                # large budget indefinitely after the environment becomes quiet.
                estimate=np.where(coherent,PRIOR,estimate)
            delta=.016 if self.last_update is None else min(.1,t-self.last_update)
            blend=1-np.exp(-delta/np.where(estimate>self.sigma,.06,.5))
            self.sigma=self.sigma+blend*(estimate-self.sigma) if self.conservative else np.where(coherent,self.sigma,self.sigma+blend*(estimate-self.sigma))
            self.last_update=t
        return self.sigma.copy()

def run_filter(data, method):
    z=data['raw'];times=data['timestamp'];phases=data['phase']
    outputs=[];sigma_trace=[];speed_trace=[];bound_trace=[];clips=[]
    estimator=OnlineNoise(method=='adaptive_axis');fx=z[0].copy();rprev=z[0].copy();derivative=np.zeros(2);last=z[0].copy();last_time=times[0]
    state=np.zeros((2,2));state[:,0]=z[0]
    covariance=np.array([np.diag([PRIOR**2,.1**2]) for _ in range(2)])
    innov_r=np.ones(2)*PRIOR**2
    initialized=False
    for i,(raw,t,phase) in enumerate(zip(z,times,phases)):
        sigma=estimator.step(raw,t,phase) if method.startswith('MAD') or method in ('Kalman_MAD','adaptive_axis') else np.ones(2)*PRIOR
        dt=t-last_time
        speed=0.;bound=0.;clipped=False
        if phase==3:
            value=last.copy();initialized=False
        elif not initialized or phase==1 or dt>.1 or dt<0:
            fx=raw.copy();rprev=raw.copy();derivative[:]=0;value=raw.copy();initialized=True
            state[:,0]=raw;state[:,1]=0;covariance=np.array([np.diag([sigma[a]**2,.1**2]) for a in range(2)])
        elif dt==0 or (method!='current' and dt<1e-6):
            value=last.copy()
            # An ignored timestamp must not advance derivative history.
            outputs.append(value);sigma_trace.append(sigma.copy());speed_trace.append(0.);bound_trace.append(0.);clips.append(False)
            continue
        elif method.startswith('Kalman'):
            transition=np.array([[1,dt],[0,1.]])
            # Continuous white acceleration covariance, so dt changes do not
            # silently change process noise spectral density.
            q=.036*np.array([[dt**3/3,dt*dt/2],[dt*dt/2,dt]])
            if method=='Kalman_innovation':sigma=np.sqrt(innov_r)
            for axis in range(2):
                state[axis]=transition@state[axis]
                prediction=transition@covariance[axis]@transition.T+q
                e=raw[axis]-state[axis,0];s=prediction[0,0]+sigma[axis]**2
                gain=prediction[:,0]/s
                state[axis]+=gain*e
                a=np.eye(2)-np.outer(gain,[1.,0.])
                covariance[axis]=a@prediction@a.T+np.outer(gain,gain)*sigma[axis]**2
                if method=='Kalman_innovation':
                    target=np.clip(e*e-prediction[0,0],1e-10,.02**2)
                    innov_r[axis]+=(1-np.exp(-dt/.08))*(target-innov_r[axis])
            value=state[:,0].copy()
        else:
            a=alpha(12,dt);derivative+=a*((raw-rprev)/dt-derivative)
            if method in ('MAD_motion_gate','MAD_motion_energy'):
                variance=2*a*a/(2-a)*sigma*sigma/(dt*dt)
                # Factor 2 is the original aggressive confidence gate; factor 1
                # is a separate energy-debias ablation, not a certainty test.
                speed=np.sqrt(max(0,float(derivative@derivative-(2 if method=='MAD_motion_gate' else 1)*variance.sum())))
            else:speed=np.linalg.norm(derivative)
            fx+=alpha(3+80*speed,dt)*(raw-fx)
            error=fx-raw
            if method=='no_bound_ablation':value=fx.copy()
            elif method in ('MAD_motion_gate','MAD_motion_energy','MAD_axis_bounds','MAD_ellipse_bound','adaptive_axis'):
                bound=np.maximum(.5*sigma,3*sigma/(1+speed/(sigma*120+1e-6)))
                if method=='adaptive_axis':
                    # Preserve the original circular correction envelope first,
                    # then prevent the noisier axis granting extra correction to
                    # the cleaner axis. Equal sigmas reproduce the old circle.
                    radius=float(np.max(bound));length=np.linalg.norm(error)
                    error=error*min(1,radius/max(length,1e-30))
                    clipped=length>radius or bool((abs(error)>bound).any())
                    value=raw+np.clip(error,-bound,bound)
                elif method=='MAD_ellipse_bound':
                    ratio=np.linalg.norm(error/bound);clipped=ratio>1
                    value=raw+error*min(1,1/max(ratio,1e-30))
                else:
                    clipped=bool((abs(error)>bound).any());value=raw+np.clip(error,-bound,bound)
            else:
                scale=float(np.max(sigma));bound=max(.5*scale,3*scale/(1+speed/(scale*120+1e-6)))
                norm=np.linalg.norm(error);clipped=norm>bound
                value=raw+error*min(1,bound/max(norm,1e-30))
            rprev=raw.copy()
        last=value.copy();last_time=t
        outputs.append(value);sigma_trace.append(sigma.copy());speed_trace.append(float(speed));bound_trace.append(float(np.max(bound)));clips.append(clipped)
    return np.array(outputs),dict(sigma=np.array(sigma_trace),speed=np.array(speed_trace),bound=np.array(bound_trace),clipped=np.array(clips))

def covariance_metrics(e):
    covariance=np.cov(e.T);values,vectors=np.linalg.eigh(covariance)
    return dict(bias_norm=float(np.linalg.norm(e.mean(axis=0))),jitter_rms=float(np.sqrt(np.trace(covariance))),
        principal_sigma=np.sqrt(np.maximum(values,0)).tolist(),principal_angle_deg=float(np.degrees(np.arctan2(vectors[1,1],vectors[0,1]))),
        correlation_xy=float(covariance[0,1]/max(1e-30,np.sqrt(covariance[0,0]*covariance[1,1]))))

def metrics(data,out,info):
    t=data['t'];truth=data['truth'];e=out-truth;raw_e=data['raw']-truth
    valid=(t>.4)&np.isin(data['phase'],[1,2,3]);err=e[valid];raw_err=raw_e[valid]
    squared=(err*err).sum(axis=1);distance=np.sqrt(squared)
    result=dict(rmse=float(np.sqrt(squared.mean())),p95_error=float(np.quantile(distance,.95)),max_error=float(distance.max()),
        filter_output_coverage=1.,error_variance_reduction=1-float(np.var(err,axis=0).sum()/max(1e-30,np.var(raw_err,axis=0).sum())))
    result.update(covariance_metrics(err))
    for label,scale in [('single_screen_px',[3839,2159]),('all_screens_px',[7679,2159])]:
        result['rmse_'+label]=float(np.sqrt(np.mean(np.sum((err*np.array(scale))**2,axis=1))))
    for label,mask in [('high_noise',(t>2)&(t<2.8)),('short_burst',(t>4.2)&(t<4.4)),('after_burst',(t>4.4)&(t<4.7))]:
        mask&=valid
        if mask.any():result[label+'_rmse']=float(np.sqrt(np.mean(np.sum(e[mask]**2,axis=1))))
    # Signed delay of the best causal truth alignment. This is reported alongside,
    # never in place of the unshifted RMSE. A positive lag means output is behind.
    if data['trajectory']!='stationary':
        lags=np.arange(-.04,.081,.001);scores=[]
        for lag in lags:
            shifted,_=truth_at(t-lag,data['trajectory'])
            mask=valid&(t>.6)&(t<5.7)
            if data['trajectory']=='start_stop':mask&=((t>.6)&(t<2.5))|((t>3.6)&(t<5.5))
            scores.append(np.mean(np.sum((out[mask]-shifted[mask])**2,axis=1)))
        best=int(np.argmin(scores));result['best_truth_lag_ms']=float(lags[best]*1000);result['aligned_shape_rmse']=float(np.sqrt(scores[best]))
    if data['trajectory'] in ('slow_line','fast_line'):
        speed=.025 if data['trajectory']=='slow_line' else .12
        result['steady_lag_ms']=float(-err[:,0].mean()/speed*1000)
        result['normal_rmse']=float(np.sqrt(np.mean(err[:,1]**2)))
    if data['trajectory']=='small_circle':
        reference=(truth[valid,0]-.5)+1j*(truth[valid,1]-.5)
        actual=(out[valid,0]-.5)+1j*(out[valid,1]-.5)
        transfer=np.mean(actual/reference)
        result['circle_radius_ratio']=float(abs(transfer));result['circle_phase_lag_ms']=float(-np.angle(transfer)/(4*np.pi)*1000)
        start=np.argmin(abs(t-2));end=np.argmin(abs(t-2.5))
        result['circle_closure_error']=float(np.linalg.norm((out[end]-out[start])-(truth[end]-truth[start])))
    if data['trajectory']=='sharp_corner':
        corner=np.array([.56,.4]);mask=(t>2.7)&(t<3.3)
        result['corner_vertex_miss']=float(np.min(np.linalg.norm(out[mask]-corner,axis=1)))
        result['corner_peak_error']=float(np.max(np.linalg.norm(e[mask],axis=1)))
        result['corner_overshoot_x']=float(max(0,np.max(out[(t>=3)&(t<3.2),0]-.56)))
        # Distance from either finite ray of the intended L path, independent of lag.
        q=out[mask];horizontal=np.linalg.norm(q-np.column_stack([np.minimum(q[:,0],.56),np.ones(len(q))*.4]),axis=1)
        vertical=np.linalg.norm(q-np.column_stack([np.ones(len(q))*.56,np.maximum(q[:,1],.4)]),axis=1)
        result['corner_path_deviation_max']=float(np.max(np.minimum(horizontal,vertical)))
    if data['trajectory']=='start_stop':
        starts=np.flatnonzero(data['phase']==1);ends=np.flatnonzero(data['phase']==3)
        result['start_error_max']=float(np.max(np.linalg.norm(e[starts],axis=1)))
        result['endpoint_error_max']=float(np.max(np.linalg.norm(e[ends],axis=1)))
    if info is not None:
        high=(t>2)&(t<2.8)
        result['estimated_sigma_high']=np.median(info['sigma'][high],axis=0).tolist()
        result['median_speed_high']=float(np.median(info['speed'][high]));result['clipped_fraction_high']=float(np.mean(info['clipped'][high]))
    return result

def aggregate(rows):
    result={}
    for row in rows:
        key=f"{row['trajectory']}/{row['model']}/{row['method']}"
        result.setdefault(key,[]).append(row['metrics'])
    summary={}
    for key,items in result.items():
        summary[key]={}
        for field in items[0]:
            vals=[item[field] for item in items if field in item]
            if all(isinstance(v,(int,float)) for v in vals):
                summary[key][field]={'mean':float(np.mean(vals)),'min':float(np.min(vals)),'max':float(np.max(vals))}
    return summary

def plot_examples(traces):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    selected=['current','adaptive_axis']
    colors={'current':'#d55e00','adaptive_axis':'#0072b2'}
    fig,axes=plt.subplots(2,3,figsize=(17,9))
    cases=[('stationary','white_variable'),('stationary','colored_bursts'),('stationary','alias_260Hz'),
           ('slow_line','white_variable'),('small_circle','white_variable'),('sharp_corner','white_variable')]
    for ax,key in zip(axes.flat,cases):
        data,series=traces[key];t=data['t'];truth=data['truth'];raw=data['raw']
        mask=(t>1.9)&(t<2.25) if key[0]=='stationary' else (t>2)&(t<2.5) if key[0]=='small_circle' else (t>2.7)&(t<3.3) if key[0]=='sharp_corner' else (t>2)&(t<2.4)
        if key[0]=='stationary':
            ax.plot(t[mask],raw[mask,1]-truth[mask,1],color='.8',label='raw')
            for method in selected:ax.plot(t[mask],series[method][0][mask,1]-truth[mask,1],label=method,color=colors[method],linewidth=1.2)
            ax.set_xlabel('source time (s)');ax.set_ylabel('normalized Y error')
        else:
            ax.plot(raw[mask,0],raw[mask,1],color='.82',label='raw',linewidth=.8)
            ax.plot(truth[mask,0],truth[mask,1],color='black',label='truth',linewidth=2)
            for method in selected:ax.plot(series[method][0][mask,0],series[method][0][mask,1],label=method,color=colors[method],linewidth=1.2)
            ax.set_xlabel('normalized X');ax.set_ylabel('normalized Y');ax.set_aspect('equal',adjustable='datalim')
        ax.set_title('/'.join(key));ax.legend(fontsize=7)
    fig.suptitle('Synthetic model comparison; 250 Hz, calibrated prior 0.000115678; NOT device measurements',fontsize=13)
    fig.tight_layout()
    for extension in ['png','svg']:fig.savefig(OUT/f'trajectories.{extension}',dpi=150)
    plt.close(fig)
    fig,axes=plt.subplots(1,3,figsize=(15,4.5))
    data,series=traces[('stationary','white_variable')];t=data['t']
    axes[0].plot(t,np.where(t<1.5,.00012,np.where(t<3,.002,np.where(t<4.5,.0006,.0002))),label='injected sigma',color='black')
    axes[0].plot(t,series['adaptive_axis'][1]['sigma'][:,0],label='adaptive X');axes[0].axhline(PRIOR,color='#d55e00',label='current fixed prior')
    axes[0].set_title('Noise adaptation has finite reaction time');axes[0].set_xlabel('seconds');axes[0].legend(fontsize=8)
    for model in ['narrowband_60Hz','alias_260Hz']:
        data,_=traces[('stationary',model)];t=data['t'];mask=(t>=1.5)&(t<4.5)
        e=(data['raw']-data['truth'])[mask,0];spectrum=abs(np.fft.rfft((e-e.mean())*np.hanning(len(e))))**2
        frequency=np.fft.rfftfreq(len(e),DT)
        axes[1].plot(frequency,spectrum,label=model)
    axes[1].set_xlim(0,125);axes[1].set_yscale('log');axes[1].set_title('260 Hz samples alias to 10 Hz at 250 Hz');axes[1].set_xlabel('observed frequency (Hz)');axes[1].legend(fontsize=8)
    data,series=traces[('stationary','colored_bursts')];mask=(data['t']>1.5)&(data['t']<2.8)
    e=data['raw'][mask]-data['truth'][mask]
    for scale,label in [(np.array([3839,2159]),'single 3840 x 2160'),(np.array([7679,2159]),'all 7680 x 2160')]:
        q=e*scale;axes[2].scatter(q[:,0],q[:,1],s=5,alpha=.35,label=label)
    axes[2].set_title('Same residual, different mapping Jacobian');axes[2].set_xlabel('target X error (px)');axes[2].set_ylabel('target Y error (px)');axes[2].legend(fontsize=8)
    fig.tight_layout()
    for extension in ['png','svg']:fig.savefig(OUT/f'noise_and_mapping.{extension}',dpi=150)
    plt.close(fig)

def main():
    global OUT
    parser=argparse.ArgumentParser();parser.add_argument('--seeds',type=int,default=3)
    parser.add_argument('--seed-start',type=int,default=1701)
    parser.add_argument('--output-dir',type=Path,default=OUT);args=parser.parse_args()
    OUT=args.output_dir.resolve();OUT.mkdir(parents=True,exist_ok=True)
    rows=[];traces={}
    for trajectory in TRAJECTORIES:
        for model in MODELS:
            for seed in range(args.seeds):
                data=generate(trajectory,model,args.seed_start+seed)
                rows.append(dict(trajectory=trajectory,model=model,seed=args.seed_start+seed,method='raw',metrics=metrics(data,data['raw'],None)))
                series={}
                for method in METHODS:
                    value,info=run_filter(data,method)
                    rows.append(dict(trajectory=trajectory,model=model,seed=args.seed_start+seed,method=method,metrics=metrics(data,value,info)))
                    if seed==0:series[method]=(value,info)
                if seed==0:traces[(trajectory,model)]=(data,series)
            print(f'completed {trajectory}/{model}',flush=True)
    metadata=dict(synthetic=True,measured_device_data=False,sample_rate_hz=FS,default_noise_prior=PRIOR,
        seeds=args.seeds,seed_start=args.seed_start,coordinate_units='normalized tablet area',source_time_used=True,
        interference_models='Hypotheses, not attribution: 60 Hz sinusoid; 260 Hz coordinate perturbation aliases to 10 Hz if present before sampling.',
        scope='Filtering only; guard_not_modeled=true. filter_output_coverage measures only whether the numeric filter emits every point; safety rejection/cancellation is not evaluated.',
        guard_not_modeled=True,
        candidates='adaptive_axis is the final production numeric filtering logic, verified against native Windows and Swift output; other candidate/ablation methods are experimental prototypes. MAD assumes locally white noise; Kalman assumes constant velocity plus white acceleration.')
    result=dict(metadata=metadata,aggregate=aggregate(rows),runs=rows)
    (OUT/('metrics.json' if args.seed_start==1701 else 'metrics-heldout.json')).write_text(json.dumps(result,indent=2),encoding='utf-8')
    if args.seed_start==1701:plot_examples(traces)
    print(f'{len(rows)} rows written to {OUT}',flush=True)

if __name__=='__main__':main()
