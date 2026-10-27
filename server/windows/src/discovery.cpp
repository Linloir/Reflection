#include "reflection/windows.hpp"
#include "reflection/discovery.hpp"
#include "reflection/protocol.hpp"
#include <bcrypt.h>
#include <ws2bth.h>
#include <thread>
#include <mutex>
#include <map>
#include <set>
#include <fstream>
#include <sstream>
#include <iomanip>
#include <cstring>
#include <chrono>

namespace reflection {
namespace {
struct Socket {
    SOCKET value = INVALID_SOCKET;
    explicit Socket(SOCKET socket) : value(socket) {}
    ~Socket() { if (value != INVALID_SOCKET) closesocket(value); }
    Socket(const Socket&) = delete;
    Socket& operator=(const Socket&) = delete;
};
class PairingAdvertisement {
public:
    explicit PairingAdvertisement(SOCKET socket) {
        int size = sizeof(address_);
        if (getsockname(socket, reinterpret_cast<sockaddr*>(&address_), &size))
            throw std::runtime_error("Bluetooth pairing address unavailable");
        addresses_.LocalAddr.lpSockaddr = reinterpret_cast<sockaddr*>(&address_);
        addresses_.LocalAddr.iSockaddrLength = sizeof(address_);
        addresses_.iSocketType = SOCK_STREAM;
        addresses_.iProtocol = BTHPROTO_RFCOMM;
        query_.dwSize = sizeof(query_);
        query_.lpszServiceInstanceName = name_;
        query_.lpServiceClassId = &service_;
        query_.dwNameSpace = NS_BTH;
        query_.dwNumberOfCsAddrs = 1;
        query_.lpcsaBuffer = &addresses_;
        if (WSASetServiceW(&query_, RNRSERVICE_REGISTER, 0))
            throw std::runtime_error("Bluetooth pairing advertisement unavailable");
    }
    ~PairingAdvertisement() { WSASetServiceW(&query_, RNRSERVICE_DELETE, 0); }
private:
    SOCKADDR_BTH address_{};
    CSADDR_INFO addresses_{};
    WSAQUERYSETW query_{};
    GUID service_{0x7b011e5a, 0x8b31, 0x4c40, {0x8d, 0x7a, 0x3e, 0x6c, 0x6d, 0xcb, 0xb7, 0x59}};
    wchar_t name_[19] = L"Reflection Pairing";
};
void nonblocking(SOCKET socket) {
    u_long enabled = 1;
    if (ioctlsocket(socket, FIONBIO, &enabled)) throw std::runtime_error("Bluetooth pairing socket unavailable");
}
void pairing_transfer(SOCKET socket, uint8_t* bytes, size_t size, bool write, uint64_t deadline,
                      const std::atomic_bool& alive) {
    size_t offset = 0;
    while (offset < size) {
        if (!alive || GetTickCount64() >= deadline) throw std::runtime_error("Bluetooth pairing timed out");
        fd_set ready; FD_ZERO(&ready); FD_SET(socket, &ready);
        timeval timeout{0, 100000};
        const int available = select(0, write ? nullptr : &ready, write ? &ready : nullptr, nullptr, &timeout);
        if (available == SOCKET_ERROR) throw std::runtime_error("Bluetooth pairing socket closed");
        if (!available) continue;
        const int count = write ? send(socket, reinterpret_cast<char*>(bytes + offset), static_cast<int>(size - offset), 0)
                                : recv(socket, reinterpret_cast<char*>(bytes + offset), static_cast<int>(size - offset), 0);
        if (count == SOCKET_ERROR && WSAGetLastError() == WSAEWOULDBLOCK) continue;
        if (count <= 0) throw std::runtime_error("Bluetooth pairing peer disconnected");
        offset += static_cast<size_t>(count);
    }
}
std::string bluetooth_address(BTH_ADDR address) {
    std::ostringstream result; result << "Bluetooth " << std::hex << std::uppercase << std::setfill('0');
    for (int shift = 40; shift >= 0; shift -= 8) {
        if (shift != 40) result << ':';
        result << std::setw(2) << ((address >> shift) & 0xff);
    }
    return result.str();
}
struct Algorithm {
    BCRYPT_ALG_HANDLE value = nullptr;
    explicit Algorithm(LPCWSTR name) {
        if (BCryptOpenAlgorithmProvider(&value, name, nullptr, 0) < 0)
            throw std::runtime_error("pairing cryptography unavailable");
    }
    ~Algorithm() { if (value) BCryptCloseAlgorithmProvider(value, 0); }
};
std::vector<uint8_t> sha256(const std::vector<uint8_t>& data) {
    Algorithm algorithm(BCRYPT_SHA256_ALGORITHM);
    std::vector<uint8_t> result(32);
    if (BCryptHash(algorithm.value, nullptr, 0, const_cast<PUCHAR>(data.data()),
                   static_cast<ULONG>(data.size()), result.data(), 32) < 0)
        throw std::runtime_error("pairing fingerprint failed");
    return result;
}
std::vector<uint8_t> encrypt(const DeviceAnnouncement& device, const std::string& plaintext) {
    Algorithm algorithm(BCRYPT_RSA_ALGORITHM);
    BCRYPT_RSAKEY_BLOB header{BCRYPT_RSAPUBLIC_MAGIC, 2048, 3, 256, 0, 0};
    std::vector<uint8_t> blob(sizeof(header));
    std::memcpy(blob.data(), &header, sizeof(header));
    blob.insert(blob.end(), device.exponent.begin(), device.exponent.end());
    blob.insert(blob.end(), device.modulus.begin(), device.modulus.end());
    BCRYPT_KEY_HANDLE key = nullptr;
    if (BCryptImportKeyPair(algorithm.value, nullptr, BCRYPT_RSAPUBLIC_BLOB, &key,
                            blob.data(), static_cast<ULONG>(blob.size()), 0) < 0)
        throw std::runtime_error("invalid tablet pairing public key");
    struct Cleanup { BCRYPT_KEY_HANDLE key; ~Cleanup() { BCryptDestroyKey(key); } } cleanup{key};
    BCRYPT_OAEP_PADDING_INFO padding{BCRYPT_SHA256_ALGORITHM, nullptr, 0};
    std::vector<uint8_t> encrypted(256);
    ULONG size = 0;
    if (BCryptEncrypt(key, reinterpret_cast<PUCHAR>(const_cast<char*>(plaintext.data())),
                       static_cast<ULONG>(plaintext.size()), &padding, nullptr, 0,
                       encrypted.data(), static_cast<ULONG>(encrypted.size()), &size, BCRYPT_PAD_OAEP) < 0 ||
        size != 256) throw std::runtime_error("could not encrypt pairing approval");
    return encrypted;
}
std::wstring wide(const std::string& text) {
    if (text.empty()) return {};
    int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), nullptr, 0);
    if (!count) throw std::invalid_argument("invalid UTF-8 argument");
    std::wstring result(static_cast<size_t>(count), 0);
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), result.data(), count);
    return result;
}
std::wstring quote(const std::wstring& argument) {
    std::wstring result = L"\"";
    size_t slashes = 0;
    for (const auto ch : argument) {
        if (ch == L'\\') { ++slashes; continue; }
        result.append(slashes * (ch == L'"' ? 2 : 1), L'\\');
        slashes = 0;
        if (ch == L'"') result += L'\\';
        result += ch;
    }
    result.append(slashes * 2, L'\\'); result += L'"';
    return result;
}
struct Handle {
    HANDLE value = nullptr;
    ~Handle() { if (value && value != INVALID_HANDLE_VALUE) CloseHandle(value); }
};
std::string process(const std::filesystem::path& executable, const std::vector<std::string>& args,
                    const std::atomic_bool& alive) {
    if (!alive) throw std::runtime_error("USB discovery stopped");
    Handle read_pipe, write_pipe;
    SECURITY_ATTRIBUTES security{sizeof(security), nullptr, TRUE};
    if (!CreatePipe(&read_pipe.value, &write_pipe.value, &security, 0) ||
        !SetHandleInformation(read_pipe.value, HANDLE_FLAG_INHERIT, 0))
        throw std::runtime_error("could not create adb output pipe");
    Handle null_input; null_input.value = CreateFileW(L"NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
        &security, OPEN_EXISTING, 0, nullptr);
    std::wstring command = quote(executable.wstring());
    for (const auto& argument : args) command += L" " + quote(wide(argument));
    STARTUPINFOW startup{}; startup.cb = sizeof(startup); startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdInput = null_input.value; startup.hStdOutput = startup.hStdError = write_pipe.value;
    PROCESS_INFORMATION child{};
    if (!CreateProcessW(executable.c_str(), command.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW,
                         nullptr, nullptr, &startup, &child))
        throw std::runtime_error("could not start bundled adb");
    Handle child_process, child_thread; child_process.value = child.hProcess; child_thread.value = child.hThread;
    CloseHandle(write_pipe.value); write_pipe.value = nullptr;
    std::string result;
    const auto deadline = GetTickCount64() + 4000;
    bool finished = false;
    for (;;) {
        DWORD available = 0;
        while (PeekNamedPipe(read_pipe.value, nullptr, 0, nullptr, &available, nullptr) && available) {
            char buffer[4096]; DWORD count = 0;
            if (!ReadFile(read_pipe.value, buffer, std::min<DWORD>(sizeof(buffer), available), &count, nullptr)) break;
            if (result.size() + count > 65536) { TerminateProcess(child_process.value, 1); throw std::runtime_error("adb output exceeded limit"); }
            result.append(buffer, count);
        }
        if (finished) break;
        if (WaitForSingleObject(child_process.value, 25) == WAIT_OBJECT_0) { finished = true; continue; }
        if (!alive || GetTickCount64() >= deadline) {
            TerminateProcess(child_process.value, 1); WaitForSingleObject(child_process.value, 1000);
            throw std::runtime_error("adb command timed out");
        }
    }
    DWORD code = 1; GetExitCodeProcess(child_process.value, &code);
    if (code) throw std::runtime_error("adb command failed");
    return result;
}
std::filesystem::path adb_path() {
    wchar_t override_path[32768];
    const auto count = GetEnvironmentVariableW(L"REFLECTION_ADB", override_path, 32768);
    if (count && count < 32768 && std::filesystem::is_regular_file(override_path)) return override_path;
    wchar_t executable[32768];
    const auto length = GetModuleFileNameW(nullptr, executable, 32768);
    if (length && length < 32768) {
        auto bundled = std::filesystem::path(executable).parent_path() / "platform-tools" / "adb.exe";
        if (std::filesystem::is_regular_file(bundled)) return bundled;
    }
    return {};
}
bool usb_serial(const std::string& value) {
    return !value.empty() && value.size() <= 128 &&
        value.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._:-") == std::string::npos;
}
bool broadcast_succeeded(const std::string& output) {
    const std::string prefix = "Broadcast completed: result=0";
    std::istringstream lines(output);
    for (std::string line; std::getline(lines, line);) {
        if (line.rfind(prefix, 0) != 0) continue;
        if (line.size() == prefix.size() || line[prefix.size()] == ',' || line[prefix.size()] == '\r') return true;
    }
    return false;
}
std::vector<std::string> usb_snapshot() {
    const SOCKET connection = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (connection == INVALID_SOCKET) throw std::runtime_error("ADB server socket unavailable");
    struct Close { SOCKET value; ~Close() { closesocket(value); } } close{connection};
    DWORD timeout = 1000;
    setsockopt(connection, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<char*>(&timeout), sizeof(timeout));
    setsockopt(connection, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<char*>(&timeout), sizeof(timeout));
    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_port = htons(5037);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(connection, reinterpret_cast<sockaddr*>(&address), sizeof(address)))
        throw std::runtime_error("ADB server is not ready");
    const std::string service = "host:track-devices-proto-binary";
    std::ostringstream header; header << std::hex << std::setw(4) << std::setfill('0') << service.size();
    const auto request = header.str() + service;
    for (size_t sent = 0; sent < request.size();) {
        const int count = send(connection, request.data() + sent, static_cast<int>(request.size() - sent), 0);
        if (count <= 0) throw std::runtime_error("ADB server request failed");
        sent += static_cast<size_t>(count);
    }
    auto exact = [&](size_t size) {
        std::string result(size, '\0');
        for (size_t received = 0; received < size;) {
            const int count = recv(connection, result.data() + received, static_cast<int>(size - received), 0);
            if (count <= 0) throw std::runtime_error("ADB server response timed out");
            received += static_cast<size_t>(count);
        }
        return result;
    };
    if (exact(4) != "OKAY") throw std::runtime_error("ADB server lacks typed device discovery");
    const auto length = exact(4);
    if (length.find_first_not_of("0123456789abcdefABCDEF") != std::string::npos)
        throw std::runtime_error("invalid ADB response length");
    const auto payload = exact(std::stoul(length, nullptr, 16));
    return adb_usb_devices({payload.begin(), payload.end()});
}
}

