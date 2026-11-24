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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only plaintext PostgreSQL relay; observations never enter product classification. */
final class NkCreateCommitWire implements AutoCloseable {
  enum Loss {
    NONE,
    COMMIT_REPLY,
    FLUSH_REPLY,
    STATEMENT_REPLY
  }

  record WireEvent(
      long nanoTime,
      int pid,
      long exchange,
      String event,
      String sql,
      String backendType,
      String detail) {}

  private static final int ALL_CONNECTIONS = 0;
  private static final long NO_EXCHANGE = -1L;
  private final ServerSocket listener;
  private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final AtomicReference<Throwable> fault = new AtomicReference<>();
  private final List<WireEvent> events = Collections.synchronizedList(new ArrayList<>());
  private final AtomicLong connections = new AtomicLong();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile Loss loss;
  private volatile boolean armed;
  private volatile int armedPid = ALL_CONNECTIONS;
  private volatile boolean dispatched;
  private volatile char withheld;
  private volatile boolean executed;

  NkCreateCommitWire(String host, int port, Loss loss) throws IOException {
    this.loss = loss;
    listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    tasks.execute(() -> accept(host, port));
  }

  int port() {
    return listener.getLocalPort();
  }

  void arm() {
    arm(ALL_CONNECTIONS, loss);
  }

  void arm(Loss requestedLoss) {
    arm(ALL_CONNECTIONS, requestedLoss);
  }

  /**
   * Arms one physical PostgreSQL backend PID; zero preserves legacy all-connection test behavior.
   */
  void arm(int pid, Loss requestedLoss) {
    if (pid < ALL_CONNECTIONS)
      throw new IllegalArgumentException("PostgreSQL PID must be non-negative");
    loss = requestedLoss;
    armedPid = pid;
    fault.set(null);
    dispatched = false;
    withheld = 0;
    executed = false;
    synchronized (events) {
      events.clear();
    }
    armed = true; // Loss.NONE is an intentional control that still records the selected COMMIT.
  }

