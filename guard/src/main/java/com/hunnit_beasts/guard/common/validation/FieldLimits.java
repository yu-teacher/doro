package com.hunnit_beasts.guard.common.validation;

import com.hunnit_beasts.guard.common.exception.ErrorCode;
import com.hunnit_beasts.guard.common.exception.GuardException;

/**
 * relation_tuples 컬럼 길이(V1__init_guard_schema.sql)와 일치하는 입력 한계.
 * REST(DTO 애너테이션)와 gRPC(수동 검증)가 같은 상수를 사용해 동작을 맞춘다.
 */
public final class FieldLimits {

    /** namespace, relation, subject_namespace, subject_relation 컬럼 길이 */
    public static final int NAME_MAX = 64;
    /** object_id, subject_id 컬럼 길이 */
    public static final int ID_MAX = 128;

    private FieldLimits() {
    }

    /** gRPC 등 Bean Validation 을 거치지 않는 경로용. 위반 시 INVALID_INPUT(400) 으로 던진다. */
    public static void requireTuple(String namespace, String objectId, String relation,
                                    String subjectNamespace, String subjectId, String subjectRelation) {
        requireObject(namespace, objectId, relation);
        required("subject_namespace", subjectNamespace, NAME_MAX);
        required("subject_id", subjectId, ID_MAX);
        optional("subject_relation", subjectRelation, NAME_MAX);
    }

    public static void requireObject(String namespace, String objectId, String relation) {
        required("namespace", namespace, NAME_MAX);
        required("object_id", objectId, ID_MAX);
        required("relation", relation, NAME_MAX);
    }

    private static void required(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            throw new GuardException(ErrorCode.INVALID_INPUT, field + " 는 비어 있을 수 없습니다.");
        }
        optional(field, value, max);
    }

    private static void optional(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new GuardException(ErrorCode.INVALID_INPUT, field + " 는 " + max + "자를 넘을 수 없습니다.");
        }
    }
}
