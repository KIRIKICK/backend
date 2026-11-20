package org.kirikick.api.publication.phase1;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only PostgreSQL relay; the JDBC observer never reads the withheld server outcome. */
final class NkCreateCommitWire implements AutoCloseable {
  enum Loss {
    NONE,
    COMMIT_REPLY,
    FLUSH_REPLY
  }

  private final ServerSocket listener;
  private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicReference<Throwable> fault = new AtomicReference<>();
  private final Loss loss;
  private volatile boolean armed;
  private volatile boolean dispatched;
  private volatile char withheld;
  private volatile boolean executed;

  NkCreateCommitWire(String host, int port, Loss loss) throws IOException {
    this.loss = loss;
    listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    tasks.execute(() -> {
      while (!listener.isClosed()) {
        try {
          var client = listener.accept();
          tasks.execute(() -> bridge(client, host, port));
        } catch (IOException e) {
          if (!listener.isClosed()) {
            fault.compareAndSet(null, e);
          }
        }
      }
    });
  }

  int port() {
    return listener.getLocalPort();
  }

  void arm() {
    armed = true;
  }

  boolean commitDispatched() {
    return dispatched;
  }

  char withheldForAuditOnly() {
    return withheld;
  }

  boolean targetExecuted() {
    return executed;
  }

  void requireHealthy() {
    if (fault.get() != null) {
      throw new AssertionError(
          "Test transport could not observe the actual driver protocol", fault.get());
    }
  }

  private void bridge(Socket client, String host, int port) {
    try (client;
        var upstream = new Socket()) {
      upstream.connect(new InetSocketAddress(host, port), 5000);
      client.setSoTimeout(12000);
      upstream.setSoTimeout(12000);
      var fromClient = new DataInputStream(client.getInputStream());
      var toClient = new DataOutputStream(client.getOutputStream());
      var fromPg = new DataInputStream(upstream.getInputStream());
      var toPg = new DataOutputStream(upstream.getOutputStream());
      try {
        startup(fromClient, toClient, fromPg, toPg);
        var exchange = new Exchange();
        tasks.execute(() -> replies(client, upstream, fromPg, toClient, exchange));
        requests(fromClient, toPg, exchange);
      } catch (IOException | RuntimeException e) {
        if (withheld == 0 && !client.isClosed()) {
          fault.compareAndSet(null, e);
        }
      }
    } catch (IOException | RuntimeException e) {
      fault.compareAndSet(null, e);
    }
  }

  private static void startup(
      DataInputStream client, DataOutputStream toClient, DataInputStream pg, DataOutputStream toPg)
      throws IOException {
    while (true) {
      var bytes = body(client);
      toPg.writeInt(bytes.length + 4);
      toPg.write(bytes);
      toPg.flush();
      if (bytes.length != 4) {
        return;
      }
      // Let the real driver negotiate; this fixture requires an unencrypted local PG connection.
      int response = pg.readUnsignedByte();
      toClient.writeByte(response);
      toClient.flush();
      if (response != 'N') {
        throw new IllegalStateException(
            "Fixture negotiated non-plaintext PostgreSQL; no wire proof");
      }
    }
  }

