package com.hunnit_beasts.doro.sdk.client;

import com.hunnit_beasts.guard.interfaces.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public class DoroGuardClient {

    private final ManagedChannel channel;
    private final GuardServiceGrpc.GuardServiceBlockingStub blockingStub;
    private final long timeoutSeconds;

    public DoroGuardClient(String host, int port) {
        this(host, port, 3);
    }

    public DoroGuardClient(String host, int port, long timeoutSeconds) {
        this(ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build(), timeoutSeconds);
    }

    public DoroGuardClient(ManagedChannel channel) {
        this(channel, 3);
    }

    public DoroGuardClient(ManagedChannel channel, long timeoutSeconds) {
        this.channel = channel;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 3;
        this.blockingStub = GuardServiceGrpc.newBlockingStub(channel)
                .withInterceptors(new TraceIdClientInterceptor());
    }

    private GuardServiceGrpc.GuardServiceBlockingStub getStub() {
        return blockingStub.withDeadlineAfter(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * 권한 확인 (기본 subject_namespace = "user")
     */
    public boolean check(String namespace, String objectId, String relation, String subjectId) {
        return check(namespace, objectId, relation, "user", subjectId, null);
    }

    /**
     * 권한 확인 (subject_namespace 지정)
     */
    public boolean check(String namespace, String objectId, String relation, String subjectNamespace, String subjectId) {
        return check(namespace, objectId, relation, subjectNamespace, subjectId, null);
    }

    /**
     * 권한 확인 전체 파라미터
     */
    public boolean check(String namespace, String objectId, String relation,
                         String subjectNamespace, String subjectId, String subjectRelation) {
        try {
            CheckRequest request = CheckRequest.newBuilder()
                    .setNamespace(namespace)
                    .setObjectId(objectId)
                    .setRelation(relation)
                    .setSubjectNamespace(subjectNamespace)
                    .setSubjectId(subjectId)
                    .setSubjectRelation(subjectRelation != null ? subjectRelation : "")
                    .build();

            CheckResponse response = getStub().check(request);
            return response.getAllowed();
        } catch (Exception e) {
            log.error("DoroGuardClient check failed for {}:{}#{}@{}: {}",
                    namespace, objectId, relation, subjectId, e.getMessage());
            return false;
        }
    }

    /**
     * 단일 관계 튜플 등록
     */
    public int writeTuple(String namespace, String objectId, String relation,
                          String subjectNamespace, String subjectId) {
        return writeTuple(namespace, objectId, relation, subjectNamespace, subjectId, null);
    }

    public int writeTuple(String namespace, String objectId, String relation,
                          String subjectNamespace, String subjectId, String subjectRelation) {
        try {
            RelationTupleProto tupleProto = RelationTupleProto.newBuilder()
                    .setNamespace(namespace)
                    .setObjectId(objectId)
                    .setRelation(relation)
                    .setSubjectNamespace(subjectNamespace)
                    .setSubjectId(subjectId)
                    .setSubjectRelation(subjectRelation != null ? subjectRelation : "")
                    .build();

            WriteTuplesRequest request = WriteTuplesRequest.newBuilder()
                    .addTuples(tupleProto)
                    .build();

            WriteTuplesResponse response = getStub().writeTuples(request);
            return response.getWrittenCount();
        } catch (Exception e) {
            log.error("DoroGuardClient writeTuple failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 단일 관계 튜플 삭제
     */
    public int deleteTuple(String namespace, String objectId, String relation,
                          String subjectNamespace, String subjectId) {
        return deleteTuple(namespace, objectId, relation, subjectNamespace, subjectId, null);
    }

    public int deleteTuple(String namespace, String objectId, String relation,
                          String subjectNamespace, String subjectId, String subjectRelation) {
        try {
            RelationTupleProto tupleProto = RelationTupleProto.newBuilder()
                    .setNamespace(namespace)
                    .setObjectId(objectId)
                    .setRelation(relation)
                    .setSubjectNamespace(subjectNamespace)
                    .setSubjectId(subjectId)
                    .setSubjectRelation(subjectRelation != null ? subjectRelation : "")
                    .build();

            DeleteTuplesRequest request = DeleteTuplesRequest.newBuilder()
                    .addTuples(tupleProto)
                    .build();

            DeleteTuplesResponse response = getStub().deleteTuples(request);
            return response.getDeletedCount();
        } catch (Exception e) {
            log.error("DoroGuardClient deleteTuple failed: {}", e.getMessage());
            return 0;
        }
    }

    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdown().awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                channel.shutdownNow();
            }
        }
    }
}