  void disarm() {
    armed = false;
    armedPid = ALL_CONNECTIONS;
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

  List<WireEvent> events() {
    synchronized (events) {
      return List.copyOf(events);
    }
  }

  void requireHealthy() {
    Throwable problem = fault.get();
    if (problem != null)
      throw new AssertionError(
          "Test transport could not observe the actual driver protocol", problem);
  }

  private void accept(String host, int port) {
    while (!listener.isClosed()) {
      try {
        Socket client = listener.accept();
        sockets.add(client);
        tasks.execute(() -> bridge(client, host, port));
      } catch (IOException exception) {
        if (!listener.isClosed()) fault.compareAndSet(null, exception);
      }
    }
  }

  private void bridge(Socket client, String host, int port) {
    Socket upstream = new Socket();
    sockets.add(upstream);
    Session session = null;
    try (client;
        upstream) {
      upstream.connect(new InetSocketAddress(host, port), 5000);
      client.setSoTimeout(12000);
      upstream.setSoTimeout(12000);
      DataInputStream fromClient = new DataInputStream(client.getInputStream());
      DataOutputStream toClient = new DataOutputStream(client.getOutputStream());
      DataInputStream fromPg = new DataInputStream(upstream.getInputStream());
      DataOutputStream toPg = new DataOutputStream(upstream.getOutputStream());
      startup(fromClient, toClient, fromPg, toPg);
      // Pooled connections may be idle; request deadlines and close() bound the relay lifetime.
      client.setSoTimeout(0);
      upstream.setSoTimeout(0);
      Session relaySession = new Session(connections.incrementAndGet());
      session = relaySession;
      tasks.execute(() -> replies(client, upstream, fromPg, toClient, relaySession));
      requests(fromClient, toPg, relaySession);
    } catch (IOException | RuntimeException exception) {
      if (!closed.get() && (session == null || !session.expectedClose)) {
        fault.compareAndSet(null, exception);
      }
    } finally {
      sockets.remove(client);
      sockets.remove(upstream);
    }
  }

  private static void startup(
      DataInputStream client, DataOutputStream toClient, DataInputStream pg, DataOutputStream toPg)
      throws IOException {
    while (true) {
      byte[] bytes = body(client);
      toPg.writeInt(bytes.length + 4);
      toPg.write(bytes);
      toPg.flush();
      if (bytes.length != 4) return;
      int response = pg.readUnsignedByte();
      toClient.writeByte(response);
      toClient.flush();
      if (response != 'N')
        throw new IllegalStateException(
            "Fixture negotiated non-plaintext PostgreSQL; no wire proof");
    }
  }

  private void requests(DataInputStream from, DataOutputStream to, Session session)
      throws IOException {
    int type;
    while ((type = from.read()) != -1) {
      byte[] bytes = body(from);
      switch (type) {
        case 'P' ->
            sendOperation(
                to,
                type,
                bytes,
                session.operation(OperationKind.PARSE, parse(session, bytes)),
                session);
        case 'B' ->
            sendOperation(
                to,
                type,
                bytes,
                session.operation(OperationKind.BIND, bind(session, bytes)),
                session);
        case 'E' ->
            sendOperation(
                to,
                type,
                bytes,
                session.operation(OperationKind.EXECUTE, execute(session, bytes)),
                session);
        case 'Q' -> {
          Operation operation =
              session.operation(OperationKind.SIMPLE, classify(cstring(bytes, 0)));
          session.sync();
          sendOperation(to, type, bytes, operation, session);
        }
        case 'S' -> {
          session.sync();
          frame(to, type, bytes);
          if (shouldObserve(session)) {
            event(session, null, "SYNC_FORWARDED", "OTHER", "S", "cycle");
          }
        }
        default -> frame(to, type, bytes);
      }
      if (type == 'X') {
        session.expectedClose = true;
        return;
      }
    }
    session.expectedClose = true;
  }

  private void sendOperation(
      DataOutputStream to, int type, byte[] bytes, Operation operation, Session session)
      throws IOException {
    // The immutable FIFO entry exists before wire write, but FORWARDED is logged only after flush.
    frame(to, type, bytes);
    if (!shouldObserve(session)) {
      operation.forwarded.countDown();
      return;
    }
    event(
        session,
        operation,
        "FRONTEND_FORWARDED",
        operation.sql,
        Character.toString((char) type),
        "sent");
    if (operation.sql.equals("COMMIT") && operation.kind.isExecutable()) {
      dispatched = true;
      event(
          session,
          operation,
          "COMMIT_FORWARDED",
          operation.sql,
          Character.toString((char) type),
          "sent");
    }
    operation.forwarded.countDown();
  }

  private String parse(Session session, byte[] bytes) {
    Cursor cursor = new Cursor(bytes);
    String statement = cursor.string();
    String sql = classify(cursor.string());
    synchronized (session) {
      session.statements.put(statement, sql);
    }
    return sql;
  }

  private String bind(Session session, byte[] bytes) {
    Cursor cursor = new Cursor(bytes);
    String portal = cursor.string();
    String statement = cursor.string();
    synchronized (session) {
      String sql = session.statements.get(statement);
      if (sql == null) throw new IllegalStateException("Bind references an unparsed statement");
      session.portals.put(portal, sql);
      return sql;
    }
  }

  private String execute(Session session, byte[] bytes) {
    synchronized (session) {
      String sql = session.portals.get(new Cursor(bytes).string());
      if (sql == null) throw new IllegalStateException("Execute references an unbound portal");
      return sql;
    }
  }

  private void replies(
      Socket client, Socket upstream, DataInputStream from, DataOutputStream to, Session session) {
    try (client;
        upstream) {
      int type;
      while ((type = from.read()) != -1) {
        byte[] bytes = body(from);
        if (type == 'K') {
          synchronized (session) {
            session.pid = intAt(bytes, 0);
          }
          if (shouldObserve(session)) {
            event(session, null, "BACKEND_KEY", "OTHER", "K", "pid-observed");
          }
        }
        Operation operation = responseOperation(session, type);
        if (operation != null) {
          awaitForwarded(operation);
          if (!shouldObserve(session)) {
            frame(to, type, bytes);
            continue;
          }
          boolean commitComplete = type == 'C' && "COMMIT".equals(commandTag(bytes));
          if (operation.sql.equals("COMMIT") && type == 'C' && !commitComplete) {
            throw new IllegalStateException(
                "COMMIT execution received non-COMMIT command completion");
          }
          if (isTarget(session) && operation.sql.equals("COMMIT") && operation.kind.isExecutable())
            executed = true;
          event(
              session,
              operation,
              switch (type) {
                case 'C' -> "BACKEND_COMMAND_COMPLETE";
                case 'E' -> "BACKEND_ERROR";
                case '1' -> "BACKEND_PARSE_COMPLETE";
                case '2' -> "BACKEND_BIND_COMPLETE";
                default -> "BACKEND_REPLY";
              },
              operation.sql,
              Character.toString((char) type),
              type == 'C' ? commandTag(bytes) : type == 'E' ? errorSqlState(bytes) : "ack");
          if (shouldWithhold(session, operation, type, commitComplete)) {
            withheld = (char) type;
            event(
                session,
                operation,
                "BACKEND_REPLY_WITHHELD",
                operation.sql,
                Character.toString((char) type),
                "target");
            armed = false;
            session.expectedClose = true;
            return;
          }
          event(
              session,
              operation,
              "BACKEND_REPLY_FORWARDED",
              operation.sql,
              Character.toString((char) type),
              "target-or-control");
        }
        if (type == 'Z') {
          session.readyForQuery();
          if (shouldObserve(session)) {
            event(session, null, "READY_FOR_QUERY", "OTHER", "Z", "cycle");
          }
        }
        frame(to, type, bytes);
      }
    } catch (IOException | RuntimeException exception) {
      if (!closed.get() && !session.expectedClose) fault.compareAndSet(null, exception);
    }
  }

  private Operation responseOperation(Session session, int backendType) {
    synchronized (session) {
      Operation operation = session.operations.peekFirst();
      if (operation == null) return null;
      if (!operation.kind.accepts(backendType)) return null;
      if (operation.kind != OperationKind.SIMPLE) session.operations.removeFirst();
      if (backendType == 'E' && operation.kind != OperationKind.SIMPLE)
        session.discardUntilReady = true;
      return operation;
    }
  }

  private void awaitForwarded(Operation operation) {
    try {
      if (!operation.forwarded.await(5, TimeUnit.SECONDS))
        throw new IllegalStateException("Backend replied before frontend write completed");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted awaiting frontend write", exception);
    }
  }

  private boolean shouldWithhold(
      Session session, Operation operation, int type, boolean commitComplete) {
    if (!armed || !isTarget(session) || !operation.kind.isExecutable()) return false;
    if (loss == Loss.COMMIT_REPLY && operation.sql.equals("COMMIT"))
      return type == 'E' || commitComplete;
    if (loss == Loss.FLUSH_REPLY && operation.sql.equals("UPDATE_ROOMS"))
      return type == 'C' || type == 'E';
    return loss == Loss.STATEMENT_REPLY
        && operation.sql.equals("STATEMENT_FAULT")
        && (type == 'C' || type == 'E');
  }

  private boolean isTarget(Session session) {
    return armedPid == ALL_CONNECTIONS || session.pid == armedPid;
  }

  private boolean shouldObserve(Session session) {
    return armed && isTarget(session);
  }

  private void event(
      Session session,
      Operation operation,
      String event,
      String sql,
      String backendType,
      String detail) {
    int pid;
    synchronized (session) {
      pid = session.pid;
    }
    events.add(
        new WireEvent(
            System.nanoTime(),
            pid,
            operation == null ? NO_EXCHANGE : operation.sequence,
            event,
            sql,
            backendType,
            detail));
  }

  private static String classify(String query) {
    String normalized = query.trim().toUpperCase(Locale.ROOT);
    if (normalized.equals("COMMIT")) return "COMMIT";
    if (normalized.startsWith("UPDATE ROOMS")) return "UPDATE_ROOMS";
    if (normalized.contains("NK75_STATEMENT_FAULT")) return "STATEMENT_FAULT";
    if (normalized.startsWith("ROLLBACK")) return "ROLLBACK";
    return "OTHER";
  }

  private static String errorSqlState(byte[] bytes) {
    for (int offset = 0; offset < bytes.length && bytes[offset] != 0; ) {
      int field = bytes[offset++];
      String value = cstring(bytes, offset);
      offset += value.getBytes(StandardCharsets.UTF_8).length + 1;
      if (field == 'C') return value;
    }
    return "missing-sqlstate";
  }

  private static String commandTag(byte[] bytes) {
    return cstring(bytes, 0);
  }

  private static int intAt(byte[] bytes, int offset) {
    if (offset + 4 > bytes.length)
      throw new IllegalStateException("Truncated PostgreSQL BackendKeyData frame");
    return ((bytes[offset] & 0xff) << 24)
        | ((bytes[offset + 1] & 0xff) << 16)
        | ((bytes[offset + 2] & 0xff) << 8)
        | (bytes[offset + 3] & 0xff);
  }

  private static String cstring(byte[] bytes, int start) {
    int end = start;
    while (end < bytes.length && bytes[end] != 0) end++;
    if (end == bytes.length)
      throw new IllegalStateException("Missing PostgreSQL string terminator");
    return new String(bytes, start, end - start, StandardCharsets.UTF_8);
  }

  private static byte[] body(DataInputStream input) throws IOException {
    int size = input.readInt() - 4;
    if (size < 0 || size > 1024 * 1024)
      throw new IOException("Unexpected frame length in isolated PostgreSQL fixture");
    byte[] bytes = input.readNBytes(size);
    if (bytes.length != size) throw new EOFException("Truncated PostgreSQL frame");
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
    if (!closed.compareAndSet(false, true)) return;
    listener.close();
    for (Socket socket : sockets) socket.close();
    tasks.shutdownNow();
    tasks.close();
  }

  private enum OperationKind {
    PARSE {
      boolean accepts(int type) {
        return type == '1' || type == 'E';
      }
    },
    BIND {
      boolean accepts(int type) {
        return type == '2' || type == 'E';
      }
    },
    EXECUTE {
      boolean accepts(int type) {
        return type == 'C' || type == 'E';
      }
    },
    SIMPLE {
      boolean accepts(int type) {
        return type == 'C' || type == 'E';
      }
    };

    abstract boolean accepts(int type);

    boolean isExecutable() {
      return this == EXECUTE || this == SIMPLE;
    }
  }

  private static final class Operation {
    private final long sequence;
    private final OperationKind kind;
    private final String sql;
    private final CountDownLatch forwarded = new CountDownLatch(1);

    private Operation(long sequence, OperationKind kind, String sql) {
      this.sequence = sequence;
      this.kind = kind;
      this.sql = sql;
    }
  }

  private static final class Session {
    private final long connection;
    private final Map<String, String> statements = new HashMap<>();
    private final Map<String, String> portals = new HashMap<>();
    private final ArrayDeque<Operation> operations = new ArrayDeque<>();
    private final ArrayDeque<Long> syncBoundaries = new ArrayDeque<>();
    private int pid = -1;
    private long nextSequence;
    private boolean discardUntilReady;
    private volatile boolean expectedClose;

    private Session(long connection) {
      this.connection = connection;
    }

    private synchronized Operation operation(OperationKind kind, String sql) {
      Operation operation = new Operation(nextSequence++, kind, sql);
      operations.addLast(operation);
      return operation;
    }

    private synchronized void sync() {
      syncBoundaries.addLast(nextSequence);
    }

    private synchronized void readyForQuery() {
      long boundary = syncBoundaries.isEmpty() ? Long.MAX_VALUE : syncBoundaries.removeFirst();
      discardUntilReady = false;
      while (!operations.isEmpty() && operations.peekFirst().sequence < boundary)
        operations.removeFirst();
    }
  }

  private static final class Cursor {
    private final byte[] bytes;
    private int position;

    private Cursor(byte[] bytes) {
      this.bytes = bytes;
    }

    private String string() {
      int start = position;
      while (position < bytes.length && bytes[position] != 0) position++;
      if (position == bytes.length)
        throw new IllegalStateException("Missing PostgreSQL frontend string terminator");
      String value = new String(bytes, start, position - start, StandardCharsets.UTF_8);
      position++;
      return value;
    }
  }
}
