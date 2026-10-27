package io.github.linloir.reflection.control;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.SystemClock;
import io.github.linloir.reflection.core.PairingExchange;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Bonded-host pairing polls run independently of LAN discovery and pen transport. */
final class BluetoothPairingDiscovery implements Runnable {
    private final Context context;
    private final PairingIdentity identity;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "reflection-pairing-timeout"); thread.setDaemon(true); return thread;
    });
    private volatile boolean running;
    private Thread worker;
    private BluetoothSocket active;
    private final Map<String, Long> retryAt = new HashMap<>();
    private final Set<String> responsive = new HashSet<>();

    BluetoothPairingDiscovery(Context context, PairingIdentity identity) {
        this.context = context; this.identity = identity;
    }
    synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(this, "reflection-bluetooth-pairing"); worker.setDaemon(true); worker.start();
    }
    void stop() {
        BluetoothSocket socket;
        synchronized (this) {
            running = false; socket = active; active = null;
            if (worker != null) worker.interrupt();
        }
        close(socket); watchdog.shutdownNow();
    }
    @Override public void run() {
        try {
            while (running) {
                boolean attempted = false;
                try {
                    BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
                    if (adapter != null && adapter.isEnabled()) {
                        List<BluetoothDevice> devices = new ArrayList<>();
                        Set<String> present = new HashSet<>();
                        for (BluetoothDevice device : adapter.getBondedDevices()) {
                            if (devices.size() >= 128) break;
                            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                                devices.add(device); present.add(device.getAddress());
                            }
                        }
                        retryAt.keySet().retainAll(present); responsive.retainAll(present);
                        long now = SystemClock.elapsedRealtime();
                        BluetoothDevice chosen = null;
                        for (BluetoothDevice device : devices) {
                            String address = device.getAddress();
                            Long retry = retryAt.get(address);
                            if (retry != null && retry > now) continue;
                            if (chosen == null || responsive.contains(address)) chosen = device;
                            if (responsive.contains(address)) break;
                        }
                        if (chosen != null && running) {
                            String address = chosen.getAddress();
                            boolean found = probe(chosen); attempted = true;
                            if (found) responsive.add(address); else responsive.remove(address);
                            retryAt.put(address, SystemClock.elapsedRealtime() + (found ? 5000 : 30000));
                        }
                    }
                } catch (SecurityException unavailable) {
                    // Android 12+ permission is requested by the visible connection UI.
                } catch (RuntimeException unavailable) { /* Re-evaluate when Bluetooth state changes. */ }
                try { Thread.sleep(attempted ? 100 : 1000); } catch (InterruptedException ignored) {}
            }
        } finally { stop(); }
    }
    private boolean probe(BluetoothDevice device) {
        BluetoothSocket socket = null;
        ScheduledFuture<?> timeout = null;
        try {
            byte[] announcement = identity.announcement();
            socket = device.createRfcommSocketToServiceRecord(UUID.fromString(PairingExchange.SERVICE_UUID));
            synchronized (this) { if (!running) return false; active = socket; }
            final BluetoothSocket pending = socket;
            timeout = watchdog.schedule(() -> close(pending), 2500, TimeUnit.MILLISECONDS);
            socket.connect();
            byte[] reply = PairingExchange.exchange(new DataInputStream(socket.getInputStream()),
                new DataOutputStream(socket.getOutputStream()), announcement);
            if (reply != null && running) identity.consumeReply(reply, device.getAddress(), true);
            return true;
        } catch (Exception ignored) { return false; }
        finally {
            if (timeout != null) timeout.cancel(false);
            synchronized (this) { if (active == socket) active = null; }
            close(socket);
        }
    }
    private static void close(BluetoothSocket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }
}
