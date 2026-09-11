package dev.localsync.client;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Blocking transport used from LocalSync worker threads. HttpURLConnection avoids the
 * Java 25 selector bootstrap that can fail on Windows installations with broken AF_UNIX.
 */
final class BilibiliHttp {
    private static final int MAX_TEXT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_BINARY_BYTES = 16 * 1024 * 1024;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private BilibiliHttp() {
    }

    static HttpResponse<String> sendString(HttpRequest request, boolean followRedirects)
            throws IOException {
        try (StreamingResponse response = openStream(request, followRedirects)) {
            byte[] body = readLimited(response.body(), MAX_TEXT_BYTES);
            return new BasicResponse<>(request, response.statusCode(), response.headers(),
                new String(body, StandardCharsets.UTF_8), response.uri());
        }
    }

    static HttpResponse<byte[]> sendBytes(HttpRequest request, boolean followRedirects)
            throws IOException {
        try (StreamingResponse response = openStream(request, followRedirects)) {
            byte[] body = readLimited(response.body(), MAX_BINARY_BYTES);
            return new BasicResponse<>(request, response.statusCode(), response.headers(),
                body, response.uri());
        }
    }

    static HttpResponse<Void> discard(HttpRequest request, boolean followRedirects)
            throws IOException {
        try (StreamingResponse response = openStream(request, followRedirects)) {
            return new BasicResponse<>(request, response.statusCode(), response.headers(),
                null, response.uri());
        }
    }

    static StreamingResponse openStream(HttpRequest request, boolean followRedirects)
            throws IOException {
        URLConnection raw = request.uri().toURL().openConnection();
        if (!(raw instanceof HttpURLConnection connection)) {
            throw new IOException("Unsupported media protocol: " + request.uri().getScheme());
        }
        int timeout = timeoutMillis(request.timeout().orElse(DEFAULT_TIMEOUT));
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(followRedirects);
        connection.setUseCaches(false);
        connection.setRequestMethod(request.method());
        request.headers().map().forEach((name, values) ->
            values.forEach(value -> connection.addRequestProperty(name, value)));
        try {
            int status = connection.getResponseCode();
            InputStream body = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
            if (body == null) body = InputStream.nullInputStream();
            return new StreamingResponse(connection, status, headers(connection), body,
                finalUri(connection, request.uri()));
        } catch (IOException | RuntimeException error) {
            connection.disconnect();
            throw error;
        }
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 32_768));
        byte[] buffer = new byte[8_192];
        int total = 0;
        for (int read; (read = input.read(buffer)) >= 0; ) {
            total += read;
            if (total > maximum) {
                throw new IOException("Bilibili response exceeded " + maximum + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static int timeoutMillis(Duration duration) {
        long value = Math.max(1L, duration.toMillis());
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    private static HttpHeaders headers(HttpURLConnection connection) {
        Map<String, List<String>> copied = new LinkedHashMap<>();
        connection.getHeaderFields().forEach((name, values) -> {
            if (name != null && values != null) copied.put(name, List.copyOf(values));
        });
        return HttpHeaders.of(copied, (name, value) -> true);
    }

    private static URI finalUri(HttpURLConnection connection, URI fallback) {
        try {
            return connection.getURL().toURI();
        } catch (URISyntaxException error) {
            return fallback;
        }
    }

    static final class StreamingResponse implements AutoCloseable {
        private final HttpURLConnection connection;
        private final int statusCode;
        private final HttpHeaders headers;
        private final InputStream body;
        private final URI uri;

        private StreamingResponse(HttpURLConnection connection, int statusCode,
                                  HttpHeaders headers, InputStream body, URI uri) {
            this.connection = connection;
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
            this.uri = uri;
        }

        int statusCode() {
            return statusCode;
        }

        HttpHeaders headers() {
            return headers;
        }

        InputStream body() {
            return body;
        }

        URI uri() {
            return uri;
        }

        @Override
        public void close() throws IOException {
            try {
                body.close();
            } finally {
                connection.disconnect();
            }
        }
    }

    private record BasicResponse<T>(HttpRequest request, int statusCode,
                                    HttpHeaders headers, T body,
                                    URI uri) implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
