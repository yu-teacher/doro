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
class CheckEngineTtuTest {

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
    @DisplayName("TTU(Tuple-to-Userset) 계층 상속: 루트 폴더의 뷰어가 하위 문서의 viewer 권한을 재귀 상속받는지 검증")
    void testHierarchicalFolderDocumentTtuInheritance() {
        // 1. folder:root 의 viewer 는 user:david
        // 2. folder:sub 의 parent 는 folder:root
        // 3. doc:spec 의 parent 는 folder:sub
        tupleService.writeTuples(List.of(
                TupleDto.of("folder", "root", "viewer", "user", "david"),
                TupleDto.of("folder", "sub", "parent", "folder", "root"),
                TupleDto.of("document", "spec", "parent", "folder", "sub")
        ));

        // David가 folder:sub 의 viewer 권한을 상속받았는지 확인 (parent#viewer)
        CheckEngine.CheckResult subFolderCheck = checkEngine.check("folder", "sub", "viewer", "user", "david", null);
        assertThat(subFolderCheck.allowed()).isTrue();

        // David가 doc:spec 의 viewer 권한을 2단계 상속받았는지 확인 (doc:spec -> folder:sub -> folder:root)
        CheckEngine.CheckResult docCheck = checkEngine.check("document", "spec", "viewer", "user", "david", null);
        assertThat(docCheck.allowed()).isTrue();

        // 다른 무관한 유저(eve)는 접근 불가
        CheckEngine.CheckResult eveCheck = checkEngine.check("document", "spec", "viewer", "user", "eve", null);
        assertThat(eveCheck.allowed()).isFalse();
    }
}
