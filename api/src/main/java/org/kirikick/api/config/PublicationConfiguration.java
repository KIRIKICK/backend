package org.kirikick.api.config;

import org.kirikick.api.publication.service.AddKickToRoomService;
import org.kirikick.api.publication.service.OpenRoomService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Conditional(PublicationEnabledCondition.class)
@EnableConfigurationProperties(PublicationProperties.class)
@Import({OpenRoomService.class, AddKickToRoomService.class})
public class PublicationConfiguration {}
