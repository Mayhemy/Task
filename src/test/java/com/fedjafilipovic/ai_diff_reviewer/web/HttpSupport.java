package com.fedjafilipovic.ai_diff_reviewer.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal raw-HTTP helper for integration tests. Uses HttpURLConnection so we
 * can control headers, method, and exact body bytes — and read the response
 * body as raw bytes for envelope-shape assertions. No JSON parsing here: tests
 * parse with tools.jackson directly as needed.
 */
public final class HttpSupport {

    private final String base;

    public HttpSupport(int port) {
        this.base = "http://localhost:" + port;
    }

    public record RawResponse(int status, byte[] body, Map<String, String> headers) {}

    /** POST with a body and optional Authorization + Idempotency-Key headers. */
    public RawResponse post(String path, String bodyJson, String bearer, String idempotencyKey,
                             String contentType, String contentLength) {
        return request("POST", path, bodyJson, bearer, idempotencyKey, contentType, contentLength);
    }

    public RawResponse get(String path, String bearer) {
        return request("GET", path, null, bearer, null, null, null);
    }

    public RawResponse request(String method, String path, String body, String bearer,
                               String idempotencyKey, String contentType, String contentLength) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
            c.setRequestMethod(method);
            c.setDoInput(true);
            if (body != null) c.setDoOutput(true);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (idempotencyKey != null) c.setRequestProperty("Idempotency-Key", idempotencyKey);
            c.setRequestProperty("Content-Type", contentType != null ? contentType : "application/json");
            if (contentLength != null) c.setRequestProperty("Content-Length", contentLength);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] respBody = in == null ? new byte[0] : readAll(in);
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null && !e.getValue().isEmpty()) {
                    headers.put(e.getKey(), e.getValue().get(0));
                }
            }
            c.disconnect();
            return new RawResponse(status, respBody, headers);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** POST raw bytes (for exact-byte-boundary tests) with optional fake Content-Length. */
    public RawResponse postBytes(String path, byte[] body, String bearer, String idempotencyKey,
                                 String contentType, String contentLength) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
            c.setRequestMethod("POST");
            c.setDoInput(true);
            c.setDoOutput(true);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (idempotencyKey != null) c.setRequestProperty("Idempotency-Key", idempotencyKey);
            c.setRequestProperty("Content-Type", contentType != null ? contentType : "application/json");
            if (contentLength != null) c.setRequestProperty("Content-Length", contentLength);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] respBody = in == null ? new byte[0] : readAll(in);
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null && !e.getValue().isEmpty()) {
                    headers.put(e.getKey(), e.getValue().get(0));
                }
            }
            c.disconnect();
            return new RawResponse(status, respBody, headers);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Open an SSE stream and collect raw event lines until the stream closes. */
    public List<String> sse(String path, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("Authorization", "Bearer " + bearer);
        c.setRequestProperty("Accept", "text/event-stream");
        c.setReadTimeout(10_000);
        int status = c.getResponseCode();
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            if (status >= 400) {
                byte[] b = in == null ? new byte[0] : readAll(in);
                throw new IOException("sse status " + status + ": " + new String(b, StandardCharsets.UTF_8));
            }
            List<String> lines = new ArrayList<>();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int n;
            byte[] tmp = new byte[4096];
            while ((n = in.read(tmp)) != -1) {
                buf.write(tmp, 0, n);
                // Split on \n\n event boundaries incrementally.
                String s = buf.toString(StandardCharsets.UTF_8);
                int idx;
                while ((idx = s.indexOf("\n\n")) != -1) {
                    String evt = s.substring(0, idx);
                    lines.add(evt);
                    s = s.substring(idx + 2);
                }
                buf.reset();
                buf.write(s.getBytes(StandardCharsets.UTF_8));
            }
            String rest = buf.toString(StandardCharsets.UTF_8);
            if (!rest.isBlank()) lines.add(rest);
            c.disconnect();
            return lines;
        }
    }

    /**
     * Sends a raw HTTP/1.1 POST over a plain socket with a Content-Length
     * header that does NOT match the actual bytes written (a real lying
     * Content-Length, which HttpURLConnection can't produce — it recomputes
     * and overwrites the header from the actual bytes written unless
     * fixed-length streaming mode is used, and that mode enforces the
     * declared length actually gets written). Used to prove the fast-path
     * size guard rejects based on the declared header alone, before it would
     * ever block reading a body that never arrives.
     *
     * @return only the HTTP status line (e.g. "HTTP/1.1 413 Payload Too Large")
     */
    public String postWithLyingContentLength(String path, String bearer, String actualBodySent,
                                             long declaredContentLength) throws IOException {
        int port = Integer.parseInt(base.substring(base.lastIndexOf(':') + 1));
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            byte[] bodyBytes = actualBodySent.getBytes(StandardCharsets.UTF_8);
            StringBuilder req = new StringBuilder();
            req.append("POST ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: localhost:").append(port).append("\r\n");
            if (bearer != null) req.append("Authorization: Bearer ").append(bearer).append("\r\n");
            req.append("Content-Type: application/json\r\n");
            req.append("Content-Length: ").append(declaredContentLength).append("\r\n");
            req.append("Connection: close\r\n");
            req.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();
            socket.shutdownOutput();

            ByteArrayOutputStream respBuf = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] tmp = new byte[4096];
            int n;
            try {
                while ((n = in.read(tmp)) != -1) {
                    respBuf.write(tmp, 0, n);
                }
            } catch (IOException timeoutOrReset) {
                // Server may close the connection right after writing the
                // response; whatever we already buffered is what we need.
            }
            String resp = respBuf.toString(StandardCharsets.UTF_8);
            int lineEnd = resp.indexOf("\r\n");
            return lineEnd >= 0 ? resp.substring(0, lineEnd) : resp;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
