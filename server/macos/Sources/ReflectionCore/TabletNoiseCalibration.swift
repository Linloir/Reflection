import Foundation

public struct TabletNoiseCalibration {
    public let sequence: UInt64
    public let noise: Double

    public static func decode(_ payload: Data, after previous: UInt64) throws -> TabletNoiseCalibration {
        let bytes = [UInt8](payload)
        guard bytes.count == 12 else { throw ReflectionError.invalid("Invalid tablet calibration length") }
        let sequence = WireProtocol.uint64(bytes, 0)
        let noise = Float(bitPattern: WireProtocol.uint32(bytes, 8))
        guard sequence > previous, previous != 0 || sequence == 1,
              noise.isFinite, noise == 0 || (Float(0.00001)...Float(0.02)).contains(noise) else {
            throw ReflectionError.invalid("Invalid or replayed tablet calibration")
        }
        return TabletNoiseCalibration(sequence: sequence, noise: noise == 0 ? 0 : min(0.02, max(0.00001, Double(noise))))
    }

    public static func acknowledgement(sequence: UInt64, noise: Double) -> Data {
        var result = Data([1])
        for shift in stride(from: 56, through: 0, by: -8) { result.append(UInt8(truncatingIfNeeded: sequence >> shift)) }
        let bits = Float(noise).bitPattern
        for shift in stride(from: 24, through: 0, by: -8) { result.append(UInt8(truncatingIfNeeded: bits >> shift)) }
        return result
    }
}
