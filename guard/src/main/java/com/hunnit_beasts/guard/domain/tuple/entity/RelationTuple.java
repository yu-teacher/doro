package com.hunnit_beasts.guard.domain.tuple.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "relation_tuples")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RelationTuple {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "namespace", nullable = false, length = 64)
    private String namespace;

    @Column(name = "object_id", nullable = false, length = 128)
    private String objectId;

    @Column(name = "relation", nullable = false, length = 64)
    private String relation;

    @Column(name = "subject_namespace", nullable = false, length = 64)
    private String subjectNamespace;

    @Column(name = "subject_id", nullable = false, length = 128)
    private String subjectId;

    @Column(name = "subject_relation", length = 64)
    private String subjectRelation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public RelationTuple(UUID id, String namespace, String objectId, String relation,
                         String subjectNamespace, String subjectId, String subjectRelation) {
        this.id = id != null ? id : UUID.randomUUID();
        this.namespace = namespace;
        this.objectId = objectId;
        this.relation = relation;
        this.subjectNamespace = subjectNamespace;
        this.subjectId = subjectId;
        this.subjectRelation = (subjectRelation != null && !subjectRelation.isBlank()) ? subjectRelation : null;
        this.createdAt = Instant.now();
    }

    public String toTupleString() {
        String obj = namespace + ":" + objectId;
        String subj = subjectNamespace + ":" + subjectId;
        if (subjectRelation != null && !subjectRelation.isBlank()) {
            subj += "#" + subjectRelation;
        }
        return obj + "#" + relation + "@" + subj;
    }
}
