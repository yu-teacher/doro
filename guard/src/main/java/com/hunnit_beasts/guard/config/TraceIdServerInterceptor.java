package com.hunnit_beasts.guard.config;

import io.grpc.*;
import org.slf4j.MDC;

import java.util.UUID;

public class TraceIdServerInterceptor implements ServerInterceptor {

    public static final Metadata.Key<String> TRACE_ID_METADATA_KEY =
            Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String traceId = headers.get(TRACE_ID_METADATA_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        final String finalTraceId = traceId;
        MDC.put("traceId", finalTraceId);

        ServerCall.Listener<ReqT> listener;
        try {
            listener = next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                @Override
                public void sendHeaders(Metadata responseHeaders) {
                    responseHeaders.put(TRACE_ID_METADATA_KEY, finalTraceId);
                    super.sendHeaders(responseHeaders);
                }
            }, headers);
        } finally {
            MDC.clear();
        }

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(listener) {
            @Override
            public void onMessage(ReqT message) {
                MDC.put("traceId", finalTraceId);
                try {
                    super.onMessage(message);
                } finally {
                    MDC.clear();
                }
            }

            @Override
            public void onHalfClose() {
                MDC.put("traceId", finalTraceId);
                try {
                    super.onHalfClose();
                } finally {
                    MDC.clear();
                }
            }

            @Override
            public void onComplete() {
                MDC.put("traceId", finalTraceId);
                try {
                    super.onComplete();
                } finally {
                    MDC.clear();
                }
            }

            @Override
            public void onCancel() {
                MDC.put("traceId", finalTraceId);
                try {
                    super.onCancel();
                } finally {
                    MDC.clear();
                }
            }
        };
    }
}