  private void requests(DataInputStream from, DataOutputStream to, Exchange exchange)
      throws IOException {
    int type;
    while ((type = from.read()) != -1) {
      var bytes = body(from);
      if (armed && (type == 'P' || type == 'Q')) {
        String query = query(type, bytes);
        boolean commit = query.equalsIgnoreCase("COMMIT");
        boolean flush =
            loss == Loss.FLUSH_REPLY && query.toLowerCase(Locale.ROOT).startsWith("update rooms ");
        if (commit || flush) {
          if (type != 'P') {
            throw new IllegalStateException(
                "Expected default pgjdbc extended protocol for the target");
          }
          exchange.kind = commit ? Kind.COMMIT : Kind.FLUSH;
          exchange.expectedFrames = commit ? "PBES" : "PBDES";
          exchange.frames = 1;
        }
      } else if (exchange.frames > 0 && exchange.expectedFrames == null) {
        throw new IllegalStateException("JDBC target frame sequence was not initialized");
      } else if (exchange.frames > 0 && exchange.frames < exchange.expectedFrames.length()) {
        if (type != exchange.expectedFrames.charAt(exchange.frames)) {
          throw new IllegalStateException(
              "Unexpected frontend frame in JDBC target: " + (char) type);
        }
        exchange.frames++;
      }
      boolean commitSync =
          type == 'S'
              && exchange.kind == Kind.COMMIT
              && exchange.expectedFrames != null
              && exchange.frames == exchange.expectedFrames.length();
      frame(to, type, bytes);
      if (commitSync) {
        dispatched = true;
      }
      if (type == 'X') {
        return;
      }
    }
  }

  private void replies(
      Socket client,
      Socket upstream,
      DataInputStream from,
      DataOutputStream to,
      Exchange exchange) {
    try {
      int type;
      while ((type = from.read()) != -1) {
        var bytes = body(from);
        Kind kind = exchange.kind;
        if (kind != null) {
          if (type == '1') {
            exchange.parseComplete = true;
          }
          if (type == '2') {
            exchange.bindComplete = true;
          }
          if (type == 'C' || type == 'E') {
            if (exchange.frames != exchange.expectedFrames.length()
                || !exchange.parseComplete
                || !exchange.bindComplete) {
              throw new IllegalStateException(
                  "Target did not reach PostgreSQL as a complete extended query");
            }
            executed = true;
            if ((kind == Kind.COMMIT && loss == Loss.COMMIT_REPLY) || kind == Kind.FLUSH) {
              withheld = (char) type;
              client.close();
              upstream.close();
              return;
            }
            if (kind == Kind.COMMIT && type == 'C' && !commandTag(bytes).equals("COMMIT")) {
              throw new IllegalStateException("Backend command tag is not COMMIT");
            }
            exchange.kind = null;
            exchange.parseComplete = false;
            exchange.bindComplete = false;
            frame(to, type, bytes);
            continue;
          }
        }
        frame(to, type, bytes);
      }
    } catch (IOException | RuntimeException e) {
      if (withheld == 0 && !client.isClosed() && !upstream.isClosed()) {
        fault.compareAndSet(null, e);
      }
    }
  }

  private static String query(int type, byte[] bytes) {
    int start = type == 'P' ? end(bytes, 0) + 1 : 0;
    return new String(bytes, start, end(bytes, start) - start, StandardCharsets.UTF_8);
  }

  private static String commandTag(byte[] bytes) {
    return new String(bytes, 0, end(bytes, 0), StandardCharsets.UTF_8);
  }

  private static int end(byte[] bytes, int start) {
    int i = start;
    while (i < bytes.length && bytes[i] != 0) {
      i++;
    }
    return i;
  }

  private static byte[] body(DataInputStream input) throws IOException {
    int size = input.readInt() - 4;
    if (size < 0 || size > 1024 * 1024) {
      throw new IOException("Unexpected frame length in isolated PostgreSQL fixture");
    }
    byte[] bytes = input.readNBytes(size);
    if (bytes.length != size) {
      throw new EOFException("Truncated PostgreSQL frame");
    }
    return bytes;
  }

  private static void frame(DataOutputStream out, int type, byte[] bytes) throws IOException {
    out.writeByte(type);
    out.writeInt(bytes.length + 4);
    out.write(bytes);
    out.flush();
  }

  @Override
  public void close() throws IOException {
    listener.close();
    tasks.close();
  }

  private enum Kind {
    COMMIT,
    FLUSH
  }

  private static final class Exchange {
    private volatile Kind kind;
    private volatile int frames;
    private volatile String expectedFrames;
    private volatile boolean parseComplete;
    private volatile boolean bindComplete;
  }
}
