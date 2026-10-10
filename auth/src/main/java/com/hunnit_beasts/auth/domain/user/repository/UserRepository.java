package com.hunnit_beasts.auth.domain.user.repository;

import com.hunnit_beasts.auth.domain.user.entity.User;
import com.hunnit_beasts.auth.domain.user.dto.DeletedUserRef;
import com.hunnit_beasts.auth.domain.user.entity.UserStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /** 영구 탈퇴(DELETED)하지 않은 해당 역할 계정 수. 첫 관리자 부트스트랩의 "관리자 없음" 판정에 쓴다. */
    @Query("SELECT COUNT(u) FROM User u WHERE u.role = :role AND u.status <> :deleted")
    long countByRoleExcludingStatus(@Param("role") com.hunnit_beasts.auth.domain.user.entity.UserRole role,
                                    @Param("deleted") UserStatus deleted);

    /** 유예 기간이 지난 탈퇴 대기 계정의 ID(오래된 순). */
    @Query("SELECT u.id FROM User u WHERE u.status = :status AND u.deletionRequestedAt <= :cutoff "
            + "ORDER BY u.deletionRequestedAt ASC")
    List<UUID> findIdsDeletionRequestedBefore(@Param("status") UserStatus status,
                                              @Param("cutoff") Instant cutoff,
                                              Pageable pageable);

    /**
     * 유예 중인 계정만 활성으로 되돌린다. 영구 처리와 동시에 일어나도 먼저 실행된 쪽만 성공하도록
     * 상태 조건을 UPDATE 에 함께 건다. (엔티티를 수정해 저장하면 늦게 커밋된 쪽이 상대의 결과를 덮어쓴다)
     * @return 복구한 행 수 (0 이면 이미 영구 처리됐거나 유예 중이 아니다)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.status = :active, u.deletionRequestedAt = NULL, u.updatedAt = :now "
            + "WHERE u.id = :id AND u.status = :pending")
    int restoreIfPendingDeletion(@Param("id") UUID id,
                                 @Param("active") UserStatus active,
                                 @Param("pending") UserStatus pending,
                                 @Param("now") Instant now);

    /**
     * 유예가 끝난 계정의 개인정보를 지우고 DELETED 로 바꾼다. 조건부 UPDATE 라 복구와 경쟁해도 한쪽만 이긴다.
     * @return 처리한 행 수 (0 이면 그사이 복구됐거나 이미 처리됐다)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.email = :email, u.name = :name, u.profileImageUrl = NULL, "
            + "u.status = :deleted, u.deletedAt = :now, u.updatedAt = :now "
            + "WHERE u.id = :id AND u.status = :pending AND u.deletionRequestedAt <= :cutoff")
    int anonymizeIfDue(@Param("id") UUID id,
                       @Param("email") String email,
                       @Param("name") String name,
                       @Param("deleted") UserStatus deleted,
                       @Param("pending") UserStatus pending,
                       @Param("cutoff") Instant cutoff,
                       @Param("now") Instant now);

    /** since 이후(포함)에 영구 탈퇴한 사용자를 오래된 순으로 돌려준다. 개인정보는 선택하지 않는다. */
    @Query("SELECT new com.hunnit_beasts.auth.domain.user.dto.DeletedUserRef(u.id, u.deletedAt) FROM User u "
            + "WHERE u.status = :status AND u.deletedAt >= :since ORDER BY u.deletedAt ASC, u.id ASC")
    List<DeletedUserRef> findDeletedSince(@Param("status") UserStatus status,
                                          @Param("since") Instant since,
                                          Pageable pageable);
}
