package demo.load;

import net.datafaker.Faker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seeds mock data through the public API (so seeding is itself traced): for each user, create the user, wait for
 * UserCreated, upsert a profile and add opening funds. Writes users.csv for the Gatling feeders.
 * Usage: ./gradlew :load-tests:seed -Dusers=500 [-Dgateway=http://localhost:8080]
 */
public final class Seed {
    private static final Pattern REQUEST_ID = Pattern.compile("\"requestId\":\"([^\"]+)\"");
    private static final Pattern ENTITY_ID = Pattern.compile("\"entityId\":\"([^\"]+)\"");
    private static final Pattern STATUS = Pattern.compile("\"status\":\"([A-Z]+)\"");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String gateway = Config.GATEWAY;
    private final Faker faker = new Faker(Locale.UK);

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "users.csv");
        int n = Integer.parseInt(System.getProperty("users", "500"));
        new Seed().run(n, out);
    }

    void run(int n, Path out) throws Exception {
        List<String> rows = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failed = new AtomicInteger();
        Semaphore inflight = new Semaphore(25);
        long t0 = System.currentTimeMillis();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                inflight.acquire();
                exec.submit(() -> {
                    try {
                        rows.add(seedOne());
                        if (rows.size() % 50 == 0) System.out.printf("seeded %d/%d%n", rows.size(), n);
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        System.err.println("seed failed: " + e.getMessage());
                    } finally {
                        inflight.release();
                    }
                });
            }
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        List<String> lines = new ArrayList<>();
        lines.add("userId,accountId,email,password,firstName,lastName");
        lines.addAll(rows);
        Files.write(out, lines);
        System.out.printf("seeded %d users (%d failed) in %ds -> %s%n", rows.size(), failed.get(),
                (System.currentTimeMillis() - t0) / 1000, out);
        if (rows.isEmpty()) System.exit(1);
    }

    private String seedOne() throws Exception {
        String first = faker.name().firstName().replaceAll("[^A-Za-z]", "");
        String last = faker.name().lastName().replaceAll("[^A-Za-z]", "");
        String email = (first + "." + last + "." + UUID.randomUUID().toString().substring(0, 6) + "@example.com").toLowerCase();
        String client = "seed-" + UUID.randomUUID();

        String created = send(client, "POST", "/api/users", """
                {"email":"%s","firstName":"%s","lastName":"%s","dateOfBirth":"%s"}"""
                .formatted(email, first, last, faker.timeAndDate().birthday(18, 80)));
        String userId = group(ENTITY_ID, created);
        await(client, group(REQUEST_ID, created));

        String profile = send(client, "PUT", "/api/users/" + userId + "/profile", """
                {"displayName":"%s %s","phone":"%s","addressLine":"%s","city":"%s","postcode":"%s","country":"UK","marketingOptIn":%s}"""
                .formatted(first, last, faker.phoneNumber().cellPhone(), faker.address().streetAddress().replace("\"", ""),
                        faker.address().city().replace("\"", ""), faker.address().postcode(),
                        ThreadLocalRandom.current().nextBoolean()));
        String accountId = "ACC-" + userId;
        String funds = send(client, "POST", "/api/accounts/" + accountId + "/funds",
                "{\"amount\":%d,\"currency\":\"GBP\"}".formatted(ThreadLocalRandom.current().nextInt(100, 5000)));
        await(client, group(REQUEST_ID, profile));
        await(client, group(REQUEST_ID, funds));
        return String.join(",", userId, accountId, email, "pw-" + userId.substring(0, 8), first, last);
    }

    private void await(String client, String requestId) throws Exception {
        for (int i = 0; i < 240; i++) {
            String s = send(client, "GET", "/api/requests/" + requestId, null);
            String status = group(STATUS, s);
            if (status.equals("COMPLETED")) return;
            if (status.equals("FAILED")) throw new IllegalStateException("request " + requestId + " failed: " + s);
            Thread.sleep(250);
        }
        throw new IllegalStateException("request " + requestId + " still pending");
    }

    private String send(String client, String method, String path, String body) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(gateway + path)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("X-Client-Id", client);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 429 && attempt < 10) {       // honour the gateway's Retry-After
                long wait = res.headers().firstValueAsLong("Retry-After").orElse(1);
                Thread.sleep(wait * 1000);
                continue;
            }
            if (res.statusCode() >= 300) throw new IOException(method + " " + path + " -> " + res.statusCode() + " " + res.body());
            return res.body();
        }
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) throw new IllegalStateException("unexpected response: " + s);
        return m.group(1);
    }
}
