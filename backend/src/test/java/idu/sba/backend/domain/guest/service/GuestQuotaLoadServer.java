package idu.sba.backend.domain.guest.service;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

// 실제 AI·운영 DB 없이 체험 횟수 저장소만 같은 HTTP 경로에서 비교한다.
public final class GuestQuotaLoadServer {
    private GuestQuotaLoadServer() { }

    public static void main(String[] args) throws Exception {
        String backend = args.length > 0 ? args[0] : "redis";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 18080;
        var fixture = new GuestQuotaTestFixture(backend);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        var executor = Executors.newFixedThreadPool(40);
        server.setExecutor(executor);
        server.createContext("/quota", exchange -> {
            int status = 500;
            try {
                if (!exchange.getRequestMethod().equals("POST")) {
                    status = 405;
                } else {
                    String query = exchange.getRequestURI().getRawQuery();
                    if (query == null || !query.startsWith("token=") || query.length() <= 6) {
                        status = 400;
                    } else {
                        String token = query.substring(6);
                        status = fixture.store.tryAcquire(token).isPresent() ? 200 : 403;
                    }
                }
            } catch (Exception e) {
                System.err.println("Quota request failed: " + e);
            } finally {
                byte[] body = Integer.toString(status).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
                exchange.close();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            executor.shutdownNow();
            fixture.close();
        }));
        server.start();
        System.out.printf("Quota test server: backend=%s port=%d (Ctrl+C to stop)%n", backend, port);
    }
}
