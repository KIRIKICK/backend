package org.kirikick.api.auth.domain;

public record IssuedSession(LoginSession session, String refreshToken) {

  @Override
  public String toString() {
    return "IssuedSession[session=" + session + ", refreshToken=<redacted>]";
  }
}
