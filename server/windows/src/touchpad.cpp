#include "reflection/windows.hpp"
#include "reflection/touchpad.hpp"
#include <cmath>
#include <initializer_list>

namespace reflection {
namespace {
void send(std::vector<INPUT>& inputs) {
    if (inputs.empty()) return;
    if (SendInput(static_cast<UINT>(inputs.size()), inputs.data(), sizeof(INPUT)) != inputs.size()) {
        INPUT release{}; release.type = INPUT_MOUSE;
        for (const auto& input : inputs) {
            if (input.mi.dwFlags & MOUSEEVENTF_LEFTDOWN) release.mi.dwFlags |= MOUSEEVENTF_LEFTUP;
            if (input.mi.dwFlags & MOUSEEVENTF_RIGHTDOWN) release.mi.dwFlags |= MOUSEEVENTF_RIGHTUP;
        }
        if (release.mi.dwFlags) SendInput(1, &release, sizeof(INPUT));
        throw std::runtime_error("touchpad SendInput failed; check target application integrity level");
    }
}
INPUT mouse(DWORD flags, LONG x = 0, LONG y = 0, DWORD data = 0) {
    INPUT input{}; input.type = INPUT_MOUSE;
    input.mi.dx = x; input.mi.dy = y; input.mi.dwFlags = flags; input.mi.mouseData = data;
    return input;
}
void shortcut(std::initializer_list<WORD> modifiers, WORD key) {
    std::vector<INPUT> inputs;
    // Preserve physical modifiers and release every synthetic key in the same SendInput batch.
    std::vector<WORD> pressed;
    auto add = [&](WORD value, bool up) {
        INPUT input{}; input.type = INPUT_KEYBOARD; input.ki.wVk = value;
        input.ki.dwFlags = up ? KEYEVENTF_KEYUP : 0;
        if (value == VK_LEFT || value == VK_RIGHT || value == VK_UP || value == VK_DOWN ||
            value == VK_LWIN) input.ki.dwFlags |= KEYEVENTF_EXTENDEDKEY;
        inputs.push_back(input);
    };
    for (WORD modifier : modifiers) if (!(GetAsyncKeyState(modifier) & 0x8000)) {
        pressed.push_back(modifier); add(modifier, false);
    }
    add(key, false); add(key, true);
    for (auto it = pressed.rbegin(); it != pressed.rend(); ++it) add(*it, true);
    const UINT inserted = SendInput(static_cast<UINT>(inputs.size()), inputs.data(), sizeof(INPUT));
    if (inserted != inputs.size()) {
        // Best-effort release after a partial insertion, without releasing the user's own keys.
        inputs.clear();
        add(key, true);
        for (auto it = pressed.rbegin(); it != pressed.rend(); ++it) add(*it, true);
        SendInput(static_cast<UINT>(inputs.size()), inputs.data(), sizeof(INPUT));
        throw std::runtime_error("touchpad keyboard shortcut injection failed");
    }
}
LONG take(double& remainder, double delta) {
    remainder += delta;
    const auto whole = static_cast<LONG>(std::trunc(remainder));
    remainder -= whole; return whole;
}
}

void TouchpadBackend::submit(const TouchpadEvent& e) {
    if (dry_run_) return;
    std::vector<INPUT> inputs;
    switch (e.kind) {
    case TouchpadKind::move: {
        const auto x = take(motion_x_, e.x * 1.5), y = take(motion_y_, e.y * 1.5);
        if (x || y) inputs.push_back(mouse(MOUSEEVENTF_MOVE, x, y));
        break;
    }
    case TouchpadKind::left_tap: case TouchpadKind::right_tap: {
        const bool right = e.kind == TouchpadKind::right_tap;
        inputs.push_back(mouse(right ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_LEFTDOWN));
        inputs.push_back(mouse(right ? MOUSEEVENTF_RIGHTUP : MOUSEEVENTF_LEFTUP)); break;
    }
    case TouchpadKind::scroll: {
        const auto x = take(wheel_x_, -e.x * 3), y = take(wheel_y_, e.y * 3);
        if (x) inputs.push_back(mouse(MOUSEEVENTF_HWHEEL, 0, 0, static_cast<DWORD>(x)));
        if (y) inputs.push_back(mouse(MOUSEEVENTF_WHEEL, 0, 0, static_cast<DWORD>(y)));
        break;
    }
    case TouchpadKind::zoom:
        zoom_ += e.x;
        while (std::abs(zoom_) >= 0.12) {
            shortcut({VK_CONTROL}, zoom_ > 0 ? VK_OEM_PLUS : VK_OEM_MINUS);
            zoom_ += zoom_ > 0 ? -0.12 : 0.12;
        }
        break;
    case TouchpadKind::swipe:
        if (e.x != 0) {
            if (e.fingers == 4) shortcut({VK_LWIN, VK_CONTROL}, e.x > 0 ? VK_LEFT : VK_RIGHT);
            else if (e.x > 0) shortcut({VK_MENU, VK_SHIFT}, VK_TAB);
            else shortcut({VK_MENU}, VK_TAB);
        } else if (e.y < 0) shortcut({VK_LWIN}, VK_TAB);
        else shortcut({VK_LWIN}, 'D');
        break;
    case TouchpadKind::scroll_begin: case TouchpadKind::scroll_end: case TouchpadKind::cancel:
        release(); break;
    }
    send(inputs);
}

void TouchpadBackend::release() {
    motion_x_ = motion_y_ = wheel_x_ = wheel_y_ = zoom_ = 0;
}

} // namespace reflection
