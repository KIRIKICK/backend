package org.kirikick.api.auth.controller.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}
