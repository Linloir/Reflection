#include "reflection/windows.hpp"
#include "reflection/driver.hpp"
#include "reflection/runtime.hpp"
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

bool ready(SOCKET socket, bool write, Deadline deadline, const std::atomic_bool& running,
           const std::function<void()>& tick = {}) {
    while (running.load()) {
        if(Clock::now()>=deadline) return false;
        if(tick) tick();
        const auto remaining = std::chrono::duration_cast<std::chrono::microseconds>(deadline - Clock::now()).count();
        if (remaining <= 0) return false;
        const auto wait = std::min<int64_t>(remaining, tick ? 50000 : 250000);
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
              const std::atomic_bool& running, const std::function<void()>& tick = {}) {
    size_t offset = 0;
    while (offset < size) {
        if (!ready(socket, write, deadline, running, tick)) throw std::runtime_error("session timeout or shutdown");
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

std::vector<uint8_t> receive(SOCKET socket, Deadline deadline, const std::atomic_bool& running,
                             const std::function<void()>& tick = {}) {
    uint8_t header[4];
    transfer(socket, header, sizeof(header), false, deadline, running, tick);
    const auto size = read_u32(header);
    if (size < 2 || size > maximum_body) throw std::runtime_error("invalid frame length");
    std::vector<uint8_t> result(size);
    transfer(socket, result.data(), result.size(), false, deadline, running, tick);
    if (result[0] != protocol_version) throw std::runtime_error("unsupported protocol version");
    return result;
}

void transmit(SOCKET socket, std::vector<uint8_t> body, Deadline deadline, const std::atomic_bool& running,
              const std::function<void()>& tick = {}) {
    auto bytes = frame(body);
    transfer(socket, bytes.data(), bytes.size(), true, deadline, running, tick);
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

class ModeBackend final : public InputBackend {
    Config config_;
    bool dry_run_;
    std::filesystem::path path_;
    EventSink events_;
    std::unique_ptr<InputBackend> backend_;
    Clock::time_point checked_{};
    bool wait_for_down_ = false;
    std::string failed_;
    std::unique_ptr<InputBackend> create(const std::string& mode) {
        if (dry_run_ || mode == "api") return make_pen_backend(dry_run_, config_.show_cursor);
        return make_driver_backend();
    }
public:
    ModeBackend(const Config& config, bool dry_run, const std::filesystem::path& path, EventSink events)
        : config_(config), dry_run_(dry_run), path_(path), events_(std::move(events)) {
        backend_ = create(config_.input_mode);
        if (events_) events_({"input_mode", config_.input_mode, 0, 0, dry_run_, {}});
    }
    void tick() override {
        const auto now = Clock::now();
        if (!path_.empty() && now - checked_ >= std::chrono::milliseconds(250)) {
            checked_ = now;
            try {
                auto mode = load_config(path_).input_mode;
                if (!dry_run_ && mode == "driver" && !driver_status(false).installed) {
                    ConfigLock lock(path_);
                    auto current = load_config(path_);
                    if (current.input_mode == "driver") { current.input_mode = "api"; save_config(current, path_); }
                    mode = current.input_mode;
                }
                if (mode != config_.input_mode) {
                    auto next = create(mode);
                    backend_->release();
                    backend_ = std::move(next);
                    config_.input_mode = mode;
                    wait_for_down_ = true;
                    if (events_) events_({"input_mode", mode, 0, 0, dry_run_, {}});
                }
                failed_.clear();
            } catch (const std::exception& error) {
                if (failed_ != error.what() && events_) events_({"input_error", error.what(), 0, 0, dry_run_, {}});
                failed_ = error.what();
            }
        }
        backend_->tick();
    }
    void submit(const MappedSample& sample) override {
        if (wait_for_down_) {
            if (sample.source.phase == Phase::down || sample.source.phase == Phase::hover)
                wait_for_down_ = false;
            else return;
        }
        backend_->submit(sample);
    }
    void release() noexcept override { backend_->release(); wait_for_down_ = false; }
};

void session(SOCKET socket, const Config& config, bool dry_run, const std::atomic_bool& running,
             const EventSink& events, DisplayMapping& mapping, HostInput& input, InputBackend& backend) {
    const auto session_key = authenticate(socket, unhex(config.token), running);
    struct ReleaseOnExit {
        InputBackend* backend;
        DisplayMapping* mapping;
        HostInput* input;
        ~ReleaseOnExit() { backend->release(); mapping->release(); input->end_session(); }
    } release{&backend, &mapping, &input};
    input.begin_session();
    POINT cursor{};
    if (GetCursorPos(&cursor)) mapping.seed(cursor.x, cursor.y);
    SequenceGuard guard;
    ContactGuard contact_guard;
    const auto tick = [&] {
        if(input.take_release()) { backend.release(); mapping.release(); contact_guard=ContactGuard{}; }
        backend.tick();
    };
    uint64_t count = 0;
    auto last_status = Clock::now();
    bool capabilities_seen = false;
    bool mapping_enabled = false;
    uint64_t mapping_sequence = 0, mapping_response = 0, mapping_revision = 0;
    auto send_mapping = [&] {
        // Capture before serializing: a simultaneous local switch must remain dirty for the next heartbeat.
        mapping_revision = mapping.revision();
        const auto status = mapping.status(++mapping_response);
        std::vector<uint8_t> message{1, 0x22};
        message.insert(message.end(), status.begin(), status.end());
        const auto tag = hmac_sha256(session_key, message);
        message.insert(message.end(), tag.begin(), tag.end());
        transmit(socket, std::move(message), Clock::now() + std::chrono::seconds(1), running, tick);
    };
    if (events) events({"connected", "", 0, 0, dry_run, {}});
    while (running.load()) {
        auto body = receive(socket, Clock::now() + std::chrono::seconds(2), running, tick);
        if (body.size() < 34) throw std::runtime_error("missing frame authentication");
        const std::vector<uint8_t> tag(body.end() - 32, body.end());
        body.resize(body.size() - 32);
        if (!same_bytes(tag, hmac_sha256(session_key, body))) throw std::runtime_error("frame authentication failed");
        std::vector<uint8_t> payload(body.begin() + 2, body.end());
        if (body[1] == 0x10) {
            const auto raw = decode_pen(payload);
            guard.accept(raw);
            ++count;
            tick();
            const auto processed = input.process(raw);
            if(!processed) continue;
            const auto& sample=*processed;
            contact_guard.accept(sample);
            const auto mapped = mapping.map(sample);
            backend.submit(mapped);
            if (events && Clock::now() - last_status >= std::chrono::milliseconds(500)) {
                events({"samples", "", count, 0, dry_run, {}});
                last_status = Clock::now();
            }
            if (events && dry_run && sample.phase != Phase::move && sample.phase != Phase::hover)
                events({"pen", "", count, 0, true, mapped});
        } else if (body[1] == 0x11) {
            if (payload.size() != 8) throw std::runtime_error("invalid ping length");
            guard.accept_ping(read_u64(payload.data()));
            body[1] = 0x12;
            const auto response_tag = hmac_sha256(session_key, body);
            body.insert(body.end(), response_tag.begin(), response_tag.end());
            transmit(socket, std::move(body), Clock::now() + std::chrono::seconds(1), running, tick);
            if (mapping_enabled && mapping.revision() != mapping_revision) send_mapping();
        } else if (body[1] == 0x20) {
            if (capabilities_seen || count != 0) throw std::runtime_error("replayed or late capabilities");
            capabilities_seen = true;
            mapping_enabled = mapping_capability(payload);
            input.negotiate(stabilization_capability(payload));
            if (mapping_enabled) send_mapping();
        } else if (body[1] == 0x21 && mapping_enabled) {
            const auto request = decode_mapping_request(payload, mapping_sequence);
            mapping_sequence = request.sequence;
            mapping.request(request.action, request.display);
            send_mapping();
        } else {
            throw std::runtime_error("unexpected session message");
        }
    }
    if (events) events({"session_end", "", count, 0, dry_run, {}});
}

} // namespace

void serve(const Config& original, bool dry_run, std::atomic_bool& running, const EventSink& events,
           std::shared_ptr<DisplayMapping> mapping, std::shared_ptr<HostInput> input,
           const std::filesystem::path& configuration_path) {
    original.validate();
    Config config = original;
    if (!mapping) mapping = std::make_shared<DisplayMapping>(config, displays);
    if (!input) input = std::make_shared<HostInput>(config.stabilization);
    config.mapping.validate();
    ModeBackend backend(config, dry_run, configuration_path, events);
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
        backend.tick();
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
            session(client.get(), config, dry_run, running, events, *mapping, *input, backend);
        } catch (const std::exception& error) {
            if (events) events({"disconnected", error.what(), 0, 0, dry_run, {}});
        }
    }
}

} // namespace reflection
