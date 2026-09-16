-- Doro Guard Initial Schema

CREATE TABLE IF NOT EXISTS relation_tuples (
    id UUID PRIMARY KEY,
    namespace VARCHAR(64) NOT NULL,
    object_id VARCHAR(128) NOT NULL,
    relation VARCHAR(64) NOT NULL,
    subject_namespace VARCHAR(64) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    subject_relation VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 정방향 조회 인덱스 (object -> relation -> subject)
CREATE INDEX IF NOT EXISTS idx_tuples_forward ON relation_tuples(namespace, object_id, relation);

-- 역방향 조회 인덱스 (subject -> object)
CREATE INDEX IF NOT EXISTS idx_tuples_reverse ON relation_tuples(subject_namespace, subject_id, subject_relation);

-- 고유 제약 인덱스
CREATE UNIQUE INDEX IF NOT EXISTS uq_relation_tuple 
ON relation_tuples (namespace, object_id, relation, subject_namespace, subject_id, subject_relation);

-- 스키마 DSL 정의 저장 테이블
CREATE TABLE IF NOT EXISTS schema_definitions (
    id UUID PRIMARY KEY,
    version INT NOT NULL UNIQUE,
    dsl_text TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
