package org.kirikick.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.support.AuthTestSettings;
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
          .isEqualTo("#/components/schemas/AuthErrorResponse");
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

  private JsonNode document() throws Exception {
    var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
    return json.readTree(result.getResponse().getContentAsByteArray());
  }
}
