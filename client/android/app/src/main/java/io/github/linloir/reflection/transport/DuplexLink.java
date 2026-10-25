package io.github.linloir.reflection.transport;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

/** A byte stream: protocol framing and authentication are transport independent. */
public interface DuplexLink extends Closeable {
    interface Factory { DuplexLink create() throws IOException; }
    void connect() throws IOException;
    InputStream input() throws IOException;
    OutputStream output() throws IOException;
    void setReadTimeout(int millis) throws IOException;
    boolean isClosed();
}
