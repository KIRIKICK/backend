package org.kirikick.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kirikick.api.auth.infra.SessionRepositoryDataAccessExceptionTranslator;
import org.kirikick.api.auth.infra.SessionStateUnavailableFilter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.FlushMode;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;

@Configuration(proxyBeanMethods = false)
public class SessionFailureBoundaryConfiguration {

  @Bean
  static BeanPostProcessor sessionRepositoryFailureTranslator() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof RedisSessionRepository repository) {
          repository.setFlushMode(FlushMode.IMMEDIATE);
        }
        if ("sessionRepository".equals(beanName)
            && bean instanceof SessionRepository<?> repository) {
          return translating(repository);
        }
        return bean;
      }
    };
  }

  @Bean
  FilterRegistrationBean<SessionStateUnavailableFilter> sessionStateUnavailableFilter(
      ObjectMapper objectMapper, SessionRepository<? extends Session> sessionRepository) {
    FilterRegistrationBean<SessionStateUnavailableFilter> registration =
        new FilterRegistrationBean<>(
            new SessionStateUnavailableFilter(objectMapper, sessionRepository));
    registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
    return registration;
  }

  private static <S extends Session> SessionRepository<S> translating(
      SessionRepository<S> repository) {
    return new SessionRepositoryDataAccessExceptionTranslator<>(repository);
  }
}
