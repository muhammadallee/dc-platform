import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scripted identity provider for the hardening phase-07 scenarios: it signs what a real one never would, and fails on request.
 *
 *   java -cp nimbus.jar Idp.java <port-file> <issuer>
 *
 * GET /jwks serves the current keys. POST /__mode?m=ok|fail|hang|html|redirect. POST /__keys?set=k1,k2. GET /__fetches.
 * GET /__mint?kid=k1&amp;alg=RS256&amp;sub=alice&amp;iss=..&amp;aud=..&amp;exp=300&amp;nbf=10&amp;claims={json}; an empty value leaves the claim out.
 * alg may be none or HS256, the latter keyed with the public JWK, for algorithm confusion. Test keys only.
 */
public final class Idp {

    private static final Map<String, RSAKey> KEYS = new HashMap<>();
    private static final AtomicInteger FETCHES = new AtomicInteger();
    private static volatile String mode = "ok";
    private static volatile List<String> served = List.of("k1");

    private Idp() {
    }

    public static void main(String[] args) throws Exception {
        String issuer = args[1];
        for (String id : List.of("k1", "k2", "stranger")) {
            KEYS.put(id, new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).generate());
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/jwks", Idp::jwks);
        server.createContext("/__mode", exchange -> {
            mode = query(exchange).get("m");
            answer(exchange, 200, "ok");
        });
        server.createContext("/__keys", exchange -> {
            served = List.of(query(exchange).get("set").split(","));
            answer(exchange, 200, "ok");
        });
        server.createContext("/__fetches", exchange -> answer(exchange, 200, Integer.toString(FETCHES.get())));
        server.createContext("/__mint", exchange -> {
            try {
                answer(exchange, 200, mint(query(exchange), issuer));
            } catch (Exception e) {
                answer(exchange, 500, e.toString());
            }
        });
        server.start();
        Files.writeString(Path.of(args[0]), Integer.toString(server.getAddress().getPort()));
    }

    private static void jwks(HttpExchange exchange) throws java.io.IOException {
        FETCHES.incrementAndGet();
        switch (mode) {
            case "fail" -> answer(exchange, 500, "no");
            case "html" -> answer(exchange, 200, "<html>sign in</html>");
            case "redirect" -> {
                exchange.getResponseHeaders().add("Location", "/jwks");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
            case "hang" -> {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            }
            default -> {
                List<com.nimbusds.jose.jwk.JWK> keys = new ArrayList<>();
                served.forEach(id -> keys.add(KEYS.get(id).toPublicJWK()));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                answer(exchange, 200, new JWKSet(keys).toString());
            }
        }
    }

    private static String mint(Map<String, String> q, String issuer) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();
        String sub = q.getOrDefault("sub", "alice");
        if (!sub.isEmpty()) {
            claims.subject(sub);
        }
        String iss = q.getOrDefault("iss", issuer);
        if (!iss.isEmpty()) {
            claims.issuer(iss);
        }
        String aud = q.getOrDefault("aud", "svc");
        if (!aud.isEmpty()) {
            claims.audience(aud);
        }
        String exp = q.getOrDefault("exp", "7200");
        if (!exp.isEmpty()) {
            claims.expirationTime(at(exp));
        }
        if (q.containsKey("nbf")) {
            claims.notBeforeTime(at(q.get("nbf")));
        }
        if (q.containsKey("claims")) {
            JSONObjectUtils.parse(q.get("claims")).forEach(claims::claim);
        }
        String alg = q.getOrDefault("alg", "RS256");
        RSAKey key = KEYS.get(q.getOrDefault("kid", "k1"));
        if ("none".equals(alg)) {
            return new PlainJWT(claims.build()).serialize();
        }
        if ("HS256".equals(alg)) {
            byte[] secret = MessageDigest.getInstance("SHA-256").digest(key.toPublicJWK().toJSONString().getBytes(StandardCharsets.UTF_8));
            SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(key.getKeyID()).build(), claims.build());
            hmac.sign(new MACSigner(secret));
            return hmac.serialize();
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.parse(alg)).keyID(key.getKeyID()).build(), claims.build());
        // A signature by another key under the same id, for a bad signature.
        jwt.sign(new RSASSASigner("true".equals(q.get("forged")) ? KEYS.get("stranger") : key));
        return jwt.serialize();
    }

    private static Date at(String secondsFromNow) {
        return new Date(System.currentTimeMillis() + Long.parseLong(secondsFromNow) * 1000);
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                values.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static void answer(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
