package com.hunnit_beasts.doro.sdk.aop;

import com.hunnit_beasts.doro.sdk.annotation.DoroGuard;
import com.hunnit_beasts.doro.sdk.client.DoroGuardClient;
import com.hunnit_beasts.doro.sdk.domain.DoroUser;
import com.hunnit_beasts.doro.sdk.domain.DoroUserContext;
import com.hunnit_beasts.doro.sdk.exception.DoroAccessDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

@Slf4j
@Aspect
public class DoroGuardAspect {

    private final DoroGuardClient guardClient;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

    /** guardClient 가 null 이면(doro.guard.enabled=false) 보호 대상 호출은 fail-closed 로 거부한다. */
    public DoroGuardAspect(DoroGuardClient guardClient) {
        this.guardClient = guardClient;
    }

    /** 메서드 레벨 @DoroGuard */
    @Around("@annotation(doroGuard)")
    public Object enforcePermission(ProceedingJoinPoint joinPoint, DoroGuard doroGuard) throws Throwable {
        return enforce(joinPoint, doroGuard);
    }

    /** 클래스 레벨 @DoroGuard. 메서드에 별도 @DoroGuard 가 있으면 메서드 쪽이 우선한다. */
    @Around("@within(doroGuard) && !@annotation(com.hunnit_beasts.doro.sdk.annotation.DoroGuard)")
    public Object enforceClassPermission(ProceedingJoinPoint joinPoint, DoroGuard doroGuard) throws Throwable {
        return enforce(joinPoint, doroGuard);
    }

    private Object enforce(ProceedingJoinPoint joinPoint, DoroGuard doroGuard) throws Throwable {
        if (guardClient == null) {
            log.error("@DoroGuard is used but doro.guard.enabled=false; denying request (fail-closed)");
            throw new DoroAccessDeniedException("Doro Guard 가 비활성화되어 보호된 리소스에 접근할 수 없습니다.");
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();

        EvaluationContext context = createEvaluationContext(method, args);

        String namespace;
        String objectId;
        String relation;
        String subjectId;

        // 1. 단축형 표현식(value) 파싱 (예: "document:#docId#editor")
        if (!doroGuard.value().isBlank()) {
            String rawPattern = doroGuard.value();
            String[] firstSplit = rawPattern.split(":", 2);
            if (firstSplit.length < 2) {
                throw new IllegalArgumentException("Invalid @DoroGuard value pattern: " + rawPattern);
            }
            namespace = firstSplit[0].trim();
            String remainder = firstSplit[1].trim();

            int lastHash = remainder.lastIndexOf('#');
            if (lastHash == -1) {
                throw new IllegalArgumentException("Invalid @DoroGuard value pattern (missing #relation): " + rawPattern);
            }
            String rawObjectExpr = remainder.substring(0, lastHash).trim();
            relation = remainder.substring(lastHash + 1).trim();
            objectId = resolveSpel(rawObjectExpr, context);
        } else {
            namespace = doroGuard.namespace();
            objectId = resolveSpel(doroGuard.object(), context);
            relation = doroGuard.relation();
        }

        // 2. Subject ID 결정 (지정되지 않았을 시 현재 DoroUserContext의 userId 사용)
        if (!doroGuard.subject().isBlank()) {
            subjectId = resolveSpel(doroGuard.subject(), context);
        } else {
            DoroUser currentUser = DoroUserContext.getCurrentUser();
            if (!currentUser.isAuthenticated()) {
                throw new DoroAccessDeniedException("인증되지 않은 사용자입니다.");
            }
            subjectId = currentUser.userId().toString();
        }

        // 3. Doro Guard gRPC Check 호출. 장애는 거부로 바꾸지 않고 DoroGuardUnavailableException(→ 503)으로 전달한다.
        boolean allowed = guardClient.checkOrThrow(namespace, objectId, relation, subjectId);

        if (!allowed) {
            log.warn("DoroGuard Access Denied for user={} on {}:{}#{}", subjectId, namespace, objectId, relation);
            throw new DoroAccessDeniedException(namespace, objectId, relation, subjectId);
        }

        log.debug("DoroGuard Access Granted for user={} on {}:{}#{}", subjectId, namespace, objectId, relation);
        return joinPoint.proceed();
    }

    private String resolveSpel(String expression, EvaluationContext context) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        if (!expression.startsWith("#")) {
            return expression;
        }
        Object value = parser.parseExpression(expression).getValue(context);
        return value != null ? value.toString() : "";
    }

    private EvaluationContext createEvaluationContext(Method method, Object[] args) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        String[] paramNames = paramNameDiscoverer.getParameterNames(method);
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                context.setVariable("p" + i, args[i]);
                context.setVariable("a" + i, args[i]);
                if (paramNames != null && i < paramNames.length && paramNames[i] != null) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }
            context.setVariable("args", args);
        }
        return context;
    }
}
