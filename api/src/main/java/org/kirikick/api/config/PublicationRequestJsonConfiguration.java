package org.kirikick.api.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.List;
import org.kirikick.api.publication.controller.dto.NkCreateRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Applies strict JSON coercion and unknown-property handling only to publication requests. */
@Configuration(proxyBeanMethods = false)
@Conditional(PublicationEnabledCondition.class)
public class PublicationRequestJsonConfiguration implements WebMvcConfigurer {

  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    for (HttpMessageConverter<?> converter : converters) {
      if (converter instanceof MappingJackson2HttpMessageConverter jackson) {
        ObjectMapper mapper = strictMapper(jackson.getObjectMapper());
        jackson.registerObjectMappersForType(
            NkCreateRequest.class,
            mappers ->
                jackson
                    .getSupportedMediaTypes()
                    .forEach(mediaType -> mappers.put(mediaType, mapper)));
      }
    }
  }

  private static ObjectMapper strictMapper(ObjectMapper source) {
    ObjectMapper mapper = source.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    return mapper;
  }
}
