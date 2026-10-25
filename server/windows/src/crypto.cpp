#include "reflection/windows.hpp"
#include <bcrypt.h>

namespace reflection {
namespace {

class HmacProvider {
public:
    HmacProvider() {
        if (BCryptOpenAlgorithmProvider(&handle, BCRYPT_SHA256_ALGORITHM, nullptr,
                                        BCRYPT_ALG_HANDLE_HMAC_FLAG) < 0)
            throw std::runtime_error("BCryptOpenAlgorithmProvider failed");
        DWORD received = 0;
        if (BCryptGetProperty(handle, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&object_size),
                              sizeof(object_size), &received, 0) < 0) {
            BCryptCloseAlgorithmProvider(handle, 0);
            throw std::runtime_error("BCryptGetProperty failed");
        }
    }
    ~HmacProvider() { BCryptCloseAlgorithmProvider(handle, 0); }
    BCRYPT_ALG_HANDLE handle = nullptr;
    DWORD object_size = 0;
};

} // namespace

std::vector<uint8_t> random_bytes(size_t count) {
    if (count > 4096) throw std::invalid_argument("random request too large");
    std::vector<uint8_t> result(count);
    if (BCryptGenRandom(nullptr, result.data(), static_cast<ULONG>(result.size()),
                        BCRYPT_USE_SYSTEM_PREFERRED_RNG) < 0)
        throw std::runtime_error("BCryptGenRandom failed");
    return result;
}

std::vector<uint8_t> hmac_sha256(const std::vector<uint8_t>& key, const std::vector<uint8_t>& message) {
    // Opening a CNG algorithm provider is relatively expensive on the pen path.
    static const HmacProvider algorithm;
    BCRYPT_HASH_HANDLE hash = nullptr;
    std::vector<uint8_t> object(algorithm.object_size);
    auto cleanup = [&] { if (hash) BCryptDestroyHash(hash); };
    try {
        if (BCryptCreateHash(algorithm.handle, &hash, object.data(), algorithm.object_size,
                             const_cast<PUCHAR>(key.data()), static_cast<ULONG>(key.size()), 0) < 0 ||
            BCryptHashData(hash, const_cast<PUCHAR>(message.data()), static_cast<ULONG>(message.size()), 0) < 0)
            throw std::runtime_error("BCrypt HMAC initialization failed");
        std::vector<uint8_t> result(32);
        if (BCryptFinishHash(hash, result.data(), static_cast<ULONG>(result.size()), 0) < 0)
            throw std::runtime_error("BCryptFinishHash failed");
        cleanup();
        return result;
    } catch (...) { cleanup(); throw; }
}

std::vector<uint8_t> unhex(const std::string& text) {
    if (text.size() != 64 || text.find_first_not_of("0123456789abcdefABCDEF") != std::string::npos)
        throw std::invalid_argument("token must be 64 hex characters");
    std::vector<uint8_t> result;
    for (size_t i = 0; i < text.size(); i += 2)
        result.push_back(static_cast<uint8_t>(std::stoul(text.substr(i, 2), nullptr, 16)));
    return result;
}

std::string hex(const std::vector<uint8_t>& bytes) {
    constexpr char digits[] = "0123456789abcdef";
    std::string result;
    result.reserve(bytes.size() * 2);
    for (const auto b : bytes) { result += digits[b >> 4]; result += digits[b & 15]; }
    return result;
}

} // namespace reflection
