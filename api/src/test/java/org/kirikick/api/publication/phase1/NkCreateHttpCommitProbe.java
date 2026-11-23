package org.kirikick.api.publication.phase1;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import javax.sql.DataSource;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.FlushEventListener;
import org.kirikick.api.publication.infra.PublicationCommitObserver;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-only observation of the real datasource, transaction, and response boundaries. */
final class NkCreateHttpCommitProbe {
  record ProbeEvent(long nanoTime, String event) {}

  private static final ThreadLocal<Case> ACTIVE = new ThreadLocal<>();
  private static final Map<String, Case> CASES = new ConcurrentHashMap<>();

  private NkCreateHttpCommitProbe() {}

  static Case arm(String id, String key, IntConsumer commitEntering) {
    return arm(id, key, ignored -> {}, commitEntering);
  }

  /** Connection callback runs when the request obtains its actual pooled PostgreSQL connection. */
  static Case arm(
      String id, String key, IntConsumer connectionAcquired, IntConsumer commitEntering) {
    Case testCase = new Case(id, connectionAcquired, commitEntering);
    CASES.put(key, testCase);
    return testCase;
  }

  static Case armResponseLoss(String id, String key, IntConsumer commitEntering) {
    Case testCase = arm(id, key, commitEntering);
    testCase.failResponseWrite = true;
    return testCase;
  }

  static void disarm(String key) {
    CASES.remove(key);
    ACTIVE.remove();
  }

