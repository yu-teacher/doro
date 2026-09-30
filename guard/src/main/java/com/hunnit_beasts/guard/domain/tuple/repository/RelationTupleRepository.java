package com.hunnit_beasts.guard.domain.tuple.repository;

import com.hunnit_beasts.guard.domain.tuple.entity.RelationTuple;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RelationTupleRepository extends JpaRepository<RelationTuple, UUID> {

    @Query("SELECT t FROM RelationTuple t WHERE t.namespace = :ns AND t.objectId = :objId AND t.relation = :rel")
    List<RelationTuple> findByObjectAndRelation(
            @Param("ns") String namespace,
            @Param("objId") String objectId,
            @Param("rel") String relation
    );

    @Query("SELECT COUNT(t) > 0 FROM RelationTuple t " +
            "WHERE t.namespace = :ns AND t.objectId = :objId AND t.relation = :rel " +
            "AND t.subjectNamespace = :subNs AND t.subjectId = :subId AND t.subjectRelation IS NULL")
    boolean existsDirectTuple(
            @Param("ns") String namespace,
            @Param("objId") String objectId,
            @Param("rel") String relation,
            @Param("subNs") String subjectNamespace,
            @Param("subId") String subjectId
    );

    @Query("SELECT COUNT(t) > 0 FROM RelationTuple t " +
            "WHERE t.namespace = :ns AND t.objectId = :objId AND t.relation = :rel " +
            "AND t.subjectNamespace = :subNs AND t.subjectId = :subId AND t.subjectRelation = :subRel")
    boolean existsUsersetTuple(
            @Param("ns") String namespace,
            @Param("objId") String objectId,
            @Param("rel") String relation,
            @Param("subNs") String subjectNamespace,
            @Param("subId") String subjectId,
            @Param("subRel") String subjectRelation
    );

    @Modifying
    @Query("DELETE FROM RelationTuple t " +
            "WHERE t.namespace = :ns AND t.objectId = :objId AND t.relation = :rel " +
            "AND t.subjectNamespace = :subNs AND t.subjectId = :subId " +
            "AND ((:subRel IS NULL AND t.subjectRelation IS NULL) OR t.subjectRelation = :subRel)")
    int deleteTuple(
            @Param("ns") String namespace,
            @Param("objId") String objectId,
            @Param("rel") String relation,
            @Param("subNs") String subjectNamespace,
            @Param("subId") String subjectId,
            @Param("subRel") String subjectRelation
    );

    /** 유니크 제약과 경합해도 예외 없이 건너뛴다. 실제로 삽입된 행 수(0 또는 1)를 반환한다. */
    @Modifying
    @Query(value = "INSERT INTO relation_tuples (id, namespace, object_id, relation, subject_namespace, subject_id, subject_relation, created_at) " +
            "VALUES (:id, :ns, :objId, :rel, :subNs, :subId, :subRel, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("ns") String namespace,
            @Param("objId") String objectId,
            @Param("rel") String relation,
            @Param("subNs") String subjectNamespace,
            @Param("subId") String subjectId,
            @Param("subRel") String subjectRelation
    );
}
