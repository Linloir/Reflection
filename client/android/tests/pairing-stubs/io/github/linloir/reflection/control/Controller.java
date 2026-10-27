package io.github.linloir.reflection.control;

/** Records deliveries; trust decisions and production controller state are outside this crypto test. */
public final class Controller {
    public int deliveries, port;
    public boolean bluetooth;
    public String token, address, nonce;
    public synchronized void requestHostTrust(PairingIdentity owner, String token, String address, int port, String nonce) {
        deliveries++; bluetooth = false; this.token = token; this.address = address; this.port = port; this.nonce = nonce;
    }
    public synchronized void requestBluetoothHostTrust(PairingIdentity owner, String token, String address, String nonce) {
        requestHostTrust(owner, token, address, 24819, nonce); bluetooth = true;
    }
}
