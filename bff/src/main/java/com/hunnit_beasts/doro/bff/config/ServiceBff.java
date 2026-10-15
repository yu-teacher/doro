package com.hunnit_beasts.doro.bff.config;

import com.hunnit_beasts.doro.sdk.bff.BffAuthService;
import com.hunnit_beasts.doro.sdk.bff.BffController;
import com.hunnit_beasts.doro.sdk.bff.BffCookies;
import com.hunnit_beasts.doro.sdk.bff.BffProperties;

/** 한 서비스의 BFF 구성 요소 묶음. */
public record ServiceBff(String name, BffProperties props, BffCookies cookies, BffAuthService auth, BffController controller) {}
