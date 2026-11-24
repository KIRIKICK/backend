package org.kirikick.api.publication.infra;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Keeps the configured pool while observing only commit calls in an active publication scope. */
public final class PublicationDataSource extends DelegatingDataSource {
  public PublicationDataSource(DataSource targetDataSource) {
    super(targetDataSource);
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
    if (!PublicationCommitObserver.isActive()) {
      return target;
    }
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> invoke(target, method, arguments));
  }

  private static Object invoke(Connection target, Method method, Object[] arguments)
      throws Throwable {
    if (!"commit".equals(method.getName()) || method.getParameterCount() != 0) {
      return invokeTarget(target, method, arguments);
    }
    PublicationCommitObserver.commitEntered();
    try {
      Object result = invokeTarget(target, method, arguments);
      PublicationCommitObserver.commitReturned();
      return result;
    } catch (SQLException exception) {
      PublicationCommitObserver.commitFailed(exception);
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
