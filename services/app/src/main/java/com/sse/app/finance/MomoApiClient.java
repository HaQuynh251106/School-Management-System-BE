package com.sse.app.finance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sse.app.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

interface MomoApiClient {
    Map<String, Object> createPayment(String url, Map<String, Object> payload);
}

@Component
class JdkMomoApiClient implements MomoApiClient {

    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    JdkMomoApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> createPayment(String url, Map<String, Object> payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "MoMo create API tra HTTP " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Ket noi MoMo bi gian doan");
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Khong the ket noi MoMo sandbox");
        }
    }
}
