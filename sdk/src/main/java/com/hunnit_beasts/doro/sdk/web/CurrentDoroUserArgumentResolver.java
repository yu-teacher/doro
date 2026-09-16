package com.hunnit_beasts.doro.sdk.web;

import com.hunnit_beasts.doro.sdk.annotation.CurrentDoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

public class CurrentDoroUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentDoroUser.class) &&
                (DoroUser.class.isAssignableFrom(parameter.getParameterType()) ||
                 UUID.class.isAssignableFrom(parameter.getParameterType()) ||
                 String.class.isAssignableFrom(parameter.getParameterType()));
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        DoroUser currentUser = DoroUserContext.getCurrentUser();
        Class<?> paramType = parameter.getParameterType();

        if (DoroUser.class.isAssignableFrom(paramType)) {
            return currentUser;
        }
        if (UUID.class.isAssignableFrom(paramType)) {
            return currentUser.userId();
        }
        if (String.class.isAssignableFrom(paramType)) {
            return currentUser.userId() != null ? currentUser.userId().toString() : null;
        }

        return null;
    }
}