struct AutomaticConnectivity::Impl {
    Config config;
    std::filesystem::path trusted_path;
    SOCKET socket = INVALID_SOCKET;
    std::atomic_bool alive{true};
    std::thread listener, usb, bluetooth;
    std::mutex mutex;
    std::timed_mutex usb_commands;
    struct Device {
        DeviceAnnouncement announcement;
        sockaddr_in remote{};
        uint64_t seen = 0;
        std::string address, code;
        bool bluetooth = false;
    };
    struct Approval { std::string nonce, reply; };
    struct USBDevice { std::string serial, name; uint64_t seen; };
    std::map<std::string, Device> found;
    std::map<std::string, USBDevice> usb_found;
    std::map<std::string, Approval> approvals;
    std::set<std::string> trusted;
    std::string selected;
    uint64_t revision = 0;
    std::string discovery_error;
    bool winsock = false;

    Impl(const Config& configuration, const std::filesystem::path& path, bool allow_usb) : config(configuration) {
        trusted_path = path; trusted_path += L".devices";
        std::ifstream saved(trusted_path);
        for (std::string id; std::getline(saved, id);)
            if (id.size() == 32 && id.find_first_not_of("0123456789abcdef") == std::string::npos && trusted.size() < 128)
                trusted.insert(id);
        try {
            WSADATA data{};
            if (WSAStartup(MAKEWORD(2, 2), &data) == 0) {
                winsock = true;
                socket = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
                sockaddr_in address{}; address.sin_family = AF_INET; address.sin_port = htons(24820);
                BOOL exclusive = TRUE;
                if (socket == INVALID_SOCKET ||
                    setsockopt(socket, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, reinterpret_cast<char*>(&exclusive), sizeof(exclusive)) ||
                    InetPtonA(AF_INET, config.bind.c_str(), &address.sin_addr) != 1 ||
                    bind(socket, reinterpret_cast<sockaddr*>(&address), sizeof(address))) {
                    discovery_error = "LAN discovery unavailable; check bind address and UDP port 24820";
                    if (socket != INVALID_SOCKET) closesocket(socket);
                    socket = INVALID_SOCKET;
                } else listener = std::thread([this] { receive(); });
                bluetooth = std::thread([this] { pair_bluetooth(); });
            } else discovery_error = "Windows networking is unavailable";
            if (allow_usb) usb = std::thread([this] { provision_usb(); });
        } catch (...) { shutdown(); throw; }
    }
    ~Impl() { shutdown(); }
    void shutdown() {
        alive = false;
        if (listener.joinable()) listener.join();
        if (usb.joinable()) usb.join();
        if (bluetooth.joinable()) bluetooth.join();
        if (socket != INVALID_SOCKET) closesocket(socket);
        if (winsock) WSACleanup();
        socket = INVALID_SOCKET; winsock = false;
    }
    std::string host_id() const {
        return hex(sha256(std::vector<uint8_t>(config.token.begin(), config.token.end()))).substr(0, 16);
    }
    std::string device_token(const std::string& id) const {
        const auto label = "Reflection/device/v1\n" + id;
        return hex(hmac_sha256(unhex(config.token), {label.begin(), label.end()}));
    }
    void persist() {
        auto temporary = trusted_path; temporary += L".tmp";
        {
            std::ofstream output(temporary, std::ios::trunc);
            for (const auto& value : trusted) output << value << '\n';
            if (!output) throw std::runtime_error("Device history could not be saved");
        }
        if (!MoveFileExW(temporary.c_str(), trusted_path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
            throw std::runtime_error("Device history could not be saved");
    }
    void expire() {
        for (auto it = found.begin(); it != found.end();)
            if (GetTickCount64() - it->second.seen > 20000) {
                approvals.erase(it->first); it = found.erase(it);
            } else ++it;
        for (auto it = usb_found.begin(); it != usb_found.end();)
            if (GetTickCount64() - it->second.seen > 20000) it = usb_found.erase(it); else ++it;
    }
    std::string remember(DeviceAnnouncement announcement, sockaddr_in remote,
                         const std::string& address, bool is_bluetooth) {
        const auto digest = sha256(announcement.modulus);
        if (hex(digest).substr(0, 32) != announcement.id) throw std::invalid_argument("pairing identity mismatch");
        std::ostringstream code;
        code << std::setfill('0') << std::setw(6)
             << (((unsigned(digest[0]) << 16) | (unsigned(digest[1]) << 8) | digest[2]) % 1000000);
        std::lock_guard<std::mutex> lock(mutex); expire();
        if (found.size() >= 32 && !found.count(announcement.id)) throw std::runtime_error("nearby tablet limit reached");
        const auto id = announcement.id;
        auto approval = approvals.find(id);
        if (approval != approvals.end() && approval->second.nonce != announcement.nonce) {
            approvals.erase(approval); approval = approvals.end();
        }
        found[id] = Device{std::move(announcement), remote, GetTickCount64(), address, code.str(), is_bluetooth};
        return approval == approvals.end() ? "REFLECTION-PENDING/1" : approval->second.reply;
    }
    void pair_bluetooth() {
        while (alive) {
            try {
                Socket listening(::socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM));
                if (listening.value == INVALID_SOCKET) throw std::runtime_error("Bluetooth pairing socket unavailable");
                ULONG secure = TRUE;
                if (setsockopt(listening.value, SOL_RFCOMM, SO_BTH_AUTHENTICATE,
                               reinterpret_cast<const char*>(&secure), sizeof(secure)) ||
                    setsockopt(listening.value, SOL_RFCOMM, SO_BTH_ENCRYPT,
                               reinterpret_cast<const char*>(&secure), sizeof(secure)))
                    throw std::runtime_error("Bluetooth pairing security unavailable");
                SOCKADDR_BTH address{}; address.addressFamily = AF_BTH; address.port = BT_PORT_ANY;
                if (bind(listening.value, reinterpret_cast<sockaddr*>(&address), sizeof(address)) ||
                    listen(listening.value, 4)) throw std::runtime_error("Bluetooth pairing listener unavailable");
                PairingAdvertisement advertisement(listening.value);
                nonblocking(listening.value);
                while (alive) {
                    fd_set ready; FD_ZERO(&ready); FD_SET(listening.value, &ready); timeval timeout{0, 100000};
                    const int available = select(0, &ready, nullptr, nullptr, &timeout);
                    if (available == SOCKET_ERROR) throw std::runtime_error("Bluetooth pairing listener closed");
                    if (!available) continue;
                    SOCKADDR_BTH remote{}; int remote_size = sizeof(remote);
                    Socket client(accept(listening.value, reinterpret_cast<sockaddr*>(&remote), &remote_size));
                    if (client.value == INVALID_SOCKET) continue;
                    try {
                        nonblocking(client.value);
                        const auto deadline = GetTickCount64() + 1500;
                        uint8_t header[4];
                        pairing_transfer(client.value, header, sizeof(header), false, deadline, alive);
                        const auto size = read_u32(header);
                        if (size < 3 || size > 1200) continue;
                        std::vector<uint8_t> request(size);
                        pairing_transfer(client.value, request.data(), request.size(), false, deadline, alive);
                        auto device = bluetooth_pairing_request(request);
                        if (!device) continue;
                        const auto reply = remember(std::move(*device), {}, bluetooth_address(remote.btAddr), true);
                        std::vector<uint8_t> response{1, 5}; response.insert(response.end(), reply.begin(), reply.end());
                        auto bytes = frame(response);
                        pairing_transfer(client.value, bytes.data(), bytes.size(), true, deadline, alive);
                    } catch (const std::exception&) { /* A stalled peer does not stop discovery or pen input. */ }
                }
            } catch (const std::exception&) { /* Retry when an adapter is enabled or reconnects. */ }
            for (unsigned i = 0; i < 50 && alive; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    }
    void receive() {
        while (alive) {
            fd_set ready; FD_ZERO(&ready); FD_SET(socket, &ready); timeval timeout{0, 100000};
            if (select(0, &ready, nullptr, nullptr, &timeout) <= 0) continue;
            char buffer[1100]; sockaddr_in remote{}; int size = sizeof(remote);
            const int count = recvfrom(socket, buffer, sizeof(buffer), 0, reinterpret_cast<sockaddr*>(&remote), &size);
            if (count <= 0 || count > 1024) continue;
            const std::string message(buffer, static_cast<size_t>(count));
            try {
                if (const auto query = discovery_query(message)) {
                    std::string token = config.token;
                    if (!query->device_id.empty()) {
                        std::lock_guard<std::mutex> lock(mutex);
                        if (!trusted.count(query->device_id)) continue;
                        token = device_token(query->device_id);
                    }
                    const auto body = "REFLECTION-HOST/1\n" + query->nonce + "\n" + std::to_string(config.port) + "\n" + host_id();
                    const auto reply = body + "\n" + hex(hmac_sha256(unhex(token), {body.begin(), body.end()}));
                    sendto(socket, reply.data(), static_cast<int>(reply.size()), 0,
                           reinterpret_cast<sockaddr*>(&remote), size);
                } else if (auto announcement = parse_device_announcement(message)) {
                    char address[INET_ADDRSTRLEN]{};
                    InetNtopA(AF_INET, &remote.sin_addr, address, sizeof(address));
                    const auto reply = remember(std::move(*announcement), remote, address, false);
                    if (reply != "REFLECTION-PENDING/1")
                        sendto(socket, reply.data(), static_cast<int>(reply.size()), 0,
                               reinterpret_cast<sockaddr*>(&remote), size);
                }
            } catch (const std::exception&) { /* Malformed datagrams never stop the input host. */ }
        }
    }
    std::set<std::string> usb_devices(const std::filesystem::path& adb) {
        const auto output = process(adb, {"devices", "-l"}, alive);
        std::set<std::string> present;
        try {
            const auto physical = usb_snapshot();
            present.insert(physical.begin(), physical.end());
        } catch (const std::exception&) {
            // Older ADB versions may still provide an explicit USB bus path.
            std::istringstream rows(output);
            for (std::string row; std::getline(rows, row);) {
                std::istringstream fields(row); std::string serial, state; fields >> serial >> state;
                if (state == "device" && row.find("usb:") != std::string::npos && usb_serial(serial)) present.insert(serial);
            }
        }
        return present;
    }
    std::string usb_identity(const std::filesystem::path& adb, const std::string& serial) {
        const auto identity = process(adb, {"-s", serial, "shell",
            "am broadcast -n io.github.linloir.reflection/io.github.linloir.reflection.control.ControlReceiver --es command pairing-identity"}, alive);
        if (!broadcast_succeeded(identity))
            throw std::runtime_error("Could not read the USB tablet identity");
        for (size_t i = 0; i + 32 <= identity.size(); ++i) {
            const auto candidate = identity.substr(i, 32);
            if (candidate.find_first_not_of("0123456789abcdef") == std::string::npos) return candidate;
        }
        throw std::runtime_error("USB tablet identity is missing");
    }
    void send_usb_lease(const std::filesystem::path& adb, const std::string& serial,
                        const std::string& id, bool select) {
        const auto command = "am broadcast -n io.github.linloir.reflection/io.github.linloir.reflection.control.ControlReceiver"
            " --es command provision-usb --es token " + device_token(id) + " --ez select " + (select ? "true" : "false");
        const auto response = process(adb, {"-s", serial, "shell", command}, alive);
        if (!broadcast_succeeded(response))
            throw std::runtime_error("The USB tablet did not accept the connection");
    }
    void provision_usb() {
        const auto adb = adb_path();
        if (adb.empty()) return;
        while (alive) {
            try {
                const auto present = usb_devices(adb);
                for (const auto& serial : present) {
                  try {
                    std::lock_guard<std::timed_mutex> usb_lock(usb_commands);
                    process(adb, {"-s", serial, "reverse", "tcp:24819", "tcp:" + std::to_string(config.port)}, alive);
                    const auto id = usb_identity(adb, serial);
                    std::string name;
                    {
                        std::lock_guard<std::mutex> lock(mutex);
                        const auto known = usb_found.find(id);
                        if (known != usb_found.end()) name = known->second.name;
                    }
                    if (name.empty()) {
                        name = process(adb, {"-s", serial, "shell", "getprop ro.product.model"}, alive);
                        const auto end = name.find_last_not_of("\r\n\t ");
                        if (end != std::string::npos) name.erase(end + 1); else name.clear();
                        if (name.empty() || name.size() > 128 || name.find_first_of("\r\n") != std::string::npos)
                            name = "USB tablet";
                    }
                    {
                        std::lock_guard<std::mutex> lock(mutex); expire();
                        if (usb_found.size() >= 32 && !usb_found.count(id)) continue;
                        usb_found[id] = USBDevice{serial, name, GetTickCount64()};
                        if (!trusted.count(id) && trusted.size() >= 128) continue;
                    }
                    send_usb_lease(adb, serial, id, false);
                    {
                        std::lock_guard<std::mutex> lock(mutex);
                        const bool inserted = trusted.insert(id).second;
                        try { if (inserted) persist(); }
                        catch (...) { if (inserted) trusted.erase(id); throw; }
                    }
                  } catch (const std::exception&) { /* One unavailable tablet does not hide the other USB devices. */ }
                }
                std::lock_guard<std::mutex> lock(mutex); expire();
                for (auto it = usb_found.begin(); it != usb_found.end();)
                    if (!present.count(it->second.serial)) it = usb_found.erase(it); else ++it;
            } catch (const std::exception&) { /* An absent or locked tablet is retried. */ }
            for (unsigned i = 0; i < 50 && alive; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    }
    std::string devices() {
        std::lock_guard<std::mutex> lock(mutex); expire();
        if (!discovery_error.empty() && found.empty() && usb_found.empty()) throw std::runtime_error(discovery_error);
        std::string result = "["; bool first = true;
        for (const auto& [id, device] : found) {
            if (usb_found.count(id)) continue;
            if (!first) result += ','; first = false;
            result += "{\"id\":" + json_escape(id) + ",\"name\":" + json_escape(device.announcement.name)
                + ",\"address\":" + json_escape(device.address) + ",\"code\":" + json_escape(device.code)
                + ",\"trusted\":" + (trusted.count(id) ? "true" : "false") + "}";
        }
        for (const auto& [id, device] : usb_found) {
            if (!first) result += ','; first = false;
            std::ostringstream code;
            code << std::setfill('0') << std::setw(6) << (std::stoul(id.substr(0, 6), nullptr, 16) % 1000000);
            const auto network = found.find(id);
            const auto& name = network == found.end() ? device.name : network->second.announcement.name;
            result += "{\"id\":" + json_escape(id) + ",\"name\":" + json_escape(name)
                + ",\"address\":" + json_escape("USB " + device.serial) + ",\"code\":" + json_escape(code.str())
                + ",\"trusted\":" + (trusted.count(id) ? "true" : "false") + "}";
        }
        return result + "]";
    }
    std::string trust(const std::string& id) {
        std::unique_lock<std::mutex> lock(mutex); expire();
        if (!trusted.count(id) && trusted.size() >= 128) throw std::runtime_error("Saved tablet limit reached");
        const auto usb_device = usb_found.find(id);
        if (usb_device != usb_found.end()) {
            const auto serial = usb_device->second.serial;
            lock.unlock();
            std::unique_lock<std::timed_mutex> usb_lock(usb_commands, std::defer_lock);
            if (!usb_lock.try_lock_for(std::chrono::seconds(5)))
                throw std::runtime_error("USB discovery is busy; retry the connection");
            const auto adb = adb_path();
            if (adb.empty() || !usb_devices(adb).count(serial) || usb_identity(adb, serial) != id)
                throw std::runtime_error("USB tablet changed or disconnected; refresh and retry");
            process(adb, {"-s", serial, "reverse", "tcp:24819", "tcp:" + std::to_string(config.port)}, alive);
            send_usb_lease(adb, serial, id, true);
            lock.lock();
            const bool inserted = trusted.insert(id).second;
            try { persist(); }
            catch (...) { if (inserted) trusted.erase(id); throw; }
            if (selected != id) { selected = id; ++revision; }
            return "{\"approvalSent\":true,\"usbSelected\":true}";
        }
        const auto found_device = found.find(id);
        if (found_device == found.end()) throw std::runtime_error("Tablet is no longer visible; keep Reflection open and retry");
        const auto& device = found_device->second;
        const auto plaintext = device.announcement.nonce + "\n" + host_id() + "\n" + std::to_string(config.port) + "\n" + device_token(id);
        const auto reply = "REFLECTION-PAIR/1\n" + device.announcement.nonce + "\n" + base64_encode(encrypt(device.announcement, plaintext));
        const bool inserted = trusted.insert(id).second;
        try { persist(); }
        catch (...) { if (inserted) trusted.erase(id); throw; }
        approvals[id] = Approval{device.announcement.nonce, reply};
        if (selected != id) { selected = id; ++revision; }
        // Discovery retries repeat this offer if the initial UDP response is lost.
        if (!device.bluetooth)
            sendto(socket, reply.data(), static_cast<int>(reply.size()), 0,
                   reinterpret_cast<const sockaddr*>(&device.remote), sizeof(device.remote));
        const auto confirmation_label = "Reflection/pair-confirm/v1\n" + device.announcement.nonce;
        const auto digest = hmac_sha256(unhex(device_token(id)), {confirmation_label.begin(), confirmation_label.end()});
        std::ostringstream code;
        code << std::setfill('0') << std::setw(6)
             << (((unsigned(digest[0]) << 16) | (unsigned(digest[1]) << 8) | digest[2]) % 1000000);
        return "{\"approvalSent\":true,\"confirmationCode\":" + json_escape(code.str()) + "}";
    }
};

AutomaticConnectivity::AutomaticConnectivity(const Config& config, const std::filesystem::path& path, bool allow_usb)
    : impl_(std::make_unique<Impl>(config, path, allow_usb)) {}
AutomaticConnectivity::~AutomaticConnectivity() = default;
void AutomaticConnectivity::cancel() noexcept { impl_->alive = false; }
std::string AutomaticConnectivity::devices() { return impl_->devices(); }
std::string AutomaticConnectivity::trust(const std::string& id) { return impl_->trust(id); }
std::vector<std::string> AutomaticConnectivity::authentication_tokens() {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    if (!impl_->selected.empty()) return {impl_->device_token(impl_->selected)};
    std::vector<std::string> result{impl_->config.token};
    for (const auto& id : impl_->trusted) result.push_back(impl_->device_token(id));
    return result;
}
uint64_t AutomaticConnectivity::selection_revision() {
    std::lock_guard<std::mutex> lock(impl_->mutex); return impl_->revision;
}
} // namespace reflection
