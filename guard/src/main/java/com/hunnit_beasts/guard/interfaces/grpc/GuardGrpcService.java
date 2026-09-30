package com.hunnit_beasts.guard.interfaces.grpc;

import com.hunnit_beasts.guard.core.engine.CheckEngine;
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
    private final TupleService tupleService;

    @Override
    public void check(CheckRequest request, StreamObserver<CheckResponse> responseObserver) {
        try {
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
            log.error("Error during gRPC check: {}", e.getMessage(), e);
            responseObserver.onError(toStatus(e));
        }
    }

    @Override
    public void writeTuples(WriteTuplesRequest request, StreamObserver<WriteTuplesResponse> responseObserver) {
        try {
            List<TupleDto> dtos = request.getTuplesList().stream()
                    .map(p -> TupleDto.of(
                            p.getNamespace(),
                            p.getObjectId(),
                            p.getRelation(),
                            p.getSubjectNamespace(),
                            p.getSubjectId(),
                            p.getSubjectRelation()
                    ))
                    .toList();

            int written = tupleService.writeTuples(dtos);
            WriteTuplesResponse response = WriteTuplesResponse.newBuilder()
                    .setWrittenCount(written)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error during gRPC writeTuples: {}", e.getMessage(), e);
            responseObserver.onError(toStatus(e));
        }
    }

    @Override
    public void deleteTuples(DeleteTuplesRequest request, StreamObserver<DeleteTuplesResponse> responseObserver) {
        try {
            List<TupleDto> dtos = request.getTuplesList().stream()
                    .map(p -> TupleDto.of(
                            p.getNamespace(),
                            p.getObjectId(),
                            p.getRelation(),
                            p.getSubjectNamespace(),
                            p.getSubjectId(),
                            p.getSubjectRelation()
                    ))
                    .toList();

            int deleted = tupleService.deleteTuples(dtos);
            DeleteTuplesResponse response = DeleteTuplesResponse.newBuilder()
                    .setDeletedCount(deleted)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error during gRPC deleteTuples: {}", e.getMessage(), e);
            responseObserver.onError(toStatus(e));
        }
    }

    /** 내부 예외를 gRPC Status 로 변환한다. 원시 예외를 그대로 넘기면 클라이언트에는 UNKNOWN 으로만 보인다. */
    private static StatusRuntimeException toStatus(Exception e) {
        if (e instanceof GuardException guardException) {
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
