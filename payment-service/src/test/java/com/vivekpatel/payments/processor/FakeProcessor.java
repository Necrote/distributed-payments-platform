package com.vivekpatel.payments.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A processor on a real socket, in-process. Real HTTP, so the timeouts, status codes and body
 * parsing in {@link ProcessorClient} are exercised for real - a mocked client would skip exactly
 * the part that goes wrong in production.
 *
 * <p>Deliberately dumber than {@code processor-simulator}: one reply rule at a time, set by the
 * test. Approves by default, echoing the request id like the real simulator does.
 */
public final class FakeProcessor implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What to send back for a request with this {@code requestId}. */
    public record Reply(int status, String body, Duration delay) {
    }

    private final HttpServer server;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private volatile Function<String, Reply> replies = FakeProcessor::approve;

    private FakeProcessor(HttpServer server) {
        this.server = server;
    }

    public static FakeProcessor start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeProcessor fake = new FakeProcessor(server);
            server.createContext("/authorize", fake::handle);
            // One virtual thread per request: the default runs every request on one thread, which
            // would serialise concurrent tests and make a slow reply block every other one.
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the fake processor", e);
        }
    }

    public String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    public void approveAll() {
        replies = FakeProcessor::approve;
    }

    public void declineAll(String declineCode) {
        replies = requestId -> new Reply(200, """
                {"requestId":"%s","status":"DECLINED","declineCode":"%s"}""".formatted(requestId, declineCode),
                Duration.ZERO);
    }

    public void replyWith(int status, String body) {
        replies = requestId -> new Reply(status, body, Duration.ZERO);
    }

    /** Approves, but only after {@code delay} - set it past the client's read timeout to time out. */
    public void approveAfter(Duration delay) {
        replies = requestId -> new Reply(200, approve(requestId).body(), delay);
    }

    public List<String> requestBodies() {
        return List.copyOf(requestBodies);
    }

    private static Reply approve(String requestId) {
        return new Reply(200, """
                {"requestId":"%s","authorizationId":"auth_%s","status":"APPROVED","amount":12500,"currency":"INR"}"""
                .formatted(requestId, UUID.randomUUID()), Duration.ZERO);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            JsonNode requestId = JSON.readTree(body).get("requestId");
            Reply reply = replies.apply(requestId == null ? "" : requestId.asText());
            if (!reply.delay().isZero()) {
                Thread.sleep(reply.delay());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The client gave up (read timeout) and closed the connection before we answered.
            // That is the scenario under test, not a failure of the fake.
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
