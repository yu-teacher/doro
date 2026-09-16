package com.hunnit_beasts.guard.core.engine;

import com.hunnit_beasts.guard.core.dsl.service.SchemaService;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import com.hunnit_beasts.guard.domain.tuple.repository.RelationTupleRepository;
import com.hunnit_beasts.guard.domain.tuple.service.TupleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CheckEngineDirectAndRewriteTest {

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
    @DisplayName("직접 권한 & Userset Rewrite(상속): owner가 자동으로 editor 및 viewer 권한을 획득하는지 검증")
    void testOwnerInheritsEditorAndViewer() {
        // doc:readme#owner@user:alice 등록
        tupleService.writeTuples(List.of(
                TupleDto.of("document", "readme", "owner", "user", "alice")
        ));

        // 1. owner 검증 -> TRUE
        CheckEngine.CheckResult ownerCheck = checkEngine.check("document", "readme", "owner", "user", "alice", null);
        assertThat(ownerCheck.allowed()).isTrue();

        // 2. editor 검증 (editor 규칙에 owner 포함) -> TRUE
        CheckEngine.CheckResult editorCheck = checkEngine.check("document", "readme", "editor", "user", "alice", null);
        assertThat(editorCheck.allowed()).isTrue();

        // 3. viewer 검증 (viewer 규칙에 editor 포함) -> TRUE
        CheckEngine.CheckResult viewerCheck = checkEngine.check("document", "readme", "viewer", "user", "alice", null);
        assertThat(viewerCheck.allowed()).isTrue();

        // 4. 무관한 유저(mallory) -> FALSE
        CheckEngine.CheckResult malloryCheck = checkEngine.check("document", "readme", "viewer", "user", "mallory", null);
        assertThat(malloryCheck.allowed()).isFalse();
    }

    @Test
    @DisplayName("중첩 Userset 권한: 그룹 멤버(group:eng#member)가 문서의 권한을 상속받는지 검증")
    void testGroupMembershipInheritance() {
        // group:eng#member@user:bob
        // doc:design#editor@group:eng#member
        tupleService.writeTuples(List.of(
                TupleDto.of("group", "eng", "member", "user", "bob"),
                TupleDto.of("document", "design", "editor", "group", "eng", "member")
        ));

        // Bob은 그룹 멤버이므로 doc:design의 editor 권한이 있어야 함
        CheckEngine.CheckResult bobEditor = checkEngine.check("document", "design", "editor", "user", "bob", null);
        assertThat(bobEditor.allowed()).isTrue();

        // Bob은 editor이므로 viewer 권한도 있어야 함
        CheckEngine.CheckResult bobViewer = checkEngine.check("document", "design", "viewer", "user", "bob", null);
        assertThat(bobViewer.allowed()).isTrue();

        // 그룹에 속하지 않은 Charlie는 접근 불가
        CheckEngine.CheckResult charlieViewer = checkEngine.check("document", "design", "viewer", "user", "charlie", null);
        assertThat(charlieViewer.allowed()).isFalse();
    }
}
