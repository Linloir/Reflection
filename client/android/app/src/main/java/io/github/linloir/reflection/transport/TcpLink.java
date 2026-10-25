package io.github.linloir.reflection.transport;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class TcpLink implements DuplexLink {
    private final Socket socket = new Socket();
    private final String host;
    private final int port;
    public TcpLink(String host, int port) { this.host = host; this.port = port; }
    @Override public void connect() throws IOException {
        socket.setTcpNoDelay(true); socket.setKeepAlive(true);
        socket.connect(new InetSocketAddress(host, port), 3000);
    }
    @Override public InputStream input() throws IOException { return socket.getInputStream(); }
    @Override public OutputStream output() throws IOException { return socket.getOutputStream(); }
    @Override public void setReadTimeout(int millis) throws IOException { socket.setSoTimeout(millis); }
    @Override public boolean isClosed() { return socket.isClosed(); }
    @Override public void close() throws IOException { socket.close(); }
}
