#include "reflection/windows.hpp"
#include "reflection/driver.hpp"
#include <winioctl.h>
#include "../driver/tablet_ioctl.h"
#include "../../shared/tablet_report.h"
#include <setupapi.h>
#include <newdev.h>
#include <shellapi.h>
#include <cwchar>

namespace reflection {
namespace {
struct Handle {
    HANDLE value = INVALID_HANDLE_VALUE;
    ~Handle() { if (value != INVALID_HANDLE_VALUE && value) CloseHandle(value); }
};
struct Devices {
    HDEVINFO value;
    ~Devices() { if (value != INVALID_HANDLE_VALUE) SetupDiDestroyDeviceInfoList(value); }
};
std::runtime_error failure(const std::string& message) {
    return std::runtime_error(message + " (Windows error " + std::to_string(GetLastError()) + ")");
}
std::filesystem::path executable() {
    std::wstring path(32768, L'\0');
    const auto size = GetModuleFileNameW(nullptr, path.data(), static_cast<DWORD>(path.size()));
    if (!size || size == path.size()) throw failure("cannot locate host executable");
    path.resize(size);
    return path;
}
std::filesystem::path package_inf() {
    return executable().parent_path() / "driver" / "ReflectionTablet.inf";
}
bool owned_device(HDEVINFO list, SP_DEVINFO_DATA& device) {
    wchar_t ids[4096]{};
    DWORD type = 0;
    if (!SetupDiGetDeviceRegistryPropertyW(list, &device, SPDRP_HARDWAREID, &type,
        reinterpret_cast<PBYTE>(ids), sizeof(ids) - 2 * sizeof(wchar_t), nullptr) || type != REG_MULTI_SZ)
        return false;
    for (const auto* id = ids; *id; id += wcslen(id) + 1)
        if (_wcsicmp(id, REFLECTION_HARDWARE_ID) == 0) return true;
    return false;
}
bool installed_device() {
    Devices devices{SetupDiGetClassDevsW(nullptr, nullptr, nullptr, DIGCF_ALLCLASSES)};
    if (devices.value == INVALID_HANDLE_VALUE) throw failure("cannot enumerate drivers");
    SP_DEVINFO_DATA device{sizeof(device)};
    for (DWORD i = 0; SetupDiEnumDeviceInfo(devices.value, i, &device); ++i)
        if (owned_device(devices.value, device)) return true;
    return false;
}
HANDLE open_driver() {
    return CreateFileW(REFLECTION_DEVICE_PATH, GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, 0, nullptr);
}
void check_abi(HANDLE handle) {
    DWORD version = 0, returned = 0;
    if (!DeviceIoControl(handle, IOCTL_REFLECTION_VERSION, nullptr, 0, &version, sizeof(version), &returned, nullptr))
        throw failure("cannot query Reflection tablet driver");
    if (returned != sizeof(version) || version != REFLECTION_TABLET_ABI)
        throw std::runtime_error("incompatible tablet driver; install the driver bundled with this host");
}
void elevate(bool install) {
    const auto program = executable();
    const auto parameters = install ? L"driver-system install" : L"driver-system uninstall";
    SHELLEXECUTEINFOW launch{sizeof(launch)};
    launch.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_FLAG_NO_UI;
    launch.lpVerb = L"runas"; launch.lpFile = program.c_str(); launch.lpParameters = parameters;
    launch.nShow = SW_HIDE;
    if (!ShellExecuteExW(&launch)) throw failure("driver authorization was cancelled or could not start");
    Handle child{launch.hProcess};
    WaitForSingleObject(child.value, INFINITE);
    DWORD result = 1;
    if (!GetExitCodeProcess(child.value, &result) || result)
        throw std::runtime_error("driver operation failed (exit " + std::to_string(result) +
            "). A signed driver package and administrator approval are required. "
            "Check Device Manager and Windows/INF/setupapi.dev.log; input mode was not changed to driver.");
}
class DriverBackend final : public InputBackend {
    Handle device_;
    TabletReport last_{REFLECTION_TABLET_REPORT_ID};
    bool in_range_ = false;
    void send(const TabletReport& report) {
        DWORD returned = 0;
        if (!DeviceIoControl(device_.value, IOCTL_REFLECTION_REPORT, const_cast<uint8_t*>(report.data()),
            static_cast<DWORD>(report.size()), nullptr, 0, &returned, nullptr))
            throw failure("virtual tablet report failed");
    }
public:
    DriverBackend() {
        device_.value = open_driver();
        if (device_.value == INVALID_HANDLE_VALUE) throw failure("Reflection tablet driver is unavailable");
        check_abi(device_.value);
        DWORD returned = 0;
        if (!DeviceIoControl(device_.value, IOCTL_REFLECTION_CLAIM, nullptr, 0, nullptr, 0, &returned, nullptr))
            throw failure("another host owns the virtual tablet");
    }
    ~DriverBackend() override { release(); }
    void submit(const MappedSample& sample) override {
        if (sample.source.phase == Phase::cancel || sample.source.phase == Phase::exit) { release(); return; }
        if (in_range_ && bool(last_[1] & REFLECTION_TABLET_INVERT) != sample.source.eraser) release();
        auto report = tablet_report(sample, virtual_desktop());
        // A lift clears switches at the last contact position before further motion.
        if (sample.source.phase == Phase::up && in_range_) std::copy(last_.begin() + 2, last_.begin() + 6, report.begin() + 2);
        send(report); last_ = report; in_range_ = true;
    }
    void release() noexcept override {
        if (!in_range_) return;
        try { send(tablet_release(last_, true)); send(tablet_release(last_, false)); } catch (...) {}
        in_range_ = false;
    }
};
}

DriverStatus driver_status(bool inspect_package) {
    DriverStatus result;
    if (inspect_package) result.package = inspect_driver_package(package_inf().parent_path());
    try {
        result.installed = installed_device();
        if (!result.installed) {
            result.detail = "Driver is not installed.";
            return result;
        }
        Handle device{open_driver()};
        if (device.value == INVALID_HANDLE_VALUE) throw failure("virtual tablet device is not available");
        check_abi(device.value);
        result.available = true;
        result.detail = "Virtual HID device ready; application and display mapping compatibility requires verification.";
    } catch (const std::exception& e) { result.detail = e.what(); }
    return result;
}
std::string DriverStatus::json() const {
    return "{\"installed\":" + std::string(installed ? "true" : "false") +
        ",\"available\":" + (available ? "true" : "false") +
        ",\"packageAvailable\":" + (package.available ? "true" : "false") +
        ",\"package\":" + package.json() +
        ",\"detail\":" + json_escape(detail) + "}";
}
void install_driver() {
    const auto status = driver_status();
    if (status.available) return;
    if (!status.package.available) throw std::runtime_error(status.package.detail);
    elevate(true);
    for (unsigned i = 0; i != 50; ++i) {
        if (driver_status(false).available) return;
        Sleep(100);
    }
    throw std::runtime_error("driver installed but not ready; a reboot or driver signing approval may be required. API mode remains selected.");
}
void uninstall_driver() {
    if (driver_status(false).installed) elevate(false);
    if (driver_status(false).installed) throw std::runtime_error("driver removal is pending; restart Windows to finish removal");
}
std::unique_ptr<InputBackend> make_driver_backend() { return std::make_unique<DriverBackend>(); }

void driver_system_action(bool install) {
    if (install) {
        const auto inf = package_inf();
        const auto package = inspect_driver_package(inf.parent_path());
        if (!package.available) throw std::runtime_error(package.detail);
        GUID device_class{};
        wchar_t class_name[256]{};
        if (!SetupDiGetINFClassW(inf.c_str(), &device_class, class_name, 256, nullptr))
            throw failure("cannot read driver INF");
        Devices devices{SetupDiCreateDeviceInfoList(&device_class, nullptr)};
        if (devices.value == INVALID_HANDLE_VALUE) throw failure("cannot create device list");
        SP_DEVINFO_DATA device{sizeof(device)};
        bool created = false;
        if (!installed_device()) {
            if (!SetupDiCreateDeviceInfoW(devices.value, class_name, &device_class, L"Reflection Virtual Tablet",
                nullptr, DICD_GENERATE_ID, &device)) throw failure("cannot create tablet device");
            const wchar_t hardware[] = REFLECTION_HARDWARE_ID L"\0";
            if (!SetupDiSetDeviceRegistryPropertyW(devices.value, &device, SPDRP_HARDWAREID,
                reinterpret_cast<const BYTE*>(hardware), sizeof(hardware)) ||
                !SetupDiCallClassInstaller(DIF_REGISTERDEVICE, devices.value, &device))
                throw failure("cannot register tablet device");
            created = true;
        }
        BOOL reboot = FALSE;
        if (!UpdateDriverForPlugAndPlayDevicesW(nullptr, REFLECTION_HARDWARE_ID, inf.c_str(), INSTALLFLAG_FORCE, &reboot)) {
            const auto error = failure("cannot install signed tablet driver");
            if (created) SetupDiCallClassInstaller(DIF_REMOVE, devices.value, &device);
            throw error;
        }
        if (reboot) throw std::runtime_error("driver installation requires restarting Windows");
    } else {
        Devices devices{SetupDiGetClassDevsW(nullptr, nullptr, nullptr, DIGCF_ALLCLASSES)};
        if (devices.value == INVALID_HANDLE_VALUE) throw failure("cannot enumerate tablet devices");
        SP_DEVINFO_DATA device{sizeof(device)};
        std::vector<std::wstring> inf_names;
        bool reboot_required = false;
        for (DWORD i = 0; SetupDiEnumDeviceInfo(devices.value, i, &device); ++i) {
            if (!owned_device(devices.value, device)) continue;
            auto key = SetupDiOpenDevRegKey(devices.value, &device, DICS_FLAG_GLOBAL, 0, DIREG_DRV, KEY_QUERY_VALUE);
            if (key != INVALID_HANDLE_VALUE) {
                wchar_t name[MAX_PATH]{};
                DWORD size = sizeof(name), type = 0;
                if (RegQueryValueExW(key, L"InfPath", nullptr, &type, reinterpret_cast<BYTE*>(name), &size) == ERROR_SUCCESS &&
                    type == REG_SZ && size < sizeof(name) && std::wstring(name).find_first_of(L"\\/") == std::wstring::npos)
                    inf_names.emplace_back(name);
                RegCloseKey(key);
            }
            BOOL reboot = FALSE;
            if (!DiUninstallDevice(nullptr, devices.value, &device, 0, &reboot)) throw failure("cannot remove tablet device");
            reboot_required = reboot_required || reboot;
        }
        for (const auto& inf : inf_names)
            if (!SetupUninstallOEMInfW(inf.c_str(), 0, nullptr) && GetLastError() != ERROR_FILE_NOT_FOUND)
                throw failure("device removed but driver store cleanup failed");
        if (reboot_required) throw std::runtime_error("driver removal requires restarting Windows; API mode is selected");
    }
}
}
