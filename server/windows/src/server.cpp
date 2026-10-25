#include "reflection/windows.hpp"
#include "reflection/protocol.hpp"
#include <ws2bth.h>
#include <chrono>

namespace reflection {
namespace {

using Clock = std::chrono::steady_clock;
using Deadline = Clock::time_point;
const GUID bluetooth_service{0x7b011e5a, 0x8b31, 0x4c40, {0x8d, 0x7a, 0x3e, 0x6c, 0x6d, 0xcb, 0xb7, 0x58}};

class Socket {
public:
    explicit Socket(SOCKET value = INVALID_SOCKET) : value_(value) {}
    ~Socket() { if (value_ != INVALID_SOCKET) closesocket(value_); }
    Socket(const Socket&) = delete;
    Socket& operator=(const Socket&) = delete;
    SOCKET get() const { return value_; }
private:
    SOCKET value_;
};

class Winsock {
public:
    Winsock() { WSADATA data{}; if (WSAStartup(MAKEWORD(2, 2), &data)) throw std::runtime_error("WSAStartup failed"); }
    ~Winsock() { WSACleanup(); }
};

std::runtime_error socket_error(const std::string& what) {
    return std::runtime_error(what + ": Winsock " + std::to_string(WSAGetLastError()));
}

void nonblocking(SOCKET socket) {
    u_long enabled = 1;
    if (ioctlsocket(socket, FIONBIO, &enabled)) throw socket_error("FIONBIO");
}

bool ready(SOCKET socket, bool write, Deadline deadline, const std::atomic_bool& running) {
    while (running.load()) {
        const auto remaining = std::chrono::duration_cast<std::chrono::microseconds>(deadline - Clock::now()).count();
        if (remaining <= 0) return false;
        const auto wait = std::min<int64_t>(remaining, 250000);
        timeval timeout{static_cast<long>(wait / 1000000), static_cast<long>(wait % 1000000)};
        fd_set set;
        FD_ZERO(&set);
        FD_SET(socket, &set);
        const int result = select(0, write ? nullptr : &set, write ? &set : nullptr, nullptr, &timeout);
        if (result > 0) return true;
        if (result == SOCKET_ERROR) throw socket_error("select");
    }
    return false;
}

void transfer(SOCKET socket, uint8_t* data, size_t size, bool write, Deadline deadline,
              const std::atomic_bool& running) {
    size_t offset = 0;
    while (offset < size) {
        if (!ready(socket, write, deadline, running)) throw std::runtime_error("session timeout or shutdown");
        const int count = write ?
            send(socket, reinterpret_cast<const char*>(data + offset), static_cast<int>(size - offset), 0) :
            recv(socket, reinterpret_cast<char*>(data + offset), static_cast<int>(size - offset), 0);
        if (count == 0) throw std::runtime_error("peer disconnected");
        if (count == SOCKET_ERROR) {
            if (WSAGetLastError() == WSAEWOULDBLOCK) continue;
            throw socket_error(write ? "send" : "recv");
        }
        offset += static_cast<size_t>(count);
    }
}

std::vector<uint8_t> receive(SOCKET socket, Deadline deadline, const std::atomic_bool& running) {
    uint8_t header[4];
    transfer(socket, header, sizeof(header), false, deadline, running);
    const auto size = read_u32(header);
    if (size < 2 || size > maximum_body) throw std::runtime_error("invalid frame length");
    std::vector<uint8_t> result(size);
    transfer(socket, result.data(), result.size(), false, deadline, running);
    if (result[0] != protocol_version) throw std::runtime_error("unsupported protocol version");
    return result;
}

void transmit(SOCKET socket, std::vector<uint8_t> body, Deadline deadline, const std::atomic_bool& running) {
    auto bytes = frame(body);
    transfer(socket, bytes.data(), bytes.size(), true, deadline, running);
}

std::vector<uint8_t> labelled(const char* label, const std::vector<uint8_t>& nonce) {
    std::vector<uint8_t> bytes(label, label + std::char_traits<char>::length(label));
    bytes.insert(bytes.end(), nonce.begin(), nonce.end());
    return bytes;
}

bool same_bytes(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
    return constant_time_equal(std::string(a.begin(), a.end()), std::string(b.begin(), b.end()));
}

std::vector<uint8_t> authenticate(SOCKET socket, const std::vector<uint8_t>& token,
                                  const std::atomic_bool& running) {
    const auto deadline = Clock::now() + std::chrono::seconds(5);
    const auto nonce = random_bytes(32);
    std::vector<uint8_t> challenge{1, 1};
    challenge.insert(challenge.end(), nonce.begin(), nonce.end());
    transmit(socket, std::move(challenge), deadline, running);
    const auto response = receive(socket, deadline, running);
    const auto expected = hmac_sha256(token, labelled("Reflection/client/v1", nonce));
    if (response.size() != 34 || response[1] != 2 ||
        !same_bytes(std::vector<uint8_t>(response.begin() + 2, response.end()), expected))
        throw std::runtime_error("authentication failed");
    const auto proof = hmac_sha256(token, labelled("Reflection/server/v1", nonce));
    std::vector<uint8_t> accepted{1, 3};
    accepted.insert(accepted.end(), proof.begin(), proof.end());
    transmit(socket, std::move(accepted), deadline, running);
    return hmac_sha256(token, labelled("Reflection/session/v1", nonce));
}

class BluetoothAdvertisement {
public:
    explicit BluetoothAdvertisement(SOCKET socket) {
        int size = sizeof(address_);
        if (getsockname(socket, reinterpret_cast<sockaddr*>(&address_), &size)) throw socket_error("getsockname");
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
        if (WSASetServiceW(&query_, RNRSERVICE_REGISTER, 0)) throw socket_error("Bluetooth SDP registration");
        registered_ = true;
    }
    ~BluetoothAdvertisement() { if (registered_) WSASetServiceW(&query_, RNRSERVICE_DELETE, 0); }
    unsigned channel() const { return address_.port; }
private:
    SOCKADDR_BTH address_{};
    CSADDR_INFO addresses_{};
    WSAQUERYSETW query_{};
    GUID service_ = bluetooth_service;
    wchar_t name_[11] = L"Reflection";
    bool registered_ = false;
};

void session(SOCKET socket, const Config& config, bool dry_run, const std::atomic_bool& running,
             const EventSink& events) {
    const auto session_key = authenticate(socket, unhex(config.token), running);
    auto backend = make_pen_backend(dry_run);
    struct ReleaseOnExit {
        InputBackend* backend;
        ~ReleaseOnExit() { backend->release(); }
    } release{backend.get()};
    Mapper mapper(config.mapping);
    POINT cursor{};
    if (GetCursorPos(&cursor)) mapper.seed(cursor.x, cursor.y);
    SequenceGuard guard;
    ContactGuard contact_guard;
    uint64_t count = 0;
    bool capabilities_seen = false;
    if (events) events({"connected", "", 0, 0, dry_run, {}});
    while (running.load()) {
        auto body = receive(socket, Clock::now() + std::chrono::seconds(2), running);
        if (body.size() < 34) throw std::runtime_error("missing frame authentication");
        const std::vector<uint8_t> tag(body.end() - 32, body.end());
        body.resize(body.size() - 32);
        if (!same_bytes(tag, hmac_sha256(session_key, body))) throw std::runtime_error("frame authentication failed");
        std::vector<uint8_t> payload(body.begin() + 2, body.end());
        if (body[1] == 0x10) {
            const auto sample = decode_pen(payload);
            guard.accept(sample);
            contact_guard.accept(sample);
            const auto mapped = mapper.map(sample);
            backend->submit(mapped);
            ++count;
            if (events && dry_run && sample.phase != Phase::move && sample.phase != Phase::hover)
                events({"pen", "", count, 0, true, mapped});
        } else if (body[1] == 0x11) {
            if (payload.size() != 8) throw std::runtime_error("invalid ping length");
            guard.accept_ping(read_u64(payload.data()));
            body[1] = 0x12;
            const auto response_tag = hmac_sha256(session_key, body);
            body.insert(body.end(), response_tag.begin(), response_tag.end());
            transmit(socket, std::move(body), Clock::now() + std::chrono::seconds(1), running);
        } else if (body[1] == 0x20) {
            if (capabilities_seen || count != 0) throw std::runtime_error("replayed or late capabilities");
            capabilities_seen = true;
        } else {
            throw std::runtime_error("unexpected session message");
        }
    }
    if (events) events({"session_end", "", count, 0, dry_run, {}});
}

} // namespace

void serve(const Config& original, bool dry_run, std::atomic_bool& running, const EventSink& events) {
    original.validate();
    Config config = original;
    if (config.target_virtual_desktop) config.mapping.target = virtual_desktop();
    config.mapping.validate();
    if (!dry_run && !pen_api_available()) throw std::runtime_error("Synthetic Pointer API unavailable");
    Winsock winsock;
    const bool bluetooth = config.transport == "bluetooth";
    Socket listener(socket(bluetooth ? AF_BTH : AF_INET, SOCK_STREAM, bluetooth ? BTHPROTO_RFCOMM : IPPROTO_TCP));
    if (listener.get() == INVALID_SOCKET) throw socket_error("socket");
    std::unique_ptr<BluetoothAdvertisement> advertisement;
    if (bluetooth) {
        ULONG enabled = TRUE;
        if (setsockopt(listener.get(), SOL_RFCOMM, SO_BTH_AUTHENTICATE,
                        reinterpret_cast<const char*>(&enabled), sizeof(enabled)) ||
            setsockopt(listener.get(), SOL_RFCOMM, SO_BTH_ENCRYPT,
                        reinterpret_cast<const char*>(&enabled), sizeof(enabled)))
            throw socket_error("Bluetooth authentication/encryption");
        SOCKADDR_BTH address{};
        address.addressFamily = AF_BTH;
        address.port = BT_PORT_ANY;
        if (bind(listener.get(), reinterpret_cast<sockaddr*>(&address), sizeof(address)))
            throw socket_error("Bluetooth bind");
    } else {
        BOOL exclusive = TRUE;
        if (setsockopt(listener.get(), SOL_SOCKET, SO_EXCLUSIVEADDRUSE,
                        reinterpret_cast<const char*>(&exclusive), sizeof(exclusive)))
            throw socket_error("SO_EXCLUSIVEADDRUSE");
        sockaddr_in address{};
        address.sin_family = AF_INET;
        address.sin_port = htons(config.port);
        if (InetPtonA(AF_INET, config.bind.c_str(), &address.sin_addr) != 1)
            throw std::invalid_argument("bind must be a numeric IPv4 address");
        if (bind(listener.get(), reinterpret_cast<sockaddr*>(&address), sizeof(address)))
            throw socket_error("TCP bind");
    }
    if (listen(listener.get(), 4)) throw socket_error("listen");
    if (bluetooth) advertisement = std::make_unique<BluetoothAdvertisement>(listener.get());
    nonblocking(listener.get());
    if (events) events({"listening", config.transport, 0,
                        bluetooth ? advertisement->channel() : config.port, dry_run, {}});
    while (running.load()) {
        if (!ready(listener.get(), false, Clock::now() + std::chrono::milliseconds(500), running)) continue;
        Socket client(accept(listener.get(), nullptr, nullptr));
        if (client.get() == INVALID_SOCKET) {
            if (WSAGetLastError() == WSAEWOULDBLOCK) continue;
            throw socket_error("accept");
        }
        try {
            nonblocking(client.get());
            if (!bluetooth) {
                BOOL enabled = TRUE;
                if (setsockopt(client.get(), IPPROTO_TCP, TCP_NODELAY,
                                reinterpret_cast<const char*>(&enabled), sizeof(enabled)))
                    throw socket_error("TCP_NODELAY");
            }
            session(client.get(), config, dry_run, running, events);
        } catch (const std::exception& error) {
            if (events) events({"disconnected", error.what(), 0, 0, dry_run, {}});
        }
    }
}

} // namespace reflection
