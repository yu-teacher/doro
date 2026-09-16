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
}
