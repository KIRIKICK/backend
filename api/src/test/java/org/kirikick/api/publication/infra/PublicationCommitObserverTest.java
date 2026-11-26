package org.kirikick.api.publication.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.publication.infra.exception.PublicationCommitOutcomeUnknownException;
import org.kirikick.api.publication.infra.model.PublicationCommitSnapshot;
import org.mockito.Mockito;

class PublicationCommitObserverTest {

  @Test
  @DisplayName("커밋 경계의 같은 SQLException과 전송 실패만 결과 불명으로 바꾼다")
  void convertsOnlyCorrelatedCommitTransportFailure() throws Exception {
    DataSource target = Mockito.mock(DataSource.class);
    Connection connection = Mockito.mock(Connection.class);
    given(target.getConnection()).willReturn(connection);
    SQLException boundary = new SQLException("connection lost", new IOException("transport"));
    willThrow(boundary).given(connection).commit();

    PublicationCommitObserver observer = new PublicationCommitObserver();
    PublicationDataSource dataSource = new PublicationDataSource(target);

    assertThatThrownBy(() ->
            observer.observe(() -> {
              Connection observed = acquireAsRuntime(dataSource);
              commitAsRuntime(observed);
              return null;
            }))
        .isInstanceOf(PublicationCommitOutcomeUnknownException.class)
        .satisfies(thrown -> {
          PublicationCommitOutcomeUnknownException exception =
              (PublicationCommitOutcomeUnknownException) thrown;
          assertThat(exception.getCause()).isInstanceOf(IllegalStateException.class);
          assertThat(exception.getCause().getCause()).isSameAs(boundary);
          assertThat(exception.snapshot())
              .isEqualTo(new PublicationCommitSnapshot(true, false, true, true, false, true));
        });
  }

  @Test
  @DisplayName("외부 오류에 같은 커밋 SQLException이 없으면 결과 불명으로 바꾸지 않는다")
  void leavesUncorrelatedFailureUntouched() throws Exception {
    DataSource target = Mockito.mock(DataSource.class);
    Connection connection = Mockito.mock(Connection.class);
    given(target.getConnection()).willReturn(connection);
    SQLException boundary = new SQLException("connection lost", new IOException("transport"));
    willThrow(boundary).given(connection).commit();

    PublicationCommitObserver observer = new PublicationCommitObserver();
    PublicationDataSource dataSource = new PublicationDataSource(target);
    RuntimeException outer = new IllegalStateException("outer", new SQLException("other"));

    assertThatThrownBy(() ->
            observer.observe(() -> {
              Connection observed = acquireAsRuntime(dataSource);
              try {
                observed.commit();
              } catch (SQLException ignored) {
                throw outer;
              }
              return null;
            }))
        .isSameAs(outer);
  }

  @Test
  @DisplayName("SQLException nextException 체인의 같은 커밋 오류도 상관관계를 확인한다")
  void followsNextExceptionByIdentity() throws Exception {
    DataSource target = Mockito.mock(DataSource.class);
    Connection connection = Mockito.mock(Connection.class);
    given(target.getConnection()).willReturn(connection);
    SQLException boundary = new SQLException("connection lost", new IOException("transport"));
    willThrow(boundary).given(connection).commit();

    SQLException outerSql = new SQLException("translated");
    outerSql.setNextException(boundary);
    RuntimeException outer = new IllegalStateException("outer", outerSql);
    PublicationCommitObserver observer = new PublicationCommitObserver();
    PublicationDataSource dataSource = new PublicationDataSource(target);

    assertThatThrownBy(() ->
            observer.observe(() -> {
              Connection observed = acquireAsRuntime(dataSource);
              try {
                observed.commit();
              } catch (SQLException ignored) {
                throw outer;
              }
              return null;
            }))
        .isInstanceOf(PublicationCommitOutcomeUnknownException.class)
        .hasCause(outer);
  }

  @Test
  @DisplayName("정상 반환 뒤 오류는 결과 불명으로 바꾸지 않는다")
  void leavesFailureAfterCommitReturnUntouched() throws Exception {
    DataSource target = Mockito.mock(DataSource.class);
    Connection connection = Mockito.mock(Connection.class);
    given(target.getConnection()).willReturn(connection);

    PublicationCommitObserver observer = new PublicationCommitObserver();
    PublicationDataSource dataSource = new PublicationDataSource(target);
    RuntimeException afterCommit = new IllegalStateException("after commit");

    assertThatThrownBy(() ->
            observer.observe(() -> {
              Connection observed = acquireAsRuntime(dataSource);
              commitAsRuntime(observed);
              throw afterCommit;
            }))
        .isSameAs(afterCommit);
  }

  @Test
  @DisplayName("DataSource 연결 획득과 Connection unwrap은 원래 대상에 위임한다")
  void delegatesUnwrap() throws Exception {
    DataSource target = Mockito.mock(DataSource.class);
    Connection connection = Mockito.mock(Connection.class);
    given(target.getConnection()).willReturn(connection);
    given(connection.unwrap(Connection.class)).willReturn(connection);

    PublicationDataSource observed = new PublicationDataSource(target);

    assertThat(observed.getConnection().unwrap(Connection.class)).isSameAs(connection);
    Connection acquiredInScope =
        new PublicationCommitObserver().observe(() -> acquireAsRuntime(observed));
    assertThat(acquiredInScope).isNotSameAs(connection);
    assertThat(acquiredInScope.unwrap(Connection.class)).isSameAs(connection);
    then(target).should(Mockito.times(2)).getConnection();
  }

  @Test
  @DisplayName("데이터소스 장식자는 대상 풀을 종료할 수 없다")
  void doesNotOwnTargetDataSourceLifecycle() {
    assertThat(AutoCloseable.class.isAssignableFrom(PublicationDataSource.class)).isFalse();
  }

  private static Connection acquireAsRuntime(DataSource dataSource) {
    try {
      return dataSource.getConnection();
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
