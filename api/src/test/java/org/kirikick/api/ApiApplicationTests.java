package org.kirikick.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kirikick.api.auth.support.AuthTestSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiApplicationTests {

  @DynamicPropertySource
  static void authSettings(DynamicPropertyRegistry registry) {
    AuthTestSettings.register(registry);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  void contextLoads() {}

  @Test
  void appliesTheDefaultBatchFetchSize() {
    try (Session session = entityManagerFactory.unwrap(SessionFactory.class).openSession()) {
      assertThat(session.getFetchBatchSize()).isEqualTo(100);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"liveness", "readiness"})
  void exposesProbesWithoutAuthenticationOrInternalDetails(String probe) throws Exception {
    mockMvc
        .perform(get("/actuator/health/" + probe))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist())
        .andExpect(jsonPath("$.details").doesNotExist());
  }
}
