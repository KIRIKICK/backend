package org.kirikick.api.config;

import javax.sql.DataSource;
import org.kirikick.api.publication.infra.PublicationCommitObserver;
import org.kirikick.api.publication.infra.PublicationDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@Conditional(PublicationEnabledCondition.class)
public class PublicationDataSourceConfiguration {
  @Bean
  PublicationCommitObserver publicationCommitObserver() {
    return new PublicationCommitObserver();
  }

  @Bean
  static BeanPostProcessor publicationDataSourceDecorator() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if ("dataSource".equals(beanName)
            && bean instanceof DataSource dataSource
            && !(dataSource instanceof PublicationDataSource)) {
          // 출제의 물리 COMMIT 결과를 관측하기 위해 공용 DataSource를 장식한다.
          return new PublicationDataSource(dataSource);
        }
        return bean;
      }
    };
  }
}
