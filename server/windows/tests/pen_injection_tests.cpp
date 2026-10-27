#include "reflection/windows.hpp"
#include <iostream>
#include <mutex>
#include <thread>

using namespace reflection;

namespace {
struct Counts { unsigned down=0, up=0, canceled=0, unreadable=0; };
std::mutex count_mutex;
Counts counts;
HWND window=nullptr;
std::atomic_bool closed{false}, finished{false};
int result=1;

LRESULT CALLBACK procedure(HWND hwnd, UINT message, WPARAM wp, LPARAM lp) {
    if(message==WM_POINTERDOWN || message==WM_POINTERUP || message==WM_POINTERUPDATE) {
        POINTER_PEN_INFO pen{};
        const bool readable=GetPointerPenInfo(GET_POINTERID_WPARAM(wp),&pen);
        std::lock_guard<std::mutex> lock(count_mutex);
        if(message==WM_POINTERDOWN) ++counts.down;
        if(message==WM_POINTERUP) ++counts.up;
        if(!readable) ++counts.unreadable;
        if(readable && (pen.pointerInfo.pointerFlags&POINTER_FLAG_CANCELED)) ++counts.canceled;
        return 0;
    }
    if(message==WM_CLOSE) { closed=true; return 0; }
    return DefWindowProcW(hwnd,message,wp,lp);
}

Counts snapshot() {
    std::lock_guard<std::mutex> lock(count_mutex);
    return counts;
}

void check_burst(bool eraser, bool leave_range, bool moves) {
    { std::lock_guard<std::mutex> lock(count_mutex); counts={}; }
    auto backend=make_pen_backend(false,false);
    POINT origin{100,100};
    if(!ClientToScreen(window,&origin)) throw std::runtime_error("cannot locate test canvas");
    MappedSample sample;
    sample.source.eraser=eraser;
    sample.source.tilt=true;
    sample.pressure=.5;
    sample.x=origin.x; sample.y=origin.y;
    uint64_t sequence=0;
    auto send=[&](Phase phase) {
        if(closed) throw std::runtime_error("test window closed");
        const POINT point{static_cast<LONG>(sample.x),static_cast<LONG>(sample.y)};
        if(GetAncestor(WindowFromPoint(point),GA_ROOT)!=window)
            throw std::runtime_error("test canvas is covered; refusing to inject into another window");
        sample.source.phase=phase;
        sample.source.sequence=++sequence;
        sample.source.timestamp_ns=sequence*1000000;
        backend->submit(sample);
    };
    constexpr unsigned strokes=150;
    send(Phase::hover);
    // No producer sleeps: this reproduces distinct tablet samples arriving in a
    // TCP burst. Both taps and moving strokes must retain every DOWN and UP.
    for(unsigned i=0;i<strokes;++i) {
        sample.x=origin.x+(i%5)*70;
        sample.y=origin.y+((i/5)%5)*35;
        send(Phase::down);
        if(moves) for(unsigned j=0;j<4;++j) { sample.x+=3; send(Phase::move); }
        send(Phase::up);
        send(leave_range?Phase::exit:Phase::hover);
    }
    backend->release();
    backend.reset();
    // Windows dispatches injected messages asynchronously on the window thread.
    const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(2);
    Counts received;
    do {
        Sleep(10);
        received=snapshot();
    } while((received.down<strokes || received.up<strokes) && std::chrono::steady_clock::now()<deadline);
    std::cout<<"{\"eraser\":"<<eraser<<",\"leaveRange\":"<<leave_range<<",\"moves\":"<<moves
        <<",\"strokes\":"<<strokes<<",\"down\":"<<received.down<<",\"up\":"<<received.up
        <<",\"canceled\":"<<received.canceled<<",\"unreadable\":"<<received.unreadable<<"}\n"<<std::flush;
    if(received.down!=strokes || received.up!=strokes || received.canceled || received.unreadable)
        throw std::runtime_error("Windows did not receive every stroke boundary");
    Sleep(50);
}
}

int main(int argc,char** argv) {
    if(argc!=2 || std::string(argv[1])!="--inject-into-test-window") {
        std::cerr<<"Opt-in desktop test: pass --inject-into-test-window. Pause other pen input first.\n";
        return 2;
    }
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    WNDCLASSW type{};
    type.lpfnWndProc=procedure;
    type.hInstance=GetModuleHandleW(nullptr);
    type.lpszClassName=L"ReflectionPenInjectionTest";
    type.hbrBackground=reinterpret_cast<HBRUSH>(COLOR_WINDOW+1);
    if(!RegisterClassW(&type)) return 1;
    window=CreateWindowExW(WS_EX_TOPMOST,type.lpszClassName,
        L"Reflection: rapid pen regression test (closes automatically)",WS_OVERLAPPEDWINDOW,
        200,100,700,500,nullptr,nullptr,type.hInstance,nullptr);
    if(!window) return 1;
    ShowWindow(window,SW_SHOWNORMAL);
    UpdateWindow(window);
    std::thread worker([] {
        try {
            Sleep(200);
            for(bool eraser:{false,true}) for(bool leave:{false,true}) for(bool moves:{false,true})
                check_burst(eraser,leave,moves);
            result=0;
        } catch(const std::exception& error) { std::cerr<<error.what()<<'\n'; }
        finished=true;
        PostMessageW(window,WM_NULL,0,0);
    });
    MSG message{};
    while(!finished && GetMessageW(&message,nullptr,0,0)>0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    worker.join();
    DestroyWindow(window);
    return result;
}
