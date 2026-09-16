package com.hunnit_beasts.doro.sdk.exception;

import lombok.Getter;

@Getter
public class DoroAccessDeniedException extends RuntimeException {

    private final String namespace;
    private final String objectId;
    private final String relation;
    private final String userId;

    public DoroAccessDeniedException(String namespace, String objectId, String relation, String userId) {
        super(String.format("Doro Guard Access Denied: User [%s] lacks '%s' permission on [%s:%s]",
                userId, relation, namespace, objectId));
        this.namespace = namespace;
        this.objectId = objectId;
        this.relation = relation;
        this.userId = userId;
    }

    public DoroAccessDeniedException(String message) {
        super(message);
        this.namespace = null;
        this.objectId = null;
        this.relation = null;
        this.userId = null;
    }
}
