package com.demo.workloads.common.vendor;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One RestClient per configured vendor, with connect/read timeouts and a fresh X-Attempt-Id per HTTP attempt. */
public class VendorClients {
    public static final String ATTEMPT_HEADER = "X-Attempt-Id";

    private final VendorProperties props;
    private final RestClient.Builder builder;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public VendorClients(VendorProperties props, RestClient.Builder builder) {
        this.props = props;
        this.builder = builder;
    }

    public RestClient client(String vendor) {
        return clients.computeIfAbsent(vendor, this::build);
    }

    private RestClient build(String vendor) {
        VendorProperties.Vendor cfg = props.getVendors().get(vendor);
        if (cfg == null || cfg.getBaseUrl() == null) {
            throw new IllegalStateException("No demo.vendors." + vendor + ".base-url configured");
        }
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(cfg.getConnectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(cfg.getReadTimeout());
        return builder.clone()
                .baseUrl(cfg.getBaseUrl())
                .requestFactory(factory)
                // Unique per attempt: the WireMock fault stubs select a percentage of attempts by this header.
                .requestInterceptor((req, body, exec) -> {
                    req.getHeaders().set(ATTEMPT_HEADER, UUID.randomUUID().toString());
                    return exec.execute(req, body);
                })
                .build();
    }
}
