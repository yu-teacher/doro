package com.hunnit_beasts.doro.sdk.client;

import com.hunnit_beasts.guard.interfaces.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import com.hunnit_beasts.doro.sdk.exception.DoroGuardUnavailableException;
import com.hunnit_beasts.doro.sdk.exception.DoroGuardWriteFailedException;
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
        this(host, port, timeoutSeconds, null);
    }

    public DoroGuardClient(String host, int port, long timeoutSeconds, String serviceToken) {
        this(ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build(), timeoutSeconds, serviceToken);
    }

    public DoroGuardClient(ManagedChannel channel) {
        this(channel, 3);
    }

    public DoroGuardClient(ManagedChannel channel, long timeoutSeconds) {
        this(channel, timeoutSeconds, null);
    }

    public DoroGuardClient(ManagedChannel channel, long timeoutSeconds, String serviceToken) {
        this.channel = channel;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 3;
        GuardServiceGrpc.GuardServiceBlockingStub stub = GuardServiceGrpc.newBlockingStub(channel)
                .withInterceptors(new TraceIdClientInterceptor());
        if (serviceToken != null && !serviceToken.isBlank()) {
            stub = stub.withInterceptors(new ServiceTokenClientInterceptor(serviceToken));
        }
        this.blockingStub = stub;
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

    /**
     * check 의 예외 전파 버전. Guard 장애와 실제 거부를 구분할 수 있다.
     * 장애 시 DoroGuardUnavailableException 을 던지고, 정상 응답이면 허용 여부를 반환한다.
     */
    public boolean checkOrThrow(String namespace, String objectId, String relation,
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
            return getStub().check(request).getAllowed();
        } catch (Exception e) {
            log.error("DoroGuardClient checkOrThrow failed: {}", e.getMessage());
            throw new DoroGuardUnavailableException("Doro Guard 를 사용할 수 없습니다.", e);
        }
    }

    public boolean checkOrThrow(String namespace, String objectId, String relation, String subjectId) {
        return checkOrThrow(namespace, objectId, relation, "user", subjectId, null);
    }

    /** writeTuple 의 예외 전파 버전. 실패하면 DoroGuardWriteFailedException 을 던진다. */
    public int writeTupleOrThrow(String namespace, String objectId, String relation,
                                 String subjectNamespace, String subjectId, String subjectRelation) {
        try {
            WriteTuplesRequest request = WriteTuplesRequest.newBuilder()
                    .addTuples(RelationTupleProto.newBuilder()
                            .setNamespace(namespace)
                            .setObjectId(objectId)
                            .setRelation(relation)
                            .setSubjectNamespace(subjectNamespace)
                            .setSubjectId(subjectId)
                            .setSubjectRelation(subjectRelation != null ? subjectRelation : "")
                            .build())
                    .build();
            return getStub().writeTuples(request).getWrittenCount();
        } catch (Exception e) {
            log.error("DoroGuardClient writeTupleOrThrow failed: {}", e.getMessage());
            throw new DoroGuardWriteFailedException("Doro Guard 튜플 쓰기에 실패했습니다.", e);
        }
    }

    /** deleteTuple 의 예외 전파 버전. 실패하면 DoroGuardWriteFailedException 을 던진다. */
    public int deleteTupleOrThrow(String namespace, String objectId, String relation,
                                  String subjectNamespace, String subjectId, String subjectRelation) {
        try {
            DeleteTuplesRequest request = DeleteTuplesRequest.newBuilder()
                    .addTuples(RelationTupleProto.newBuilder()
                            .setNamespace(namespace)
                            .setObjectId(objectId)
                            .setRelation(relation)
                            .setSubjectNamespace(subjectNamespace)
                            .setSubjectId(subjectId)
                            .setSubjectRelation(subjectRelation != null ? subjectRelation : "")
                            .build())
                    .build();
            return getStub().deleteTuples(request).getDeletedCount();
        } catch (Exception e) {
            log.error("DoroGuardClient deleteTupleOrThrow failed: {}", e.getMessage());
            throw new DoroGuardWriteFailedException("Doro Guard 튜플 삭제에 실패했습니다.", e);
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
