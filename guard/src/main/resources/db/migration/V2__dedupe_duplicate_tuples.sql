-- G5: subject_relation 이 NULL 인 직접 튜플은 PostgreSQL 의 NULL 규칙 때문에 기존 유니크 인덱스로 중복이 막히지 않았다.
-- 완전히 동일한 튜플이 중복된 행은 백업 테이블에 보관한 뒤 가장 오래된 하나만 남기고 정리한다.
-- (이 스크립트는 PostgreSQL/H2 공통 SQL 이다. 직접 튜플 부분 유니크 인덱스는 postgresql/V3 에 있다.)

CREATE TABLE IF NOT EXISTS relation_tuples_duplicates_backup AS
SELECT * FROM relation_tuples WHERE 1 = 0;

INSERT INTO relation_tuples_duplicates_backup
SELECT a.*
FROM relation_tuples a
WHERE EXISTS (
    SELECT 1 FROM relation_tuples b
    WHERE b.namespace = a.namespace
      AND b.object_id = a.object_id
      AND b.relation = a.relation
      AND b.subject_namespace = a.subject_namespace
      AND b.subject_id = a.subject_id
      AND b.subject_relation IS NOT DISTINCT FROM a.subject_relation
      AND (b.created_at < a.created_at OR (b.created_at = a.created_at AND b.id < a.id))
);

DELETE FROM relation_tuples
WHERE id IN (
    SELECT a.id
    FROM relation_tuples a
    WHERE EXISTS (
        SELECT 1 FROM relation_tuples b
        WHERE b.namespace = a.namespace
          AND b.object_id = a.object_id
          AND b.relation = a.relation
          AND b.subject_namespace = a.subject_namespace
          AND b.subject_id = a.subject_id
          AND b.subject_relation IS NOT DISTINCT FROM a.subject_relation
          AND (b.created_at < a.created_at OR (b.created_at = a.created_at AND b.id < a.id))
    )
);
