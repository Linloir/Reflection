#include "reflection/windows.hpp"
#include <iostream>

using namespace reflection;

int main() {
    try {
        // These are public conformance fixtures from protocol/vectors.json.
        const auto key = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        const auto nonce = unhex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f");
        const std::vector<std::pair<std::string, std::string>> vectors{
            {"Reflection/client/v1", "2777043a753f844b5e4fef4154dbe811bbccf3444287dc4ebe4085f37d1e1633"},
            {"Reflection/server/v1", "608da486cbe7cf85f382e68582e1eb77481cb88d5bd787b5ccef01d2786cdd98"},
            {"Reflection/session/v1", "5d157678ff539b47d080ecef300be00922b12a28cc97b7019d8035465b8cc74d"}
        };
        for (const auto& [label, expected] : vectors) {
            std::vector<uint8_t> input(label.begin(), label.end());
            input.insert(input.end(), nonce.begin(), nonce.end());
            if (hex(hmac_sha256(key, input)) != expected) throw std::runtime_error("HMAC conformance failed");
        }
        if (random_bytes(32).size() != 32) throw std::runtime_error("CSPRNG failed");
        std::cout << "Windows BCrypt golden vectors passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
