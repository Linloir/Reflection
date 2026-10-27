package io.github.linloir.reflection.control;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import io.github.linloir.reflection.transport.HostDiscovery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One discovery worker; active transports keep their own bounded reconnect/heartbeat loop. */
final class AutomaticConnection implements Runnable {
    private final Controller controller;
    private final Context context;
    private final PairingIdentity identity;
    private final BluetoothPairingDiscovery bluetoothPairing;
    private volatile boolean running;
    private Thread worker;
    private final Map<String, Candidate> lan = new LinkedHashMap<>();
    private final Map<String, Long> retryAfter = new LinkedHashMap<>();
    private Candidate current;
    private long attemptedAt, lastDiscovery;
    private long failedAttemptAt = Long.MIN_VALUE;
    private int fallbackIndex;
    private String lastPreferred = "";

    private static final class Candidate {
        final String mode, address, token;
        final int port, priority;
        long seen;
        Candidate(String mode, String address, int port, int priority, long seen, String token) {
            this.mode = mode; this.address = address; this.port = port; this.priority = priority; this.seen = seen; this.token = token;
        }
        String key() { return mode + ":" + address + ":" + port + ":" + token; }
    }

    AutomaticConnection(Context context, Controller controller) {
        this.context = context; this.controller = controller; this.identity = controller.pairingIdentity();
        bluetoothPairing = new BluetoothPairingDiscovery(context, identity);
    }
    synchronized void start() {
        if (running) return;
        running = true; bluetoothPairing.start();
        worker = new Thread(this, "reflection-discovery"); worker.setDaemon(true); worker.start();
    }
    synchronized void stop() { running = false; bluetoothPairing.stop(); if (worker != null) worker.interrupt(); }
    @Override public void run() {
        try {
            while (running) {
                try { cycle(); } catch (Exception ignored) { /* Re-evaluate after network and permission changes. */ }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        } finally {
            bluetoothPairing.stop();
            if (identity != null) identity.close();
        }
    }
    private void cycle() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastDiscovery >= 4000) {
            lastDiscovery = now;
            try { identity.announceAndReceive(); } catch (Exception ignored) {}
            try {
                List<String> tokens = new ArrayList<>();
                for (Controller.TrustedHost host : controller.trustedHosts()) tokens.add(host.token);
                for (HostDiscovery.Host host : HostDiscovery.discover(tokens, identity.identifier())) {
                    Candidate candidate = new Candidate("tcp", host.address, host.port, 2, now, host.token);
                    lan.put(candidate.key(), candidate);
                }
            } catch (Exception ignored) {}
        } else try { identity.receive(); } catch (Exception ignored) {}
        if (controller.token().isEmpty()) {
            controller.setAutomaticStatus("正在发现 · 在电脑上选择此设备 · 验证码 " + controller.pairingCode()); return;
        }
        String preferred = controller.preferredHostToken();
        boolean selectionChanged = !preferred.equals(lastPreferred);
        if (selectionChanged) {
            lastPreferred = preferred; fallbackIndex = 0; attemptedAt = 0; retryAfter.clear();
        }
        List<Candidate> candidates = new ArrayList<>();
        boolean connected = controller.connected();
        if (controller.usbLeaseFresh()) candidates.add(new Candidate("tcp", "127.0.0.1", 24819, 3, now, controller.usbToken()));
        for (java.util.Iterator<Candidate> iterator = lan.values().iterator(); iterator.hasNext();) {
            Candidate candidate = iterator.next();
            if (now - candidate.seen > 16000) iterator.remove(); else candidates.add(candidate);
        }
        for (Controller.TrustedHost host : controller.trustedHosts())
            if (!connected && !host.address.startsWith("bluetooth:") && !host.address.equals("127.0.0.1") && !host.address.equals("localhost"))
                candidates.add(new Candidate("tcp", host.address, host.port, 2, now, host.token));
        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter != null && adapter.isEnabled()) {
                java.util.Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                for (Controller.TrustedHost host : controller.trustedHosts()) {
                    for (BluetoothDevice device : bonded)
                        if (host.address.equals("bluetooth:" + device.getAddress()))
                            candidates.add(new Candidate("bluetooth", device.getAddress(), 0, 1, now, host.token));
                    for (BluetoothDevice device : bonded)
                        if (!host.address.equals("bluetooth:" + device.getAddress()))
                        candidates.add(new Candidate("bluetooth", device.getAddress(), 0, 1, now, host.token));
                }
            }
        } catch (SecurityException ignored) {}
        // A manually provisioned legacy USB profile can reconnect without a lease.
        if (!connected && candidates.isEmpty() && controller.host().equals("127.0.0.1"))
            candidates.add(new Candidate("tcp", controller.host(), controller.port(), 3, now, controller.token()));
        if (!selectionChanged && !connected && current != null && now - attemptedAt >= 7000 && failedAttemptAt != attemptedAt) {
            retryAfter.put(current.key(), now + 30000);
            failedAttemptAt = attemptedAt;
        }
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            Long cooldown = retryAfter.get(candidate.key());
            if (cooldown == null || now >= cooldown) unique.put(candidate.key(), candidate);
        }
        candidates = new ArrayList<>(unique.values());
        if (candidates.isEmpty()) {
            controller.setAutomaticStatus(connected && current != null ? label(current) + " · 已连接" : "正在发现宿主机 · USB / Wi-Fi / 蓝牙"); return;
        }
        java.util.Collections.sort(candidates, (a, b) -> {
            int preference = Boolean.compare(b.token.equals(preferred), a.token.equals(preferred));
            return preference != 0 ? preference : Integer.compare(b.priority, a.priority);
        });
        Candidate chosen = candidates.get(0);
        if (connected && current != null) {
            controller.setAutomaticStatus(label(current) + " · 已连接");
            boolean requestedHost = chosen.token.equals(preferred) && !current.token.equals(preferred);
            if ((!requestedHost && (!chosen.token.equals(current.token) || chosen.priority <= current.priority))
                    || controller.penContact() || now - attemptedAt < 5000) return;
        } else if (current != null && now - attemptedAt < 7000) {
            controller.setAutomaticStatus(label(current) + " · 正在连接");
            return;
        } else if (current != null && !selectionChanged) {
            // Give every interface and bonded host a chance when a preferred endpoint is unreachable.
            chosen = candidates.get((++fallbackIndex) % candidates.size());
        }
        if (!running || controller.penContact()) return;
        controller.connectAutomatically(this, chosen.mode, chosen.address, chosen.port, chosen.token);
        current = chosen; attemptedAt = now;
        controller.setAutomaticStatus(label(chosen) + " · 正在连接");
    }
    private static String label(Candidate candidate) {
        return candidate.priority == 3 ? "USB" : candidate.priority == 2 ? "Wi-Fi" : "蓝牙";
    }
}
