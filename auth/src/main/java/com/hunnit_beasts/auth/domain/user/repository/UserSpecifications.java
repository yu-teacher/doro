package com.hunnit_beasts.auth.domain.user.repository;

import com.hunnit_beasts.auth.domain.credential.entity.Credential;
import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.entity.UserRole;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Locale;

/**
 * 관리자 사용자 목록의 검색·필터 조건. credential 은 User 와 JPA 연관이 없어 서브쿼리(EXISTS)로 확인한다.
 * 의미는 웹 화면이 브라우저에서 하던 필터와 같다(이름·이메일 부분 일치, 2FA, 정지·잠김).
 */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    /** 조건 없음(모든 사용자). */
    private static Specification<User> all() {
        return (root, query, cb) -> cb.conjunction();
    }

    /** 이름 또는 이메일에 q 가 들어 있는 사용자(대소문자 무시). q 가 비어 있으면 모든 사용자. */
    public static Specification<User> matchesQuery(String q) {
        if (q == null || q.isBlank()) {
            return all();
        }
        String like = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("email")), like, '\\'),
                cb.like(cb.lower(root.get("name")), like, '\\'));
    }

    public static Specification<User> withFilter(AdminUserFilter filter) {
        if (filter == null) {
            return all();
        }
        return switch (filter) {
            case ALL -> all();
            case SUPER_ADMIN -> hasRole(UserRole.SUPER_ADMIN);
            case ADMIN -> hasRole(UserRole.ADMIN);
            case USER -> hasRole(UserRole.USER);
            case TOTP -> hasTotp();
            case SUSPENDED -> isSuspendedOrLocked();
        };
    }

    private static Specification<User> hasRole(UserRole role) {
        return (root, query, cb) -> cb.equal(root.get("role"), role);
    }

    private static Specification<User> hasTotp() {
        return (root, query, cb) -> {
            Subquery<Integer> sq = query.subquery(Integer.class);
            Root<Credential> c = sq.from(Credential.class);
            sq.select(cb.literal(1)).where(
                    cb.equal(c.get("userId"), root.get("id")),
                    cb.isNotNull(c.get("totpSecret")),
                    cb.notEqual(cb.trim(c.<String>get("totpSecret")), ""));
            return cb.exists(sq);
        };
    }

    private static Specification<User> isSuspendedOrLocked() {
        return (root, query, cb) -> {
            Subquery<Integer> sq = query.subquery(Integer.class);
            Root<Credential> c = sq.from(Credential.class);
            sq.select(cb.literal(1)).where(
                    cb.equal(c.get("userId"), root.get("id")),
                    cb.isNotNull(c.get("lockedUntil")),
                    cb.greaterThan(c.<Instant>get("lockedUntil"), Instant.now()));
            Predicate suspended = cb.equal(root.get("status"), UserStatus.SUSPENDED);
            return cb.or(suspended, cb.exists(sq));
        };
    }

    /** LIKE 의 와일드카드(%, _)와 이스케이프 문자를 문자 그대로 검색하게 한다. */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
