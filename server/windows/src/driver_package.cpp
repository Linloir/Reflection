#include "reflection/windows.hpp"
#include "reflection/driver.hpp"
#include <wintrust.h>
#include <softpub.h>
#include <mscat.h>
#include <fstream>
#include <sstream>

namespace reflection {
namespace {
struct File {
    HANDLE value = INVALID_HANDLE_VALUE;
    ~File() { if (value != INVALID_HANDLE_VALUE) CloseHandle(value); }
};
struct CatalogAdmin {
    HCATADMIN value = nullptr;
    ~CatalogAdmin() { if (value) CryptCATAdminReleaseContext(value, 0); }
};
std::string trust_error(LONG code) {
    std::ostringstream text;
    text << "0x" << std::hex << static_cast<unsigned long>(code);
    return text.str();
}
LONG verify_member(const std::filesystem::path& catalog, const std::filesystem::path& member,
                   const wchar_t* algorithm) {
    GUID policy = DRIVER_ACTION_VERIFY;
    CatalogAdmin admin;
    if (!CryptCATAdminAcquireContext2(&admin.value, &policy, algorithm, nullptr, 0))
        return static_cast<LONG>(GetLastError());
    File file{CreateFileW(member.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr)};
    if (file.value == INVALID_HANDLE_VALUE) return static_cast<LONG>(GetLastError());
    DWORD size = 0;
    if (!CryptCATAdminCalcHashFromFileHandle2(admin.value, file.value, &size, nullptr, 0) || !size)
        return static_cast<LONG>(GetLastError());
    std::vector<BYTE> hash(size);
    if (!CryptCATAdminCalcHashFromFileHandle2(admin.value, file.value, &size, hash.data(), 0))
        return static_cast<LONG>(GetLastError());
    std::wstring tag;
    for (auto byte : hash) {
        tag += L"0123456789ABCDEF"[byte >> 4];
        tag += L"0123456789ABCDEF"[byte & 15];
    }
    WINTRUST_CATALOG_INFO info{};
    info.cbStruct = sizeof(info);
    info.pcwszCatalogFilePath = catalog.c_str();
    info.pcwszMemberTag = tag.c_str();
    info.pcwszMemberFilePath = member.c_str();
    info.hMemberFile = file.value;
    info.pbCalculatedFileHash = hash.data();
    info.cbCalculatedFileHash = size;
    info.hCatAdmin = admin.value;
    WINTRUST_DATA trust{};
    trust.cbStruct = sizeof(trust);
    trust.dwUIChoice = WTD_UI_NONE;
    trust.dwUnionChoice = WTD_CHOICE_CATALOG;
    trust.pCatalog = &info;
    trust.dwStateAction = WTD_STATEACTION_VERIFY;
    // A status read must not block on the network. Windows rechecks policy at installation.
    trust.dwProvFlags = WTD_CACHE_ONLY_URL_RETRIEVAL | WTD_REVOCATION_CHECK_NONE |
        WTD_CODE_INTEGRITY_DRIVER_MODE;
    const auto result = WinVerifyTrust(reinterpret_cast<HWND>(INVALID_HANDLE_VALUE), &policy, &trust);
    trust.dwStateAction = WTD_STATEACTION_CLOSE;
    WinVerifyTrust(reinterpret_cast<HWND>(INVALID_HANDLE_VALUE), &policy, &trust);
    return result;
}
WORD driver_machine(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    IMAGE_DOS_HEADER dos{};
    if (!input.read(reinterpret_cast<char*>(&dos), sizeof(dos)) || dos.e_magic != IMAGE_DOS_SIGNATURE ||
        dos.e_lfanew < static_cast<LONG>(sizeof(dos)) || dos.e_lfanew > 1024 * 1024) return 0;
    input.seekg(dos.e_lfanew);
    DWORD signature = 0;
    IMAGE_FILE_HEADER header{};
    if (!input.read(reinterpret_cast<char*>(&signature), sizeof(signature)) || signature != IMAGE_NT_SIGNATURE ||
        !input.read(reinterpret_cast<char*>(&header), sizeof(header))) return 0;
    return header.Machine;
}
}

DriverPackageStatus inspect_driver_package(const std::filesystem::path& directory) {
    DriverPackageStatus result;
    result.directory = std::filesystem::absolute(directory);
    try {
        std::vector<std::string> missing;
        for (const auto* name : {"ReflectionTablet.inf", "ReflectionTablet.sys", "ReflectionTablet.cat"})
            if (!std::filesystem::is_regular_file(result.directory / name)) missing.emplace_back(name);
        if (!missing.empty()) {
            result.state = missing.size() == 3 ? "missing" : "incomplete";
            result.detail = missing.size() == 3
                ? "This build does not include a driver package. Use API Mode, or obtain a Reflection build with a signed driver."
                : "The bundled driver package is incomplete. Reinstall Reflection from a build that includes a signed driver.";
            return result;
        }
        const auto machine = driver_machine(result.directory / "ReflectionTablet.sys");
        if (machine != IMAGE_FILE_MACHINE_AMD64 && machine != IMAGE_FILE_MACHINE_ARM64) {
            result.state = "invalid";
            result.detail = "The bundled driver file is invalid. Obtain a complete signed Reflection driver package.";
            return result;
        }
        SYSTEM_INFO system{};
        GetNativeSystemInfo(&system);
        const WORD expected = system.wProcessorArchitecture == PROCESSOR_ARCHITECTURE_ARM64
            ? IMAGE_FILE_MACHINE_ARM64 : IMAGE_FILE_MACHINE_AMD64;
        if (machine != expected) {
            result.state = "wrong-architecture";
            result.detail = "The bundled driver targets a different processor. Obtain the Reflection driver for this PC.";
            return result;
        }
        for (const auto* name : {"ReflectionTablet.inf", "ReflectionTablet.sys"}) {
            // Catalogs may use SHA-1 member tags even when their signature is SHA-256.
            auto error = verify_member(result.directory / "ReflectionTablet.cat", result.directory / name, BCRYPT_SHA256_ALGORITHM);
            if (error != ERROR_SUCCESS) {
                const auto legacy = verify_member(result.directory / "ReflectionTablet.cat", result.directory / name, BCRYPT_SHA1_ALGORITHM);
                if (legacy == ERROR_SUCCESS) error = legacy;
            }
            if (error != ERROR_SUCCESS) {
                result.state = "untrusted";
                result.detail = "The bundled driver signature or catalog membership could not be verified. "
                    "Use a signed Reflection release. Verification of " + std::string(name) + " failed (" + trust_error(error) + ").";
                return result;
            }
        }
        result.available = true;
        result.state = "verified";
        result.detail = "Bundled driver signature and file integrity verified. Installation requires administrator approval and Windows policy checks.";
    } catch (const std::exception& e) {
        result.state = "unreadable";
        result.detail = "Cannot read the bundled driver package: " + std::string(e.what());
    }
    return result;
}

std::string DriverPackageStatus::json() const {
    return "{\"available\":" + std::string(available ? "true" : "false") +
        ",\"state\":" + json_escape(state) + ",\"detail\":" + json_escape(detail) +
        ",\"directory\":" + json_escape(directory.u8string()) + "}";
}
}
