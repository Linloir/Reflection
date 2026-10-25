package io.github.linloir.reflection.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.UUID;

/** Secure Classic Bluetooth RFCOMM; pairing is delegated to Android settings. */
public final class BluetoothLink implements DuplexLink {
    public static final UUID SERVICE_UUID = UUID.fromString("7b011e5a-8b31-4c40-8d7a-3e6c6dcbb758");
    private final BluetoothSocket socket;
    private volatile boolean closed;
    public BluetoothLink(BluetoothAdapter adapter, String address) throws IOException {
        if (adapter == null || !adapter.isEnabled()) throw new IOException("Enable Bluetooth in Android settings");
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw new IOException("Invalid Bluetooth device address");
        BluetoothDevice device = adapter.getRemoteDevice(address);
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) throw new IOException("Pair the host in Android Bluetooth settings first");
        socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
    }
    @Override public void connect() throws IOException { socket.connect(); }
    @Override public InputStream input() throws IOException { return socket.getInputStream(); }
    @Override public OutputStream output() throws IOException { return socket.getOutputStream(); }
    @Override public void setReadTimeout(int millis) { /* The session watchdog closes blocked Bluetooth I/O. */ }
    @Override public boolean isClosed() { return closed; }
    @Override public void close() throws IOException { closed = true; socket.close(); }
}
