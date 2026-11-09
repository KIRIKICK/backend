package org.kirikick.api.auth.domain;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.common.entity.BaseTimeEntity;
import org.kirikick.api.common.exception.DomainException;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "signup_applications")
public class SignupApplication extends BaseTimeEntity {

  private static final long EXPIRY_SECONDS = 300;

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 20)
  private OAuthProvider provider;

  @Column(name = "provider_subject", nullable = false, updatable = false, length = 255)
  private String providerSubject;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "completed_user_id")
  private Long completedUserId;

  @Column(name = "completed_display_name", columnDefinition = "TEXT")
  private String completedDisplayName;

  @Column(name = "completed_at")
  private Instant completedAt;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "Private constructor validates domain invariants.")
  private SignupApplication(
      UUID id, OAuthProvider provider, String providerSubject, Instant issuedAt) {
    if (id == null
        || provider == null
        || providerSubject == null
        || providerSubject.isBlank()
        || providerSubject.length() > 255
        || issuedAt == null) {
      throw new DomainException(AuthErrorCode.INVALID_SIGNUP_APPLICATION);
    }
    this.id = id;
    this.provider = provider;
    this.providerSubject = providerSubject;
    this.expiresAt = issuedAt.plusSeconds(EXPIRY_SECONDS);
  }

  public static SignupApplication open(
      UUID id, OAuthProvider provider, String providerSubject, Instant issuedAt) {
    return new SignupApplication(id, provider, providerSubject, issuedAt);
  }

  public boolean isCompleted() {
    return completedUserId != null;
  }

  public boolean matchesIdentity(OAuthProvider provider, String providerSubject) {
    return this.provider == provider && Objects.equals(this.providerSubject, providerSubject);
  }

  public boolean isExpiredAt(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public boolean isCompletedWithDisplayName(String displayName) {
    return isCompleted() && Objects.equals(completedDisplayName, displayName);
  }

  public void complete(Long userId, String displayName, Instant completedAt) {
    if (isCompleted()) {
      throw new DomainException(AuthErrorCode.SIGNUP_APPLICATION_ALREADY_COMPLETED);
    }
    if (userId == null
        || userId <= 0
        || displayName == null
        || displayName.isBlank()
        || completedAt == null) {
      throw new DomainException(AuthErrorCode.INVALID_SIGNUP_APPLICATION_COMPLETION);
    }
    this.completedUserId = userId;
    this.completedDisplayName = displayName;
    this.completedAt = completedAt;
  }
}
