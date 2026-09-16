package com.hunnit_beasts.guard.core.engine;

import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import com.hunnit_beasts.guard.domain.tuple.service.TupleService;
import com.hunnit_beasts.guard.domain.tuple.repository.RelationTupleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CheckEngineComplexGraphEdgeCaseTest {

    @Autowired
    private CheckEngine checkEngine;

    @Autowired
    private TupleService tupleService;

    @Autowired
    private RelationTupleRepository tupleRepository;

    @Autowired
    private SchemaService schemaService;

    @BeforeEach
    void setUp() {
        tupleRepository.deleteAll();
        checkEngine.invalidateCache();
        schemaService.resetToDefault();
    }

    @Test
    @DisplayName("엣지케이스: 3자간 순환 참조 (A -> B -> C -> A) 시 무한루프 없이 0.05초 내 안전 탈출")
    void testThreeWayCircularReferenceCycleBreak() {
        // teamA -> teamB -> teamC -> teamA 순환
        tupleService.writeTuples(List.of(
                TupleDto.of("group", "teamA", "member", "group", "teamB", "member"),
                TupleDto.of("group", "teamB", "member", "group", "teamC", "member"),
                TupleDto.of("group", "teamC", "member", "group", "teamA", "member")
        ));

        long startTime = System.currentTimeMillis();
        CheckEngine.CheckResult result = checkEngine.check("group", "teamA", "member", "user", "unrelated_user", null);
        long elapsed = System.currentTimeMillis() - startTime;

        assertThat(result.allowed()).isFalse();
        assertThat(elapsed).isLessThan(500);
    }

    @Test
    @DisplayName("엣지케이스: 자기 자신 순환 참조 (Self-Loop A -> A) 시 즉시 루프 차단")
    void testSelfLoopCycleBreak() {
        tupleService.writeTuples(List.of(
                TupleDto.of("group", "selfGroup", "member", "group", "selfGroup", "member")
        ));

        CheckEngine.CheckResult result = checkEngine.check("group", "selfGroup", "member", "user", "any_user", null);
        assertThat(result.allowed()).isFalse();
    }

    @Test
    @DisplayName("엣지케이스: 10단계 초심층 폴더 상속 (Folder1 -> ... -> Folder10 -> Doc) 정상 상속 검증")
    void testDeepTenLevelFolderHierarchy() {
        List<TupleDto> tuples = new ArrayList<>();
        // Root folder: f-1 에 admin 유저 등록
        tuples.add(TupleDto.of("folder", "f-1", "viewer", "user", "super_admin"));

        // f-2부터 f-10까지 부모 연결
        for (int i = 2; i <= 10; i++) {
            tuples.add(TupleDto.of("folder", "f-" + i, "parent", "folder", "f-" + (i - 1)));
        }
        // 최종 문서 doc-deep 의 부모는 f-10
        tuples.add(TupleDto.of("document", "doc-deep", "parent", "folder", "f-10"));

        tupleService.writeTuples(tuples);

        // super_admin은 f-1에만 등록되었으나, 10단계를 거쳐 doc-deep의 viewer 권한을 획득해야 함
        CheckEngine.CheckResult result = checkEngine.check("document", "doc-deep", "viewer", "user", "super_admin", null);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    @DisplayName("엣지케이스: 차집합(-) 연산자 - 그룹 멤버이지만 차단 목록(blocked)에 있으면 접근 거부")
    void testDifferenceOperatorBlockedUser() {
        String dsl = """
                type user {}
                type repository {
                  relation raw_member: user
                  relation blocked: user
                  relation member: raw_member - blocked
                }
                """;
        schemaService.registerSchema(dsl);

        // alice와 bob 둘 다 raw_member에 등록되지만, bob은 blocked에도 등록됨
        tupleService.writeTuples(List.of(
                TupleDto.of("repository", "repo-1", "raw_member", "user", "alice"),
                TupleDto.of("repository", "repo-1", "raw_member", "user", "bob"),
                TupleDto.of("repository", "repo-1", "blocked", "user", "bob")
        ));

        // Alice: member 승인
        CheckEngine.CheckResult aliceCheck = checkEngine.check("repository", "repo-1", "member", "user", "alice", null);
        assertThat(aliceCheck.allowed()).isTrue();

        // Bob: 차단 목록에 있으므로 member 거부
        CheckEngine.CheckResult bobCheck = checkEngine.check("repository", "repo-1", "member", "user", "bob", null);
        assertThat(bobCheck.allowed()).isFalse();
    }

    @Test
    @DisplayName("엣지케이스: 교집합(&) 연산자 - employee 와 nda_signed 둘 다 만족해야만 secret_project 접근 승인")
    void testIntersectionOperatorBothConditionsRequired() {
        String dsl = """
                type user {}
                type project {
                  relation employee: user
                  relation nda_signed: user
                  relation viewer: employee & nda_signed
                }
                """;
        schemaService.registerSchema(dsl);

        // charlie: 직원 O, NDA X
        // david: 직원 X, NDA O
        // elena: 직원 O, NDA O
        tupleService.writeTuples(List.of(
                TupleDto.of("project", "proj-top-secret", "employee", "user", "charlie"),
                TupleDto.of("project", "proj-top-secret", "nda_signed", "user", "david"),
                TupleDto.of("project", "proj-top-secret", "employee", "user", "elena"),
                TupleDto.of("project", "proj-top-secret", "nda_signed", "user", "elena")
        ));

        // charlie: 조건 미달 -> 거부
        assertThat(checkEngine.check("project", "proj-top-secret", "viewer", "user", "charlie", null).allowed()).isFalse();

        // david: 조건 미달 -> 거부
        assertThat(checkEngine.check("project", "proj-top-secret", "viewer", "user", "david", null).allowed()).isFalse();

        // elena: 두 조건 모두 만족 -> 승인
        assertThat(checkEngine.check("project", "proj-top-secret", "viewer", "user", "elena", null).allowed()).isTrue();
    }
}
