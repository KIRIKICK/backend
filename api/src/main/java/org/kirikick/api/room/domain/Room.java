package org.kirikick.api.room.domain;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.kirikick.api.common.entity.BaseTimeEntity;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.room.domain.exception.RoomErrorCode;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
    name = "rooms",
    uniqueConstraints = @UniqueConstraint(name = "uk_rooms_uuid", columnNames = "uuid"))
public class Room extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, updatable = false)
  private UUID uuid;

  @Column(name = "owner_id", nullable = false)
  private Long ownerId;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String name;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Private constructor validates domain invariants.")
  private Room(Long ownerId, String name) {
    if (ownerId == null || ownerId <= 0 || name == null || name.isBlank()) {
      throw new DomainException(RoomErrorCode.INVALID_ROOM);
    }
    this.uuid = UUID.randomUUID();
    this.ownerId = ownerId;
    this.name = name;
  }

  public static Room create(Long ownerId, String name) {
    return new Room(ownerId, name);
  }
}
