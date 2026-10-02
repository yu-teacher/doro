package com.hunnit_beasts.auth.domain.user.repository;

import com.hunnit_beasts.auth.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * 대소문자를 구분하지 않는 이메일 조회. 과거 데이터에 대소문자만 다른 행이 공존할 수 있으므로
     * 목록(가장 오래된 순)으로 돌려주고 호출 측에서 정확히 일치하는 행을 우선 선택한다.
     */
    @Query("SELECT u FROM User u WHERE lower(u.email) = lower(:email) ORDER BY u.createdAt ASC")
    List<User> findAllByEmailIgnoreCase(@Param("email") String email);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    /** 입력과 정확히 같은 이메일을 우선하고, 없으면 대소문자만 다른 가장 오래된 계정을 선택한다. */
    default Optional<User> findByEmailIgnoreCase(String email) {
        if (email == null) {
            return Optional.empty();
        }
        String wanted = email.trim();
        List<User> candidates = findAllByEmailIgnoreCase(wanted);
        return candidates.stream()
                .filter(u -> u.getEmail().equals(wanted))
                .findFirst()
                .or(() -> candidates.stream().findFirst());
    }
}
