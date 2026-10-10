package com.hunnit_beasts.guard.interfaces.grpc;

import com.hunnit_beasts.guard.common.validation.FieldLimits;
import com.hunnit_beasts.guard.config.CallerNamespaceGuard;
import com.hunnit_beasts.guard.config.ServiceTokenServerInterceptor;
import com.hunnit_beasts.guard.core.engine.CheckEngine;
import com.hunnit_beasts.guard.core.engine.ExpandEngine;
import com.hunnit_beasts.guard.domain.tuple.dto.TupleDto;
import com.hunnit_beasts.guard.domain.tuple.service.TupleService;
import com.hunnit_beasts.guard.common.exception.GuardException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardGrpcService extends GuardServiceGrpc.GuardServiceImplBase {

    private final CheckEngine checkEngine;
    private final ExpandEngine expandEngine;
    private final TupleService tupleService;
    private final CallerNamespaceGuard namespaceGuard;

    @Override
    public void check(CheckRequest request, StreamObserver<CheckResponse> responseObserver) {
        try {
            FieldLimits.requireTuple(request.getNamespace(), request.getObjectId(), request.getRelation(),
                    request.getSubjectNamespace(), request.getSubjectId(), request.getSubjectRelation());
            CheckEngine.CheckResult result = checkEngine.check(
                    request.getNamespace(),
                    request.getObjectId(),
                    request.getRelation(),
                    request.getSubjectNamespace(),
                    request.getSubjectId(),
                    request.getSubjectRelation()
            );

            CheckResponse response = CheckResponse.newBuilder()
                    .setAllowed(result.allowed())
                    .setDepth(result.maxDepthReached())
                    .setReason(result.reason())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logFailure("check", e);
            responseObserver.onError(toStatus(e));
        }
    }

    @Override
    public void writeTuples(WriteTuplesRequest request, StreamObserver<WriteTuplesResponse> responseObserver) {
        try {
            List<TupleDto> dtos = toValidatedDtos(request.getTuplesList());

            namespaceGuard.requireTupleAccess(ServiceTokenServerInterceptor.CALLER.get(), "tuple write", dtos);
            int written = tupleService.writeTuples(dtos);
            WriteTuplesResponse response = WriteTuplesResponse.newBuilder()
                    .setWrittenCount(written)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logFailure("writeTuples", e);
            responseObserver.onError(toStatus(e));
        }
    }

    @Override
    public void deleteTuples(DeleteTuplesRequest request, StreamObserver<DeleteTuplesResponse> responseObserver) {
        try {
            List<TupleDto> dtos = toValidatedDtos(request.getTuplesList());

            namespaceGuard.requireTupleAccess(ServiceTokenServerInterceptor.CALLER.get(), "tuple delete", dtos);
            int deleted = tupleService.deleteTuples(dtos);
            DeleteTuplesResponse response = DeleteTuplesResponse.newBuilder()
                    .setDeletedCount(deleted)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logFailure("deleteTuples", e);
            responseObserver.onError(toStatus(e));
        }
    }

    @Override
    public void expand(ExpandRequest request, StreamObserver<ExpandResponse> responseObserver) {
        try {
            FieldLimits.requireObject(request.getNamespace(), request.getObjectId(), request.getRelation());
            String tree = expandEngine.expand(request.getNamespace(), request.getObjectId(), request.getRelation());
            responseObserver.onNext(ExpandResponse.newBuilder().setTreeJson(tree).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            logFailure("expand", e);
            responseObserver.onError(toStatus(e));
        }
    }

    /** 클라이언트 입력 오류는 WARN, 그 외는 스택트레이스와 함께 ERROR 로 남긴다. */
    private static void logFailure(String operation, Exception e) {
        boolean clientError = e instanceof IllegalArgumentException
                || (e instanceof GuardException ge && ge.getErrorCode().getHttpStatus().is4xxClientError());
        if (clientError) {
            log.warn("Rejected gRPC {}: {}", operation, e.getMessage());
        } else {
            log.error("Error during gRPC {}: {}", operation, e.getMessage(), e);
        }
    }

    /** REST 의 @Valid 와 같은 기준(비어 있음/컬럼 길이)으로 검증한 뒤 DTO 로 변환한다. */
    private static List<TupleDto> toValidatedDtos(List<RelationTupleProto> tuples) {
        return tuples.stream()
                .map(p -> {
                    FieldLimits.requireTuple(p.getNamespace(), p.getObjectId(), p.getRelation(),
                            p.getSubjectNamespace(), p.getSubjectId(), p.getSubjectRelation());
                    return TupleDto.of(p.getNamespace(), p.getObjectId(), p.getRelation(),
                            p.getSubjectNamespace(), p.getSubjectId(), p.getSubjectRelation());
                })
                .toList();
    }

    /** 내부 예외를 gRPC Status 로 변환한다. 원시 예외를 그대로 넘기면 클라이언트에는 UNKNOWN 으로만 보인다. */
    private static StatusRuntimeException toStatus(Exception e) {
        if (e instanceof GuardException guardException) {
            if (guardException.getErrorCode() == com.hunnit_beasts.guard.common.exception.ErrorCode.NAMESPACE_FORBIDDEN) {
                return Status.PERMISSION_DENIED.withDescription(guardException.getMessage()).asRuntimeException();
            }
            Status status = guardException.getErrorCode().getHttpStatus().is4xxClientError()
                    ? Status.INVALID_ARGUMENT : Status.INTERNAL;
            return status.withDescription(guardException.getMessage()).asRuntimeException();
        }
        if (e instanceof IllegalArgumentException) {
            return Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException();
        }
        return Status.INTERNAL.withDescription("internal error").asRuntimeException();
    }
}
