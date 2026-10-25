import Foundation
import CryptoKit

public enum WireProtocol {
    public static let version: UInt8 = 1
    public static let maximumBodyLength = 4096

    public static func tokenKey(_ token: String) throws -> SymmetricKey {
        guard token.count == 64 else { throw ReflectionError.invalid("Invalid token length") }
        var bytes = [UInt8]()
        var i = token.startIndex
        while i != token.endIndex {
            let next = token.index(i, offsetBy: 2)
            guard let byte = UInt8(token[i..<next], radix: 16) else {
                throw ReflectionError.invalid("Invalid hexadecimal token")
            }
            bytes.append(byte); i = next
        }
        return SymmetricKey(data: bytes)
    }

    public static func mac(key: SymmetricKey, data: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: key))
    }

    public static func verify(key: SymmetricKey, data: Data, tag: Data) -> Bool {
        HMAC<SHA256>.isValidAuthenticationCode(tag, authenticating: data, using: key)
    }

    public static func frame(type: UInt8, payload: Data, key: SymmetricKey? = nil) -> Data {
        var body = Data([version, type])
        body.append(payload)
        if let key { body.append(mac(key: key, data: body)) }
        var size = UInt32(body.count).bigEndian
        var output = Data(bytes: &size, count: 4)
        output.append(body)
        return output
    }

    public static func decodeSample(_ payload: Data) throws -> PenSample {
        let bytes = [UInt8](payload)
        guard bytes.count == 48, bytes[46] == 0, bytes[47] == 0,
              let phase = PenPhase(rawValue: bytes[44]), let tool = PenTool(rawValue: bytes[45]) else {
            throw ReflectionError.invalid("Invalid sample size, reserved bits, phase, or tool")
        }
        let x = Double(Float(bitPattern: uint32(bytes, 16)))
        let y = Double(Float(bitPattern: uint32(bytes, 20)))
        let pressure = Double(Float(bitPattern: uint32(bytes, 24)))
        let tiltX = Double(Float(bitPattern: uint32(bytes, 28)))
        let tiltY = Double(Float(bitPattern: uint32(bytes, 32)))
        let distance = Double(Float(bitPattern: uint32(bytes, 36)))
        guard [x, y, pressure, distance].allSatisfy({ $0.isFinite && (0...1).contains($0) }),
              [tiltX, tiltY].allSatisfy({ $0.isFinite && (-90...90).contains($0) }),
              uint32(bytes, 40) & ~UInt32(3) == 0 else {
            throw ReflectionError.invalid("Invalid sample value")
        }
        return PenSample(sequence: uint64(bytes, 0), timestampNanoseconds: uint64(bytes, 8),
            phase: phase, tool: tool, buttons: uint32(bytes, 40), x: x, y: y,
            pressure: pressure, tiltX: tiltX, tiltY: tiltY, distance: distance)
    }

    static func uint32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        bytes[offset..<offset + 4].reduce(0) { ($0 << 8) | UInt32($1) }
    }

    static func uint64(_ bytes: [UInt8], _ offset: Int) -> UInt64 {
        bytes[offset..<offset + 8].reduce(0) { ($0 << 8) | UInt64($1) }
    }
}

public struct FrameDecoder {
    private var buffer = Data()
    public init() {}

    public mutating func append(_ data: Data) throws -> [Data] {
        buffer.append(data)
        var frames = [Data]()
        while buffer.count >= 4 {
            let length = Int(WireProtocol.uint32(Array(buffer.prefix(4)), 0))
            guard (2...WireProtocol.maximumBodyLength).contains(length) else {
                throw ReflectionError.invalid("Invalid frame length")
            }
            guard buffer.count >= length + 4 else { break }
            let body = Data(buffer.dropFirst(4).prefix(length))
            guard body.first == WireProtocol.version else {
                throw ReflectionError.invalid("Unsupported protocol version")
            }
            frames.append(body)
            buffer.removeFirst(length + 4)
        }
        guard buffer.count <= WireProtocol.maximumBodyLength + 4 else {
            throw ReflectionError.invalid("Oversize frame buffer")
        }
        return frames
    }
}
