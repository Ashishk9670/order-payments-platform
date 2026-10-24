package com.example.shop.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** The dead-letter tool against a real broker: parked records come back to their source topic, once, in order. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "security.jwt.secret=integration-test-secret-integration-test-0123456789")
@Import(Containers.class)
class DeadLetterAdminIT {

    static final String SOURCE = "order.created.v1";
    static final String DLT = SOURCE + ".DLT";

    @Autowired TestRestTemplate http;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaConnectionDetails kafkaDetails;
    @Autowired JwtEncoder jwtEncoder;

    private HttpEntity<Void> as(String role) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().subject("it").issuedAt(now).expiresAt(now.plusSeconds(300)).claim("roles", List.of(role)).build();
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue());
        return new HttpEntity<>(headers);
    }

    private JsonNode call(HttpMethod method, String path) {
        return http.exchange(path, method, as("ADMIN"), JsonNode.class).getBody();
    }

    private long pending() {
        for (JsonNode t : call(HttpMethod.GET, "/api/v1/admin/dlt")) {
            if (DLT.equals(t.get("topic").asText())) {
                return t.get("pending").asLong();
            }
        }
        return 0;
    }

    private void park(String key, String sourceTopic, String error) throws Exception {
        var headers = new java.util.ArrayList<org.apache.kafka.common.header.Header>();
        if (sourceTopic != null) {
            headers.add(new RecordHeader("kafka_dlt-original-topic", sourceTopic.getBytes(StandardCharsets.UTF_8)));
        }
        headers.add(new RecordHeader("kafka_dlt-exception-message", error.getBytes(StandardCharsets.UTF_8)));
        kafka.send(new ProducerRecord<>(DLT, null, key, "{\"parked\":\"" + key + "\"}", headers)).get();
    }

    @BeforeEach
    void startFromACleanSlate() throws Exception {
        park("seed-" + UUID.randomUUID(), SOURCE, "make sure the topic exists");
        call(HttpMethod.POST, "/api/v1/admin/dlt/" + DLT + "/acknowledge");
        assertThat(pending()).isZero();
    }

    @Test
    void parkedRecordsAreListedWithTheReasonTheyFailed() throws Exception {
        String key = "peek-" + UUID.randomUUID();
        park(key, SOURCE, "Cannot deserialize OrderCreated");

        assertThat(pending()).isEqualTo(1);
        JsonNode record = call(HttpMethod.GET, "/api/v1/admin/dlt/" + DLT + "/records?limit=5").get(0);
        assertThat(record.get("key").asText()).isEqualTo(key);
        assertThat(record.get("sourceTopic").asText()).isEqualTo(SOURCE);
        assertThat(record.get("error").asText()).isEqualTo("Cannot deserialize OrderCreated");
        assertThat(pending()).as("peeking changes nothing").isEqualTo(1);
    }

    @Test
    void replayReturnsRecordsToTheirSourceTopicInBatchesAndNeverTwice() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        for (int i = 1; i <= 3; i++) {
            park(run + "-" + i, SOURCE, "boom");
        }
        try (var probe = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), SOURCE)) {
            var first = call(HttpMethod.POST, "/api/v1/admin/dlt/" + DLT + "/replay?max=2");
            assertThat(first.get("replayed").asInt()).isEqualTo(2);
            assertThat(first.get("pending").asLong()).isEqualTo(1);
            assertThat(probe.awaitKey(run + "-1", Duration.ofSeconds(30))).isPresent();
            assertThat(probe.awaitKey(run + "-2", Duration.ofSeconds(30))).isPresent();
            assertThat(probe.collect(run + "-3", Duration.ofSeconds(2))).as("third is still parked").isEmpty();

            var rest = call(HttpMethod.POST, "/api/v1/admin/dlt/" + DLT + "/replay?max=10");
            assertThat(rest.get("replayed").asInt()).isEqualTo(1);
            assertThat(rest.get("pending").asLong()).isZero();
            assertThat(probe.awaitKey(run + "-3", Duration.ofSeconds(30))).isPresent();

            var nothingLeft = call(HttpMethod.POST, "/api/v1/admin/dlt/" + DLT + "/replay?max=10");
            assertThat(nothingLeft.get("replayed").asInt()).isZero();
            assertThat(probe.collect(run + "-1", Duration.ofSeconds(2))).as("each record was replayed exactly once").hasSize(1);
        }
    }

    @Test
    void acknowledgeDropsPendingRecordsWithoutReplayingThem() throws Exception {
        String key = "ack-" + UUID.randomUUID();
        park(key, SOURCE, "can never succeed");

        assertThat(call(HttpMethod.POST, "/api/v1/admin/dlt/" + DLT + "/acknowledge").get("acknowledged").asLong()).isEqualTo(1);

        assertThat(pending()).isZero();
        try (var probe = new KafkaProbe(kafkaDetails.getBootstrapServers().get(0), SOURCE)) {
            assertThat(call(HttpMethod.POST, "/api/v1/admin/dlt/" + DLT + "/replay").get("replayed").asInt()).isZero();
            assertThat(probe.collect(key, Duration.ofSeconds(2))).isEmpty();
        }
    }

    @Test
    void aRecordWithNoValidSourceIsSkippedAndNeverPublishedAnywhere() throws Exception {
        park("orphan-" + UUID.randomUUID(), null, "no source header");
        park("hostile-" + UUID.randomUUID(), "payment.authorized.v1.INTERNAL", "points at a topic we do not know");

        var result = call(HttpMethod.POST, "/api/v1/admin/dlt/" + DLT + "/replay");

        assertThat(result.get("replayed").asInt()).isZero();
        assertThat(result.get("skipped").asInt()).isEqualTo(2);
        assertThat(result.get("pending").asLong()).isZero();
    }

    @Test
    void onlyAnAdminMayUseTheTool() {
        assertThat(http.getForEntity("/api/v1/admin/dlt", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (String role : new String[] {"CUSTOMER", "SERVICE"}) {
            assertThat(http.exchange("/api/v1/admin/dlt", HttpMethod.GET, as(role), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(http.exchange("/api/v1/admin/dlt/" + DLT + "/replay", HttpMethod.POST, as(role), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void anUnknownTopicIs404() {
        assertThat(http.exchange("/api/v1/admin/dlt/not.ours.DLT/replay", HttpMethod.POST, as("ADMIN"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
