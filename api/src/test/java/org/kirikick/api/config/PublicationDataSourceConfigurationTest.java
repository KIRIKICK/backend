package org.kirikick.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.publication.infra.PublicationCommitObserver;
import org.kirikick.api.publication.infra.PublicationDataSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PublicationDataSourceConfigurationTest {

  @Test
  @DisplayName("출제 Grace 설정이 없으면 DataSource를 장식하지 않는다")
  void leavesDataSourceUntouchedWithoutGrace() {
    HikariDataSource pool = new HikariDataSource();

    new ApplicationContextRunner()
        .withUserConfiguration(PublicationDataSourceConfiguration.class)
        .withBean("dataSource", HikariDataSource.class, () -> pool)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(PublicationCommitObserver.class);
          assertThat(context.getBean(DataSource.class)).isSameAs(pool);
          assertThat(context.getBean(DataSource.class).unwrap(HikariDataSource.class))
              .isSameAs(pool);
        });

    assertThat(pool.isClosed()).isTrue();
  }

  @Test
  @DisplayName("출제 Grace 설정이 있으면 같은 풀을 DataSource 장식자로 노출하고 종료한다")
  void wrapsAndClosesOriginalPoolWithGrace() {
    HikariDataSource pool = new HikariDataSource();

    new ApplicationContextRunner()
        .withUserConfiguration(PublicationDataSourceConfiguration.class)
        .withBean("dataSource", HikariDataSource.class, () -> pool)
        .withPropertyValues("publication.grace=PT0S")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(PublicationCommitObserver.class);
          assertThat(context).doesNotHaveBean(PublicationProperties.class);
          DataSource dataSource = context.getBean(DataSource.class);
          assertThat(dataSource).isInstanceOf(PublicationDataSource.class);
          assertThat(((PublicationDataSource) dataSource).getTargetDataSource()).isSameAs(pool);
          assertThat(dataSource.isWrapperFor(HikariDataSource.class)).isTrue();
          assertThat(dataSource.unwrap(HikariDataSource.class)).isSameAs(pool);
        });

    assertThat(pool.isClosed()).isTrue();
  }
}
