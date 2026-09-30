-- G5: 직접 튜플(subject_relation IS NULL)의 중복을 DB 레벨에서 막는 부분 유니크 인덱스 (PostgreSQL 전용)
CREATE UNIQUE INDEX IF NOT EXISTS uq_relation_tuple_direct
ON relation_tuples (namespace, object_id, relation, subject_namespace, subject_id)
WHERE subject_relation IS NULL;
