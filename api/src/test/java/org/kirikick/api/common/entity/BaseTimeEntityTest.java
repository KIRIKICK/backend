package org.kirikick.api.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.kirikick.api.support.H2JpaTest;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@H2JpaTest
class BaseTimeEntityTest {

  @PersistenceContext private EntityManager entityManager;

  @MockitoBean(name = "auditingDateTimeProvider", enforceOverride = true)
  private DateTimeProvider auditingDateTimeProvider;

  @Test
  void recordsTimestampsAndPreservesCreatedAtOnUpdate() {
    Instant createdAt = Instant.parse("2026-11-02T00:00:00Z");
    Instant updatedAt = Instant.parse("2026-11-02T01:00:00Z");
    given(auditingDateTimeProvider.getNow()).willReturn(Optional.of(createdAt));

    entityManager.persist(new Probe(1L, "original"));
    entityManager.flush();
    entityManager.clear();

    Probe probe = entityManager.find(Probe.class, 1L);
    assertThat(probe.getCreatedAt()).isEqualTo(createdAt);
    assertThat(probe.getUpdatedAt()).isEqualTo(createdAt);

    given(auditingDateTimeProvider.getNow()).willReturn(Optional.of(updatedAt));
    probe.name = "changed";
    entityManager.flush();
    entityManager.clear();

    Probe updated = entityManager.find(Probe.class, 1L);
    assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
    assertThat(updated.getUpdatedAt()).isEqualTo(updatedAt);
  }

  @Entity(name = "AuditingProbe")
  public static class Probe extends BaseTimeEntity {

    @Id private Long id;
    private String name;

    protected Probe() {}

    Probe(Long id, String name) {
      this.id = id;
      this.name = name;
    }
  }
}
