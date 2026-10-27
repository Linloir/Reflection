import io.github.linloir.reflection.core.Wire;
import io.github.linloir.reflection.core.TouchpadEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/** Exercises the real macOS host with independent tablet identities, without injecting input. */
public final class PairingIntegrationProbe {
    private static String executable, config;
    private static final class Tablet implements AutoCloseable {
        final KeyPair keys;
        final DatagramSocket socket = new DatagramSocket();
        final String id, nonce;
        Tablet() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            keys = generator.generateKeyPair();
            id = hex(MessageDigest.getInstance("SHA-256").digest(modulus())).substring(0, 32);
            byte[] random = new byte[16]; new SecureRandom().nextBytes(random); nonce = hex(random);
            socket.setSoTimeout(1500);
        }
        byte[] modulus() {
            byte[] value = ((RSAPublicKey) keys.getPublic()).getModulus().toByteArray();
            return value[0] == 0 ? Arrays.copyOfRange(value, 1, value.length) : value;
        }
        String announcement() {
            return "REFLECTION-DEVICE/1\n" + id + "\n" + base64("Integration tablet".getBytes(StandardCharsets.UTF_8))
                + "\n" + base64(modulus()) + "\nAQAB\n" + nonce;
        }
        void announce() throws Exception { send(announcement()); }
        void send(String message) throws Exception {
            byte[] bytes = message.getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), 24820));
        }
        String receive() throws Exception {
            DatagramPacket packet = new DatagramPacket(new byte[2048], 2048); socket.receive(packet);
            return new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII);
        }
        String approve() throws Exception {
            String output = command("pairing", "trust", id);
            check(output.contains("approvalSent"), "host approval failed");
            return decrypt(receive(), output);
        }
        String decrypt(String reply, String output) throws Exception {
            String[] message = reply.split("\n");
            check(message.length == 3 && message[0].equals("REFLECTION-PAIR/1") && message[1].equals(nonce), "invalid approval envelope");
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.getPrivate(),
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            String[] clear = new String(cipher.doFinal(Base64.getDecoder().decode(message[2])), StandardCharsets.US_ASCII).split("\n");
            check(clear.length == 4 && clear[0].equals(nonce), "approval nonce mismatch");
            byte[] digest = mac(clear[3], "Reflection/pair-confirm/v1\n" + nonce);
            String code = String.format("%06d", ((digest[0] & 255) << 16 | (digest[1] & 255) << 8 | (digest[2] & 255)) % 1000000);
            check(output.contains(code), "mutual confirmation code mismatch");
            return clear[3];
        }
        String bootstrap(int port) throws Exception {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(1500);
                Wire.write(new DataOutputStream(socket.getOutputStream()), 0x04,
                    announcement().getBytes(StandardCharsets.UTF_8), null);
                byte[] response = Wire.read(new DataInputStream(socket.getInputStream()));
                check(response[1] == 0x05 && response.length <= 1200, "invalid bootstrap response");
                return new String(response, 2, response.length - 2, StandardCharsets.US_ASCII);
            }
        }
        @Override public void close() { socket.close(); }
    }
    public static void main(String[] arguments) throws Exception {
        executable = arguments[0];
        Path directory = Files.createTempDirectory("reflection-pairing-test-");
        Path configuration = directory.resolve("server.json"), log = directory.resolve("host.log");
        config = configuration.toString();
        String rootToken = String.join("", Collections.nCopies(32, "24"));
        int port;
        try (ServerSocket reserve = new ServerSocket(0)) { port = reserve.getLocalPort(); }
        Files.write(configuration, ("{\"version\":1,\"bind\":\"127.0.0.1\",\"port\":" + port + ",\"token\":\"" + rootToken + "\"}").getBytes(StandardCharsets.US_ASCII));
        Process host = null;
        boolean passed = false;
        try (Tablet first = new Tablet(); Tablet second = new Tablet(); Tablet bluetooth = new Tablet()) {
            host = new ProcessBuilder(executable, "--config", config, "start", "--dry-run")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean listed = false;
            for (int i = 0; i < 30 && !listed; i++) {
                first.announce(); second.announce();
                try { listed = command("pairing", "list").contains(first.id); }
                catch (Exception | AssertionError ignored) {}
                if (!listed) Thread.sleep(100);
            }
            check(listed, "tablets were not discovered; UDP 24820 must be free");
            int bootstrapPort = endpoint();
            check(bootstrapPort > 0, "Bluetooth pairing relay did not start");
            String firstToken = first.approve();
            check(firstToken.equals(derived(rootToken, first.id)) && !firstToken.equals(rootToken), "per-tablet key derivation mismatch");
            first.announce();
            check(first.receive().startsWith("REFLECTION-PAIR/1\n" + first.nonce + "\n"), "lost UDP offer was not retried");
            first.send("REFLECTION-DISCOVER/1 " + first.nonce + " " + first.id);
            String reply = first.receive();
            String signed = reply.substring(0, reply.lastIndexOf('\n'));
            check(reply.endsWith(hex(mac(firstToken, signed))), "discovery signature mismatch");
            try (Socket active = authenticate(port, firstToken)) {
                String secondToken = second.approve();
                check(!secondToken.equals(firstToken), "tablet credentials must be independent");
                active.setSoTimeout(1000);
                check(active.getInputStream().read() == -1, "selecting another tablet did not close the previous session");
                reject(port, firstToken);
                reject(port, rootToken);
                String bluetoothToken;
                try (Socket accepted = authenticate(port, secondToken)) {
                    check(bluetooth.bootstrap(bootstrapPort).equals("REFLECTION-PENDING/1"), "new Bluetooth tablet was not pending");
                    check(command("pairing", "list").contains(bluetooth.id), "Bluetooth-only tablet was not discoverable");
                    assertStillConnected(accepted);
                    rejectBootstrap(bootstrapPort, 1201, new byte[0]);
                    rejectBootstrap(bootstrapPort, 2, new byte[] {2, 4});
                    rejectBootstrap(bootstrapPort, 2, new byte[] {1, 9});
                    check(bluetooth.bootstrap(bootstrapPort).equals("REFLECTION-PENDING/1"), "malformed client broke bootstrap service");
                    String confirmation = command("pairing", "trust", bluetooth.id);
                    String approval = bluetooth.bootstrap(bootstrapPort);
                    bluetoothToken = bluetooth.decrypt(approval, confirmation);
                    check(bluetoothToken.equals(derived(rootToken, bluetooth.id)), "Bluetooth credential derivation mismatch");
                    bluetooth.announce();
                    check(bluetooth.receive().equals(approval), "approval did not survive switching discovery transports");
                    check(bluetooth.bootstrap(bootstrapPort).equals(approval), "Bluetooth poll lost cached approval");
                    accepted.setSoTimeout(1000);
                    check(accepted.getInputStream().read() == -1, "Bluetooth trust did not hand off the active session");
                }
                reject(port, secondToken);
                try (Socket connected = authenticate(port, bluetoothToken)) {
                    check(connected.isConnected(), "Bluetooth-only paired credential was not accepted");
                }
                String trusted = new String(Files.readAllBytes(directory.resolve("server.trusted-tablets.json")), StandardCharsets.UTF_8);
                check(trusted.contains(first.id) && trusted.contains(second.id) && trusted.contains(bluetooth.id), "trusted identities were overwritten");
                check(!trusted.contains(firstToken) && !trusted.contains(rootToken), "trust registry exposed credentials");
                command("stop"); host.waitFor(5, TimeUnit.SECONDS); host = null;
                host = new ProcessBuilder(executable, "--config", config, "start", "--dry-run")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
                Thread.sleep(250);
                try (Socket acceptedAgain = authenticate(port, firstToken)) { check(acceptedAgain.isConnected(), "first tablet lost trust after restart"); }
                Thread.sleep(100);
                try (Socket acceptedAgain = authenticate(port, secondToken)) { check(acceptedAgain.isConnected(), "second tablet lost trust after restart"); }
                Thread.sleep(100);
                try (Socket acceptedAgain = authenticate(port, bluetoothToken)) { check(acceptedAgain.isConnected(), "Bluetooth tablet lost trust after restart"); }
            }
            System.out.println("Pairing integration: LAN and Bluetooth-bootstrap discovery, RSA-OAEP, confirmation codes, cached retry, malformed frame rejection, active-session isolation, handoff, persisted multi-tablet trust, and macOS raw-capability fallback passed");
            passed = true;
        } finally {
            if (host != null) {
                try { command("stop"); } catch (Exception | AssertionError ignored) {}
                if (!host.waitFor(3, TimeUnit.SECONDS)) host.destroyForcibly();
            }
            if (!passed && Files.exists(log)) System.err.println(new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
            try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
            }
        }
    }
    private static int endpoint() throws Exception {
        String output = command("pairing", "endpoint");
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("\"loopbackPort\"\\s*:\\s*(\\d+)").matcher(output);
        check(match.find(), "missing Bluetooth bootstrap endpoint");
        return Integer.parseInt(match.group(1));
    }
    private static void assertStillConnected(Socket socket) throws Exception {
        socket.setSoTimeout(200);
        try { check(socket.getInputStream().read() != -1, "pending pairing interrupted active input"); }
        catch (SocketTimeoutException expected) {}
    }
    private static void rejectBootstrap(int port, int length, byte[] body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(1500);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(length); output.write(body); output.flush();
            check(socket.getInputStream().read() == -1, "malformed bootstrap frame was accepted");
        }
    }
    private static Socket authenticate(int port, String token) throws Exception {
        Socket socket = new Socket("127.0.0.1", port); socket.setSoTimeout(1500);
        try {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            byte[] session = Wire.authenticate(input, output, Wire.token(token));
            Wire.write(output, 0x20, ("{\"touchpad\":1,\"rawTouchpad\":1,\"rawTouchpadWidth\":10000,\"rawTouchpadHeight\":6000}")
                .getBytes(StandardCharsets.US_ASCII), session);
            byte[] acknowledgement = Wire.verify(Wire.read(input), session);
            Wire.require(acknowledgement, 0x24, 3);
            check(acknowledgement[2] == 1, "macOS compatibility touchpad was not negotiated");
            Wire.write(output, 0x23, Wire.touchpad(new TouchpadEvent(TouchpadEvent.CANCEL, 0, 0, 0, 1), 1, 1), session);
            Wire.write(output, 0x11, java.nio.ByteBuffer.allocate(8).putLong(1).array(), session);
            Wire.require(Wire.verify(Wire.read(input), session), 0x12, 10);
            return socket;
        } catch (Exception error) { socket.close(); throw error; }
    }
    private static void reject(int port, String token) throws Exception {
        try (Socket ignored = authenticate(port, token)) { throw new AssertionError("unselected credential authenticated"); }
        catch (IOException expected) {}
    }
    private static String command(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList(executable, "--config", config));
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int count;
        while ((count = process.getInputStream().read(bytes)) >= 0) output.write(bytes, 0, count);
        check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0, "host command failed: " + output);
        return output.toString("UTF-8");
    }
    private static String derived(String root, String id) throws Exception { return hex(mac(root, "Reflection/device/v1\n" + id)); }
    private static byte[] mac(String token, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(Wire.token(token), "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.US_ASCII));
    }
    private static String base64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(); for (byte item : bytes) value.append(String.format("%02x", item & 255)); return value.toString();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
