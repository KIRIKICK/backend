package org.kirikick.api.user.domain;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.kirikick.api.common.entity.BaseTimeEntity;
import org.kirikick.api.common.exception.DomainException;
import org.kirikick.api.user.domain.exception.UserErrorCode;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
    name = "users",
    uniqueConstraints = @UniqueConstraint(name = "uk_users_uuid", columnNames = "uuid"))
public class User extends BaseTimeEntity {

  private static final int DISPLAY_NAME_MAX_CODE_POINTS = 10;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "uuid", nullable = false, updatable = false)
  private UUID uuid;

  @Column(name = "display_name", nullable = false, columnDefinition = "TEXT")
  private String displayName;

  @Column(name = "profile_image_url", columnDefinition = "TEXT")
  private String profileImageUrl;

  @Column(name = "birth_year")
  private Integer birthYear;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private UserStatus status;

  @Column(name = "withdrawn_at")
  private Instant withdrawnAt;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Private constructor validates domain invariants.")
  private User(String displayName, String profileImageUrl) {
    validateDisplayName(displayName);

    this.uuid = UUID.randomUUID();
    this.displayName = displayName;
    this.profileImageUrl = profileImageUrl;
    this.status = UserStatus.ACTIVE;
  }

  public static User create(String displayName, String profileImageUrl) {
    return new User(displayName, profileImageUrl);
  }

  private static void validateDisplayName(String displayName) {
    if (displayName == null
        || displayName.isBlank()
        || displayName.codePointCount(0, displayName.length()) > DISPLAY_NAME_MAX_CODE_POINTS
        || !displayName.matches("[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9 _-]+")
        || !displayName.matches(".*[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9].*")
        || displayName.contains("  ")) {
      throw new DomainException(UserErrorCode.INVALID_DISPLAY_NAME);
    }
  }
}
