package io.github.linloir.reflection.transport;

import io.github.linloir.reflection.core.Wire;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stateless LAN discovery. Only hosts proving possession of the saved key become candidates. */
public final class HostDiscovery {
    public static final int PORT = 24820;
    public static final class Host {
        public final String address;
        public final String token;
        public final int port;
        public Host(String address, int port, String token) { this.address = address; this.port = port; this.token = token; }
    }

    public static List<Host> discover(String token) throws IOException {
        return discover(Collections.singletonList(token));
    }
    public static List<Host> discover(List<String> tokens) throws IOException {
        return discover(tokens, "");
    }
    public static List<Host> discover(List<String> tokens, String deviceID) throws IOException {
        List<Host> hosts = new ArrayList<>();
        if (tokens.isEmpty()) return hosts;
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String request = "REFLECTION-DISCOVER/1 " + hex(nonce) + (deviceID.isEmpty() ? "" : " " + deviceID);
        byte[] bytes = request.getBytes(StandardCharsets.US_ASCII);
        LinkedHashSet<InetAddress> destinations = new LinkedHashSet<>();
        destinations.add(InetAddress.getByName("255.255.255.255"));
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InterfaceAddress address : network.getInterfaceAddresses())
                if (address.getBroadcast() != null) destinations.add(address.getBroadcast());
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true); socket.setSoTimeout(180);
            for (InetAddress destination : destinations) {
                try { socket.send(new DatagramPacket(bytes, bytes.length, destination, PORT)); }
                catch (IOException ignored) { /* One blocked interface must not hide the others. */ }
            }
            long deadline = System.nanoTime() + 650_000_000L;
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            while (System.nanoTime() < deadline) {
                DatagramPacket reply = new DatagramPacket(new byte[512], 512);
                try { socket.receive(reply); } catch (SocketTimeoutException ignored) { continue; }
                String payload = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.US_ASCII);
                String[] fields = payload.split("\n", -1);
                if (fields.length != 5 || !"REFLECTION-HOST/1".equals(fields[0])
                        || !hex(nonce).equals(fields[1])) continue;
                try {
                    int port = Integer.parseInt(fields[2]);
                    if (port < 1 || port > 65535) continue;
                    String signed = fields[0] + "\n" + fields[1] + "\n" + fields[2] + "\n" + fields[3];
                    for (String token : tokens) {
                        Mac mac = Mac.getInstance("HmacSHA256");
                        mac.init(new SecretKeySpec(Wire.token(token), "HmacSHA256"));
                        if (!MessageDigest.isEqual(hex(mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)))
                                .getBytes(StandardCharsets.US_ASCII), fields[4].getBytes(StandardCharsets.US_ASCII))) continue;
                        String address = reply.getAddress().getHostAddress();
                        if (seen.add(address + ":" + port)) hosts.add(new Host(address, port, token));
                        break;
                    }
                } catch (java.security.GeneralSecurityException | IllegalArgumentException ignored) {}
            }
        }
        return hosts;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
