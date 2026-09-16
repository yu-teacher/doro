package com.hunnit_beasts.guard.config;

import com.hunnit_beasts.guard.interfaces.grpc.GuardGrpcService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class GrpcServerRunner {

    private final GuardGrpcService guardGrpcService;

    @Value("${doro.guard.grpc.port:9090}")
    private int grpcPort;

    private Server server;

    @PostConstruct
    public void start() throws IOException {
        if (grpcPort <= 0) {
            log.info("gRPC Server disabled (port={})", grpcPort);
            return;
        }

        server = ServerBuilder.forPort(grpcPort)
                .addService(guardGrpcService)
                .intercept(new TraceIdServerInterceptor())
                .build()
                .start();

        log.info("🚀 Doro Guard gRPC Server started on port {}", grpcPort);
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            log.info("Shutting down Doro Guard gRPC Server...");
            server.shutdown();
        }
    }
}
