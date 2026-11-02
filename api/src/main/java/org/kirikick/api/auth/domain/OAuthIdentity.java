package org.kirikick.api.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.common.exception.DomainException;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
    name = "oauth_identities",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_oauth_identities_provider_subject",
          columnNames = {"provider", "provider_subject"}),
      @UniqueConstraint(
          name = "uk_oauth_identities_user_provider",
          columnNames = {"user_id", "provider"})
    })
public class OAuthIdentity {

  private static final int PROVIDER_SUBJECT_MAX_LENGTH = 255;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider", nullable = false, updatable = false, length = 20)
  private OAuthProvider provider;

  @Column(
      name = "provider_subject",
      nullable = false,
      updatable = false,
      length = PROVIDER_SUBJECT_MAX_LENGTH)
  private String providerSubject;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  private OAuthIdentity(Long userId, OAuthProvider provider, String providerSubject) {
    if (userId == null || userId <= 0) {
      throw new DomainException(AuthErrorCode.INVALID_USER_ID);
    }
    if (provider == null) {
      throw new DomainException(AuthErrorCode.INVALID_PROVIDER);
    }
    if (providerSubject == null
        || providerSubject.isBlank()
        || providerSubject.length() > PROVIDER_SUBJECT_MAX_LENGTH) {
      throw new DomainException(AuthErrorCode.INVALID_PROVIDER_SUBJECT);
    }

    this.userId = userId;
    this.provider = provider;
    this.providerSubject = providerSubject;
  }

  public static OAuthIdentity create(Long userId, OAuthProvider provider, String providerSubject) {
    return new OAuthIdentity(userId, provider, providerSubject);
  }
}
