package org.kirikick.spike.publication.replacement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity(name = "PublicationReplacementSpikeRecord")
@Table(name = "publication_replacement_spike_records")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublicationReplacementSpikeRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "request_key", nullable = false)
  private UUID requestKey;

  @Column(nullable = false, columnDefinition = "text")
  private String state;

  @Column(nullable = false)
  private long attempt;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "terminal_at")
  private Instant terminalAt;

  @Column(name = "blocked_until")
  private Instant blockedUntil;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "game_id")
  private Long gameId;

  public static PublicationReplacementSpikeRecord createProcessing(
      Long userId, UUID requestKey, long attempt, Instant startedAt) {
    PublicationReplacementSpikeRecord record = new PublicationReplacementSpikeRecord();
    record.userId = userId;
    record.requestKey = requestKey;
    record.state = "PROCESSING";
    record.attempt = attempt;
    record.startedAt = startedAt;
    return record;
  }
}