  /**
   * Holds every real Hikari connection until closed, causing its configured acquisition timeout.
   */
  static PoolBorrow borrowPool(DataSource dataSource) throws SQLException {
    HikariDataSource pool = unwrapPool(dataSource);
    List<Connection> borrowed = new ArrayList<>();
    try {
      for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
        borrowed.add(dataSource.getConnection());
      }
      return new PoolBorrow(borrowed);
    } catch (SQLException exception) {
      for (Connection connection : borrowed) {
        connection.close();
      }
      throw exception;
    }
  }

  static Case active() {
    return ACTIVE.get();
  }

  /**
   * Captures the transaction's actual JDBC backend PID and xid; call inside the real transaction.
   */
  static void captureJdbcState(JdbcClient jdbc) {
    Case testCase = active();
    if (testCase == null) {
      return;
    }
    testCase.backendPid = jdbc.sql("select pg_backend_pid()").query(Integer.class).single();
    testCase.xid = jdbc.sql("select pg_current_xact_id()::text").query(String.class).single();
  }

  /**
   * Observes the current transaction; F11's explicitly armed callback creates its pending change.
   */
  static void observeTransaction() {
    Case testCase = active();
    if (testCase == null) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void beforeCommit(boolean readOnly) {
            testCase.beforeCommit = true;
            testCase.event("BEFORE_COMMIT");
            testCase.runBeforeCommitAction();
          }
        });
  }

  /**
   * Called by the test's observer spy after the real observer throws; it never classifies failures.
   */
  static void observerFailure(Throwable failure) {
    Case testCase = active();
    if (testCase == null) {
      return;
    }
    testCase.outerFailure = failure;
    if (failure instanceof PublicationCommitObserver.OutcomeUnknownException unknown) {
      testCase.observerSnapshot = unknown.snapshot();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Configuration {
    @Bean
    JpaTransactionManager transactionManager(EntityManagerFactory factory) {
      return new CommitProbeJpaTransactionManager(factory);
    }

    @Bean
    InitializingBean nk75NormalFlushObservation(EntityManagerFactory factory) {
      return () -> {
        EventListenerRegistry listeners =
            factory
                .unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry()
                .getService(EventListenerRegistry.class);
        listeners.prependListeners(
            EventType.FLUSH,
            (FlushEventListener)
                event -> {
                  Case testCase = active();
                  if (testCase != null) {
                    testCase.finalFlushReturned = false;
                    testCase.event("FLUSH_ENTER");
                  }
                });
        listeners.appendListeners(
            EventType.FLUSH,
            (FlushEventListener)
                event -> {
                  Case testCase = active();
                  if (testCase != null) {
                    testCase.finalFlushReturned = true;
                    testCase.event("FLUSH_RETURNED");
                  }
                });
      };
    }

    @Bean
    static BeanPostProcessor nk75CommitProbeDataSourceDecorator() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if ("dataSource".equals(name) && bean instanceof DataSource dataSource) {
            return dataSource instanceof ProbeDataSource
                ? dataSource
                : new ProbeDataSource(dataSource);
          }
          return bean;
        }
      };
    }

    @Bean
    Filter nk75CommitProbeRequestFilter() {
      return new Filter() {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
          String key = ((HttpServletRequest) request).getHeader("Idempotency-Key");
          Case testCase = key == null ? null : CASES.get(key);
          if (testCase == null) {
            chain.doFilter(request, response);
            return;
          }
          ACTIVE.set(testCase);
          testCase.event("HTTP_REQUEST_ENTER");
          try {
            ServletResponse observed = new ResponseLoss((HttpServletResponse) response, testCase);
            chain.doFilter(request, observed);
          } finally {
            testCase.event("HTTP_REQUEST_EXIT");
            ACTIVE.remove();
          }
        }
      };
    }
  }

  private static final class CommitProbeJpaTransactionManager extends JpaTransactionManager {
    private CommitProbeJpaTransactionManager(EntityManagerFactory factory) {
      super(factory);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      Case testCase = active();
      if (testCase != null) {
        testCase.markJpaTransactionManagerCommitEntered();
      }
      super.doCommit(status);
    }
  }

  static final class Case {
    private final List<ProbeEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final String id;
    private final IntConsumer connectionAcquired;
    private final IntConsumer commitEntering;
    private volatile boolean commitEntered;
    private volatile boolean commitReturned;
    private volatile SQLException commitFailure;
    private volatile int backendPid = -1;
    private volatile int physicalCommitPid = -1;
    private volatile String xid;
    private volatile boolean finalFlushReturned;
    private volatile boolean beforeCommit;
    private volatile boolean jpaTransactionManagerCommitEntered;
    private volatile Runnable beforeCommitAction;
    private volatile Throwable outerFailure;
    private volatile PublicationCommitObserver.Snapshot observerSnapshot;
    private volatile boolean failResponseWrite;
    private volatile boolean responseWriteFailed;
    private volatile boolean responseWriteObserved;
    private volatile boolean successWriteBeforeCommitReturned;

    private Case(String id, IntConsumer connectionAcquired, IntConsumer commitEntering) {
      this.id = id;
      this.connectionAcquired = connectionAcquired;
      this.commitEntering = commitEntering;
    }

    String id() {
      return id;
    }

    boolean commitEntered() {
      return commitEntered;
    }

    boolean commitReturned() {
      return commitReturned;
    }

    SQLException commitFailure() {
      return commitFailure;
    }

    int physicalCommitPid() {
      return physicalCommitPid;
    }

    int backendPid() {
      return backendPid;
    }

    String xid() {
      return xid;
    }

    boolean finalFlushReturned() {
      return finalFlushReturned;
    }

    boolean beforeCommit() {
      return beforeCommit;
    }

    boolean jpaTransactionManagerCommitEntered() {
      return jpaTransactionManagerCommitEntered;
    }

    void flushAtJpaTransactionManagerCommit(Runnable action) {
      beforeCommitAction = action;
    }

    Throwable outerFailure() {
      return outerFailure;
    }

    PublicationCommitObserver.Snapshot observerSnapshot() {
      return observerSnapshot;
    }

    boolean responseWriteFailed() {
      return responseWriteFailed;
    }

    boolean successWriteBeforeCommitReturned() {
      return successWriteBeforeCommitReturned;
    }

    boolean commitFailureCorrelated() {
      for (Throwable failure = outerFailure; failure != null; failure = failure.getCause()) {
        if (failure == commitFailure) return true;
      }
      return false;
    }

    List<String> exceptionChain() {
      List<String> chain = new ArrayList<>();
      IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
      for (Throwable cursor = outerFailure;
          cursor != null && seen.put(cursor, Boolean.TRUE) == null;
          cursor = cursor.getCause()) {
        chain.add(cursor.getClass().getName());
      }
      return List.copyOf(chain);
    }

    List<ProbeEvent> events() {
      return List.copyOf(events);
    }

    private void event(String event) {
      events.add(new ProbeEvent(System.nanoTime(), event));
    }

    private void runBeforeCommitAction() {
      Runnable action = beforeCommitAction;
      if (action != null) {
        event("BEFORE_COMMIT_PENDING_CHANGE");
        action.run();
      }
    }

    private void markJpaTransactionManagerCommitEntered() {
      jpaTransactionManagerCommitEntered = true;
      event("JPA_TRANSACTION_MANAGER_DO_COMMIT_ENTER");
    }

    private void connectionAcquired(Connection connection) {
      if (backendPid < 0) {
        backendPid = NkCreateHttpCommitProbe.backendPid(connection);
        connectionAcquired.accept(backendPid);
        event("CONNECTION_ACQUIRED");
      }
    }

    private void entering(Connection connection) {
      commitEntered = true;
      event("PHYSICAL_COMMIT_ENTER");
      if (backendPid < 0) {
        backendPid = NkCreateHttpCommitProbe.backendPid(connection);
      }
      if (xid == null) {
        xid = NkCreateHttpCommitProbe.xid(connection);
      }
      physicalCommitPid = NkCreateHttpCommitProbe.backendPid(connection);
      commitEntering.accept(physicalCommitPid);
    }

    private void returned() {
      commitReturned = true;
      event("PHYSICAL_COMMIT_RETURN");
    }

    private void failed(SQLException failure) {
      commitFailure = failure;
      event("PHYSICAL_COMMIT_THROW");
    }
  }

  private static final class ProbeDataSource extends DelegatingDataSource {
    private ProbeDataSource(DataSource target) {
      super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
      return observe(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return observe(super.getConnection(username, password));
    }

    private static Connection observe(Connection target) {
      Case testCase = active();
      if (testCase != null) testCase.connectionAcquired(target);
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> invoke(target, method, arguments));
    }

    private static Object invoke(Connection target, Method method, Object[] arguments)
        throws Throwable {
      if (!"commit".equals(method.getName()) || method.getParameterCount() != 0)
        return invokeTarget(target, method, arguments);
      Case testCase = active();
      if (testCase != null) testCase.entering(target);
      try {
        Object result = invokeTarget(target, method, arguments);
        if (testCase != null) testCase.returned();
        return result;
      } catch (SQLException exception) {
        if (testCase != null) testCase.failed(exception);
        throw exception;
      }
    }

    private static Object invokeTarget(Connection target, Method method, Object[] arguments)
        throws Throwable {
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException exception) {
        throw exception.getTargetException();
      }
    }
  }

  private static final class ResponseLoss extends HttpServletResponseWrapper {
    private final Case testCase;

    private ResponseLoss(HttpServletResponse response, Case testCase) {
      super(response);
      this.testCase = testCase;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
      ServletOutputStream delegate = super.getOutputStream();
      return new ServletOutputStream() {
        @Override
        public boolean isReady() {
          return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
          delegate.setWriteListener(listener);
        }

        @Override
        public void write(int value) throws IOException {
          beforeWrite();
          delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
          beforeWrite();
          delegate.write(bytes, offset, length);
        }

        private void beforeWrite() throws IOException {
          if (!testCase.responseWriteObserved) {
            testCase.responseWriteObserved = true;
            testCase.successWriteBeforeCommitReturned =
                getStatus() >= 200 && getStatus() < 300 && !testCase.commitReturned;
            testCase.event("HTTP_RESPONSE_FIRST_WRITE_STATUS_" + getStatus());
          }
          if (!testCase.failResponseWrite) return;
          testCase.responseWriteFailed = true;
          testCase.event("HTTP_RESPONSE_WRITE_FAILED");
          throw new IOException(
              "nk75 test-only response serialization failure before bytes are written");
        }
      };
    }
  }

  static final class PoolBorrow implements AutoCloseable {
    private final List<Connection> connections;

    private PoolBorrow(List<Connection> connections) {
      this.connections = List.copyOf(connections);
    }

    @Override
    public void close() throws SQLException {
      SQLException failure = null;
      for (Connection connection : connections) {
        try {
          connection.close();
        } catch (SQLException exception) {
          failure = exception;
        }
      }
      if (failure != null) throw failure;
    }
  }

  private static HikariDataSource unwrapPool(DataSource source) {
    DataSource candidate = source;
    while (candidate instanceof DelegatingDataSource delegating) {
      candidate = delegating.getTargetDataSource();
    }
    if (candidate instanceof HikariDataSource pool) return pool;
    try {
      return candidate.unwrap(HikariDataSource.class);
    } catch (SQLException exception) {
      throw new IllegalStateException("expected the isolated Hikari datasource", exception);
    }
  }

  private static int backendPid(Connection connection) {
    try {
      Class<?> pgConnection = Class.forName("org.postgresql.PGConnection");
      Object physical = connection.unwrap(pgConnection);
      return (int) pgConnection.getMethod("getBackendPID").invoke(physical);
    } catch (ReflectiveOperationException | SQLException exception) {
      return -1;
    }
  }

  private static String xid(Connection connection) {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("select pg_current_xact_id()::text")) {
      return rows.next() ? rows.getString(1) : null;
    } catch (SQLException exception) {
      return null;
    }
  }
}
