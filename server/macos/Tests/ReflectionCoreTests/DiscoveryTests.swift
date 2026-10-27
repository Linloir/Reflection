import XCTest
import CryptoKit
import Security
@testable import ReflectionCore

final class DiscoveryTests: XCTestCase {
    private let token = String(repeating: "42", count: 32)

    func testDiscoveryRepliesAuthenticateNonceAndPortWithoutExposingToken() throws {
        let nonce = String(repeating: "ab", count: 16)
        let response = try XCTUnwrap(HostDiscoveryService.reply(
            request: "REFLECTION-DISCOVER/1 " + nonce, port: 24819, token: token))
        let fields = String(decoding: response, as: UTF8.self).components(separatedBy: "\n")
        XCTAssertEqual(fields.count, 5)
        XCTAssertEqual(fields[1], nonce)
        XCTAssertEqual(fields[2], "24819")
        XCTAssertFalse(String(decoding: response, as: UTF8.self).contains(token))
        let message = fields.prefix(4).joined(separator: "\n")
        let key = try WireProtocol.tokenKey(token)
        let expected = WireProtocol.mac(key: key, data: Data(message.utf8)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(fields[4], expected)
        let other = try XCTUnwrap(HostDiscoveryService.reply(
            request: "REFLECTION-DISCOVER/1 " + String(repeating: "cd", count: 16), port: 24819, token: token))
        XCTAssertNotEqual(response, other)
        XCTAssertNil(HostDiscoveryService.reply(request: "REFLECTION-DISCOVER/1 bad", port: 24819, token: token))
        XCTAssertNil(HostDiscoveryService.reply(request: "REFLECTION-DISCOVER/1 " + nonce + "\n", port: 24819, token: token))
    }

    func testPairingCredentialRequiresTabletPrivateKeyAndBindsNonce() throws {
        let attributes: [CFString: Any] = [kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeySizeInBits: 2048]
        let privateKey = try XCTUnwrap(SecKeyCreateRandomKey(attributes as CFDictionary, nil))
        let publicKey = try XCTUnwrap(SecKeyCopyPublicKey(privateKey))
        let encoded = try XCTUnwrap(SecKeyCopyExternalRepresentation(publicKey, nil)) as Data
        var cursor = 0
        let sequence = try readDER(encoded, cursor: &cursor, tag: 0x30)
        cursor = 0
        var modulus = try readDER(sequence, cursor: &cursor, tag: 0x02)
        if modulus.first == 0 { modulus.removeFirst() }
        let exponent = try readDER(sequence, cursor: &cursor, tag: 0x02)
        let nonce = String(repeating: "a3", count: 16)
        let config = ServerConfiguration(token: token)
        let ciphertext = try HostDiscoveryService.encryptToken(
            configuration: config, modulus: modulus, exponent: exponent, nonce: nonce)
        XCTAssertEqual(ciphertext.count, 256)
        let clear = try XCTUnwrap(SecKeyCreateDecryptedData(privateKey, .rsaEncryptionOAEPSHA256,
                                                          ciphertext as CFData, nil)) as Data
        let fields = String(decoding: clear, as: UTF8.self).components(separatedBy: "\n")
        XCTAssertEqual(fields[0], nonce)
        XCTAssertEqual(fields[2], String(config.port))
        XCTAssertEqual(fields[3], token)
        let otherPrivate = try XCTUnwrap(SecKeyCreateRandomKey(attributes as CFDictionary, nil))
        XCTAssertNil(SecKeyCreateDecryptedData(otherPrivate, .rsaEncryptionOAEPSHA256, ciphertext as CFData, nil))
    }

    private func readDER(_ data: Data, cursor: inout Int, tag: UInt8) throws -> Data {
        let bytes = Array(data)
        guard cursor + 2 <= bytes.count, bytes[cursor] == tag else { throw ReflectionError.invalid("Invalid test DER") }
        cursor += 1
        var length = Int(bytes[cursor]); cursor += 1
        if length & 0x80 != 0 {
            let count = length & 0x7f; length = 0
            for _ in 0..<count { length = length * 256 + Int(bytes[cursor]); cursor += 1 }
        }
        guard cursor + length <= bytes.count else { throw ReflectionError.invalid("Invalid test DER length") }
        defer { cursor += length }
        return Data(bytes[cursor..<cursor + length])
    }
}
