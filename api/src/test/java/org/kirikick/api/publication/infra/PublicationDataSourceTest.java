package org.kirikick.api.publication.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.publication.infra.exception.PublicationCommitOutcomeUnknownException;
import org.mockito.Mockito;

class PublicationDataSourceTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("관측 범위 밖에서는 두 획득 경로 모두 delegate의 연결 자체를 반환한다")
  void returnsDelegateConnectionOutsideScope(boolean credentials) throws Exception {
    DataSource target = Mockito.mock(DataSource.class);
    Connection connection = Mockito.mock(Connection.class);
    given(acquire(target, credentials)).willReturn(connection);

    assertThat(acquire(new PublicationDataSource(target), credentials)).isSameAs(connection);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("두 획득 경로의 SQLException은 관측 범위 유무와 관계없이 보존한다")
  void preservesAcquisitionFailure(boolean credentials) throws Exception {
    DataSource target = Mockito.mock(DataSource.class);
    SQLException failure = new SQLException("acquisition failed", new IOException("transport"));
    given(acquire(target, credentials)).willThrow(failure);
    PublicationDataSource dataSource = new PublicationDataSource(target);

    assertThatThrownBy(() -> acquire(dataSource, credentials)).isSameAs(failure);
    assertThatThrownBy(() ->
            new PublicationCommitObserver()
                .observe(() -> acquireAsRuntime(dataSource, credentials)))
        .isInstanceOf(IllegalStateException.class)
        .hasCause(failure);
    assertThatThrownBy(() -> acquire(dataSource, credentials)).isSameAs(failure);
    Connection connection = Mockito.mock(Connection.class);
    if (credentials) {
      willReturn(connection).given(target).getConnection("test", "test");
    } else {
      willReturn(connection).given(target).getConnection();
    }
    assertThat(acquire(dataSource, credentials)).isSameAs(connection);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("활성 범위에서 획득한 연결의 실제 커밋 예외를 연결하고 실패 후 범위를 정리한다")
  void observesCommitFailureAndClearsScope(boolean credentials) throws Exception {
    DataSource target = Mockito.mock(DataSource.class);
    Connection connection = Mockito.mock(Connection.class);
    given(acquire(target, credentials)).willReturn(connection);
    SQLException failure = new SQLException("commit reply lost", new IOException("transport"));
    willThrow(failure).given(connection).commit();
    PublicationDataSource dataSource = new PublicationDataSource(target);

    assertThatThrownBy(() ->
            new PublicationCommitObserver()
                .observe(() -> {
                  Connection observed = acquireAsRuntime(dataSource, credentials);
                  assertThat(observed).isNotSameAs(connection);
                  commitAsRuntime(observed);
                  return null;
                }))
        .isInstanceOf(PublicationCommitOutcomeUnknownException.class)
        .satisfies(thrown -> assertThat(thrown.getCause().getCause()).isSameAs(failure));

    assertThat(acquire(dataSource, credentials)).isSameAs(connection);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("성공 뒤 관측 범위를 정리해 다음 획득은 원래 연결을 반환한다")
  void clearsScopeAfterSuccess(boolean credentials) throws Exception {
    DataSource target = Mockito.mock(DataSource.class);
    Connection connection = Mockito.mock(Connection.class);
    given(acquire(target, credentials)).willReturn(connection);
    PublicationDataSource dataSource = new PublicationDataSource(target);

    new PublicationCommitObserver()
        .observe(() -> {
          Connection observed = acquireAsRuntime(dataSource, credentials);
          assertThat(observed).isNotSameAs(connection);
          commitAsRuntime(observed);
          return null;
        });

    assertThat(acquire(dataSource, credentials)).isSameAs(connection);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("범위 밖에서 획득한 연결은 범위 안에서 커밋해도 출제 관측에 섞이지 않는다")
  void ignoresConnectionAcquiredOutsideScope(boolean credentials) throws Exception {
    DataSource target = Mockito.mock(DataSource.class);
    Connection connection = Mockito.mock(Connection.class);
    given(acquire(target, credentials)).willReturn(connection);
    SQLException failure = new SQLException("unrelated commit", new IOException("transport"));
    willThrow(failure).given(connection).commit();
    PublicationDataSource dataSource = new PublicationDataSource(target);
    Connection unrelated = acquire(dataSource, credentials);

    assertThatThrownBy(() ->
            new PublicationCommitObserver()
                .observe(() -> {
                  commitAsRuntime(unrelated);
                  return null;
                }))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasCause(failure);
    assertThat(acquire(dataSource, credentials)).isSameAs(connection);
  }

  private static Connection acquire(DataSource dataSource, boolean credentials)
      throws SQLException {
    return credentials ? dataSource.getConnection("test", "test") : dataSource.getConnection();
  }

  private static Connection acquireAsRuntime(DataSource dataSource, boolean credentials) {
    try {
      return acquire(dataSource, credentials);
    } catch (SQLException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void commitAsRuntime(Connection connection) {
    try {
      connection.commit();
    } catch (SQLException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
