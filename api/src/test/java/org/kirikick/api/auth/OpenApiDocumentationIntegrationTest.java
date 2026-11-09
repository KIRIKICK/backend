package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.domain.exception.AuthErrorCode;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.kirikick.api.common.exception.ErrorCode;
import org.kirikick.api.user.domain.exception.UserErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class OpenApiDocumentationIntegrationTest {
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
    String database = "jdbc:h2:mem:openapi-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    registry.add("spring.datasource.url", () -> database);
  }

  @Test
  void documentsMaskedCsrfTokenAndItsResponseSchema() throws Exception {
    JsonNode document = document();
    JsonNode operation = document.at("/paths/~1auth~1csrf/get");

    assertThat(operation.path("summary").asText()).isEqualTo("CSRF 토큰 조회");
    assertThat(operation.path("description").asText()).contains("X-XSRF-TOKEN", "HttpOnly");
    assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/CsrfResponse");
    assertThat(
            document.at("/components/schemas/CsrfResponse/properties/token/description").asText())
        .contains("마스킹");
  }

  @Test
  void documentsRefreshRequirementsCookiesAndAuthenticationErrors() throws Exception {
    JsonNode document = document();
    JsonNode operation = document.at("/paths/~1auth~1refresh/post");

    assertThat(operation.path("summary").asText()).isEqualTo("Access Token 재발급");
    assertThat(operation.path("description").asText())
        .contains("__Host-refresh", "dev-refresh", "Origin", "Swagger UI");
    assertThat(operation.path("parameters").findValuesAsText("name"))
        .containsExactly("X-XSRF-TOKEN");
    for (JsonNode parameter : operation.path("parameters")) {
      assertThat(parameter.path("in").asText()).isEqualTo("header");
      assertThat(parameter.path("required").asBoolean()).isTrue();
    }
    assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/TokenResponse");
    assertThat(operation.at("/responses/200/headers/Set-Cookie/description").asText())
        .contains("HttpOnly", "SameSite=Strict");
    for (String status : new String[] {"401", "403", "503"}) {
      assertThat(
              operation
                  .at("/responses/" + status + "/content/application~1json/schema/$ref")
                  .asText())
          .isEqualTo("#/components/schemas/ErrorResponse");
    }
    assertThat(
            document.at("/components/schemas/TokenResponse/properties/expiresIn/example").asInt())
        .isEqualTo(900);
    assertThat(
            document.at("/components/schemas/TokenResponse/properties/tokenType/example").asText())
        .isEqualTo("Bearer");
  }

  @Test
  void servesSwaggerUiAndItsConfigurationWithoutAuthenticationLocally() throws Exception {
    mvc.perform(get("/swagger-ui.html"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/swagger-ui/index.html"));
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
  }

  @Test
  void documentsMyProfileResponseAndBearerAuthentication() throws Exception {
    JsonNode operation = document().at("/paths/~1users~1me~1profile/get");

    assertThat(operation.path("summary").asText()).isEqualTo("내 프로필 조회");
    JsonNode errorSchema = document().at("/components/schemas/ErrorResponse");
    assertThat(errorSchema.at("/properties/code/description").asText()).isEqualTo("오류 코드");
    assertThat(errorSchema.at("/properties/message/description").asText()).isEqualTo("오류 사유");
    assertThat(errorSchema.path("required"))
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder("code", "message");
    assertThat(operation.path("parameters")).isEmpty();
    assertThat(operation.path("security").isArray()).isTrue();
    assertThat(operation.path("security").get(0).has("bearerAuth")).isTrue();
    assertThat(document().at("/components/securitySchemes/bearerAuth/type").asText())
        .isEqualTo("http");
    assertThat(document().at("/components/securitySchemes/bearerAuth/scheme").asText())
        .isEqualTo("bearer");
    assertThat(document().at("/components/securitySchemes/bearerAuth/bearerFormat").asText())
        .isEqualTo("JWT");
    assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/UserProfileResponse");
    JsonNode profile = document().at("/components/schemas/UserProfileResponse");
    assertThat(profile.path("properties").size()).isEqualTo(4);
    for (String field :
        new String[] {"uuid", "displayName", "profileImageUrl", "linkedProviders"}) {
      assertThat(profile.path("properties").has(field)).isTrue();
    }
    assertThat(profile.path("required"))
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder("uuid", "displayName", "profileImageUrl", "linkedProviders");
    assertThat(profile.at("/properties/profileImageUrl/type"))
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder("string", "null");
    assertThat(profile.at("/properties/uuid/format").asText()).isEqualTo("uuid");
    assertThat(profile.at("/properties/linkedProviders/items/enum"))
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder("kakao", "apple");
    assertThat(operation.at("/security/0/bearerAuth/0").asText()).isEmpty();
    for (String status : new String[] {"200", "401", "403", "404", "503"}) {
      assertThat(
              operation.at("/responses/" + status + "/headers/Cache-Control/description").asText())
          .contains("no-store");
    }
    assertThat(operation.at("/responses/401/description").asText())
        .contains("AUTHENTICATION_REQUIRED");
    assertThat(operation.at("/responses/403/description").asText())
        .contains("ACCOUNT_INACTIVE", "CORS");
    assertThat(operation.at("/responses/404/description").asText()).contains("ACCOUNT_NOT_FOUND");
    assertThat(operation.at("/responses/503/description").asText())
        .contains("AUTH_STATE_UNAVAILABLE", "PROFILE_UNAVAILABLE");
    assertThat(operation.at("/responses/401/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/ErrorResponse");
    assertThat(operation.at("/responses/403/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/ErrorResponse");
    assertThat(operation.at("/responses/404/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/ErrorResponse");
    assertThat(operation.at("/responses/503/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/ErrorResponse");
  }

  @Test
  void documentsProfileExamplesWithTheCurrentDomainErrorContracts() throws Exception {
    JsonNode operation = document().at("/paths/~1users~1me~1profile/get");
    assertThat(operation.path("description").asText()).contains("JWT", "쿠키", "CSRF", "조회");

    JsonNode profile =
        operation.at("/responses/200/content/application~1json/examples/PROFILE/value");
    assertThat(profile.size()).isEqualTo(4);
    assertThat(profile.has("uuid")).isTrue();
    assertThat(profile.path("displayName").asText()).isNotBlank();
    assertThat(profile.has("profileImageUrl")).isTrue();
    assertThat(profile.get("profileImageUrl").isNull()).isTrue();
    assertThat(profile.path("linkedProviders"))
        .extracting(JsonNode::asText)
        .containsExactly("kakao");

    Map<ErrorCode, String> responses =
        Map.of(
            AuthErrorCode.AUTHENTICATION_REQUIRED, "401",
            AuthErrorCode.ACCOUNT_INACTIVE, "403",
            UserErrorCode.ACCOUNT_NOT_FOUND, "404",
            AuthErrorCode.AUTH_STATE_UNAVAILABLE, "503",
            UserErrorCode.PROFILE_UNAVAILABLE, "503");
    responses.forEach((error, status) -> {
      JsonNode example =
          operation.at(
              "/responses/" + status + "/content/application~1json/examples/" + error + "/value");
      assertThat(example.size()).isEqualTo(2);
      assertThat(example.path("code").asText()).isEqualTo(error.toString());
      assertThat(example.path("message").asText()).isEqualTo(error.getMessage());
    });

    assertThat(operation.at("/responses/403/content/*~1*/schema/type").asText())
        .isEqualTo("string");
    assertThat(operation.at("/responses/403/content/*~1*/schema/example").asText())
        .isEqualTo("Invalid CORS request");
  }

  @Test
  void documentsRequiredSignupValuesAndStrictRequestShape() throws Exception {
    JsonNode document = document();
    JsonNode request = document.at("/components/schemas/SignupRequest");
    JsonNode consents = document.at("/components/schemas/SignupConsents");

    assertThat(request.path("required"))
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder("signupApplicationId", "displayName", "consents");
    assertThat(consents.path("required"))
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder("service", "privacy", "ageConfirmed");
    assertThat(request.path("additionalProperties").asBoolean(true)).isFalse();
    assertThat(consents.path("additionalProperties").asBoolean(true)).isFalse();
    assertThat(request.at("/properties/signupApplicationId/format").asText()).isEqualTo("uuid");
    assertThat(request.at("/properties/displayName/maxLength").asInt()).isEqualTo(10);
    assertThat(request.at("/properties/displayName/description").asText()).contains("원문", "연속 공백");
    assertThat(request.at("/properties/consents/$ref").asText())
        .isEqualTo("#/components/schemas/SignupConsents");
    for (String field : new String[] {"service", "privacy", "ageConfirmed"}) {
      assertThat(consents.at("/properties/" + field + "/type").asText()).isEqualTo("boolean");
      assertThat(consents.at("/properties/" + field + "/description").asText()).contains("true");
    }
  }

  @Test
  void corsRejectionCanReturnFrameworkTextInsteadOfSignupJson() throws Exception {
    mvc.perform(get("/auth/signup/context").header("Origin", "https://untrusted.invalid"))
        .andExpect(status().isForbidden())
        .andExpect(content().string("Invalid CORS request"));
    mvc.perform(post("/auth/signup").header("Origin", "https://untrusted.invalid"))
        .andExpect(status().isForbidden())
        .andExpect(content().string("Invalid CORS request"));
  }

  @Test
  void documentsSignupSessionCsrfAndJsonResponseContracts() throws Exception {
    JsonNode document = document();
    JsonNode context = document.at("/paths/~1auth~1signup~1context/get");
    JsonNode signup = document.at("/paths/~1auth~1signup/post");
    JsonNode contextSchema = document.at("/components/schemas/SignupContextResponse");

    assertThat(contextSchema.path("required"))
        .extracting(JsonNode::asText)
        .contains("signupApplicationId");
    assertThat(contextSchema.at("/properties/suggestedDisplayName/type"))
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder("string", "null");

    assertThat(context.path("description").asText())
        .contains("__Host-onboarding", "dev-onboarding", "credentials: include");
    assertThat(context.at("/responses/200/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/SignupContextResponse");
    for (String code : new String[] {"401", "403", "503"}) {
      assertThat(
              context.at("/responses/" + code + "/content/application~1json/schema/$ref").asText())
          .isEqualTo("#/components/schemas/ErrorResponse");
      assertThat(context.at("/responses/" + code + "/headers/Cache-Control/description").asText())
          .contains("no-store");
    }
    assertThat(signup.path("description").asText())
        .contains(
            "AUTH_FE_ORIGIN", "AUTH_DEV_ADDITIONAL_FE_ORIGINS", "X-XSRF-TOKEN", "토큰을 재발급하지 않습니다");
    assertThat(signup.path("parameters").findValuesAsText("name")).containsExactly("X-XSRF-TOKEN");
    assertThat(signup.at("/parameters/0/in").asText()).isEqualTo("header");
    assertThat(signup.at("/parameters/0/required").asBoolean()).isTrue();
    assertThat(signup.at("/requestBody/required").asBoolean()).isTrue();
    assertThat(signup.at("/requestBody/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/SignupRequest");
    assertThat(signup.at("/responses/201/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/TokenResponse");
    assertThat(signup.at("/responses/201/headers/Set-Cookie/description").asText())
        .contains("HttpOnly", "SameSite=Strict");
    assertThat(signup.at("/responses/201/headers/Cache-Control/description").asText())
        .isEqualTo("no-store");
    assertThat(signup.at("/responses/201/headers/Pragma/description").asText())
        .isEqualTo("no-cache");
    for (String code : new String[] {"400", "401", "403", "409", "503"}) {
      assertThat(
              signup.at("/responses/" + code + "/content/application~1json/schema/$ref").asText())
          .isEqualTo("#/components/schemas/ErrorResponse");
      assertThat(signup.at("/responses/" + code + "/headers/Cache-Control/description").asText())
          .contains("no-store");
      for (JsonNode example :
          signup.at("/responses/" + code + "/content/application~1json/examples")) {
        JsonNode value = example.path("value");
        AuthErrorCode error = AuthErrorCode.valueOf(value.path("code").asText());
        assertThat(value.path("message").asText()).isEqualTo(error.getMessage());
      }
    }
    assertThat(signup.at("/responses/400/description").asText())
        .contains("INVALID_SIGNUP_REQUEST", "INVALID_DISPLAY_NAME");
    assertThat(signup.at("/responses/403/description").asText())
        .contains("AUTHENTICATION_REQUIRED", "AGE_CONFIRMATION_REQUIRED", "ACCOUNT_INACTIVE");
    for (JsonNode operation : new JsonNode[] {context, signup}) {
      assertThat(operation.at("/responses/403/description").asText())
          .contains("CORS", "Invalid CORS request");
      assertThat(operation.at("/responses/403/content/*~1*/schema/type").asText())
          .isEqualTo("string");
      assertThat(operation.at("/responses/403/headers/Cache-Control/description").asText())
          .contains("CORS 사전 거절에는 없을 수 있습니다");
    }
    assertThat(signup.at("/responses/409/description").asText())
        .contains("SIGNUP_APPLICATION_ALREADY_COMPLETED", "OAUTH_IDENTITY_ALREADY_EXISTS");
    assertThat(
            signup.at("/responses/403/content/application~1json/examples").findValuesAsText("code"))
        .containsExactlyInAnyOrder(
            "AUTHENTICATION_REQUIRED", "AGE_CONFIRMATION_REQUIRED", "ACCOUNT_INACTIVE");
    assertThat(
            signup.at("/responses/409/content/application~1json/examples").findValuesAsText("code"))
        .containsExactlyInAnyOrder(
            "SIGNUP_APPLICATION_ALREADY_COMPLETED", "OAUTH_IDENTITY_ALREADY_EXISTS");
  }

  private JsonNode document() throws Exception {
    var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
    return json.readTree(result.getResponse().getContentAsByteArray());
  }
}
