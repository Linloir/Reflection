/// Bounds data accepted by an asynchronous transport before write completion.
/// A reservation from a closed session cannot change a new session's budget.
public struct OutboundSendBudget {
    public struct Reservation {
        fileprivate let bytes: Int
        fileprivate let generation: UInt64
    }

    public let limit: Int
    public private(set) var pendingBytes = 0
    private var generation: UInt64 = 0

    public init(limit: Int = 65536) {
        precondition(limit > 0)
        self.limit = limit
    }

    public mutating func resetSession() {
        generation &+= 1
        pendingBytes = 0
    }

    public mutating func reserve(_ bytes: Int) throws -> Reservation {
        guard bytes >= 0, bytes <= limit - pendingBytes else {
            throw ReflectionError.invalid("Outbound send budget exceeded")
        }
        pendingBytes += bytes
        return Reservation(bytes: bytes, generation: generation)
    }

    public mutating func complete(_ reservation: Reservation) {
        guard reservation.generation == generation else { return }
        pendingBytes = max(0, pendingBytes - reservation.bytes)
    }
}
