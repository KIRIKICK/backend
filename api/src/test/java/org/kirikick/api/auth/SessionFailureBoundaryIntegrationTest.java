package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.infra.SessionRepositoryDataAccessExceptionTranslator;
import org.kirikick.api.auth.infra.SessionStateUnavailableFilter;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.testsupport.postgres.PostgresTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.session.FlushMode;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@ActiveProfiles("test")
class SessionFailureBoundaryIntegrationTest {

  @Autowired private SessionRepository<?> sessionRepository;

  @Autowired private SessionRepositoryFilter<?> springSessionRepositoryFilter;

  @Autowired
  private FilterRegistrationBean<SessionStateUnavailableFilter> sessionStateUnavailableFilter;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    registry.add("spring.datasource.url", PostgresTestDatabase.container()::getJdbcUrl);
    registry.add("spring.datasource.username", PostgresTestDatabase.container()::getUsername);
    registry.add("spring.datasource.password", PostgresTestDatabase.container()::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @Test
  void sessionRepositoryFilterUsesPrimaryFailureTranslatingRepository() {
    Object filterRepository =
        ReflectionTestUtils.getField(springSessionRepositoryFilter, "sessionRepository");

    assertThat(sessionRepository)
        .isInstanceOf(SessionRepositoryDataAccessExceptionTranslator.class);
    assertThat(filterRepository).isSameAs(sessionRepository);
    assertThat(sessionStateUnavailableFilter.getOrder())
        .isEqualTo(SessionRepositoryFilter.DEFAULT_ORDER - 1);
  }

  @Test
  void redisSessionRepositoryFlushesProofWritesImmediately() {
    Object delegate = ReflectionTestUtils.getField(sessionRepository, "delegate");

    assertThat(delegate).isInstanceOf(RedisSessionRepository.class);
    assertThat(ReflectionTestUtils.getField(delegate, "flushMode")).isEqualTo(FlushMode.IMMEDIATE);
  }
}
