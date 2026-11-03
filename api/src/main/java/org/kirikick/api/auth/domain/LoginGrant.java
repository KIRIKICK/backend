package org.kirikick.api.auth.domain;

public record LoginGrant(LoginSession session, String accessToken, String refreshToken) {
  @Override
  public String toString() {
    return "LoginGrant[session=" + session + ", accessToken=<redacted>, refreshToken=<redacted>]";
  }
}
