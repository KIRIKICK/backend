package org.kirikick.spike.n1.game.service;

import org.kirikick.api.ApiApplication;
import org.kirikick.api.support.H2JpaTest;
import org.kirikick.spike.n1.N1JpaTestConfiguration;
import org.springframework.test.context.ContextConfiguration;

@ContextConfiguration(classes = {ApiApplication.class, N1JpaTestConfiguration.class})
@H2JpaTest
class GameAssemblyServiceTest extends GameAssemblyContract {}
