package com.demo.workloads.common;

import com.demo.workloads.common.events.EventPublisher;
import com.demo.workloads.common.events.Topics;
import com.demo.workloads.common.idempotency.ProcessedEvents;
import com.demo.workloads.common.kafka.BackpressureProperties;
import com.demo.workloads.common.kafka.CircuitBreakerBackpressure;
import com.demo.workloads.common.kafka.DeadLetterFailurePublisher;
import com.demo.workloads.common.vendor.VendorCaller;
import com.demo.workloads.common.vendor.VendorClients;
import com.demo.workloads.common.vendor.VendorProperties;
import com.demo.workloads.common.vendor.VendorUnavailableException;
import com.demo.workloads.common.web.VendorErrorAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.web.client.RestClient;

@AutoConfiguration(after = {KafkaAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@EnableConfigurationProperties({VendorProperties.class, BackpressureProperties.class})
public class DemoCommonAutoConfiguration {

    @Bean
    VendorErrorAdvice vendorErrorAdvice() {
        return new VendorErrorAdvice();
    }

    @Bean
    VendorClients vendorClients(VendorProperties props, RestClient.Builder builder) {
        return new VendorClients(props, builder);
    }

    @Bean
    VendorCaller vendorCaller(VendorClients clients, CircuitBreakerRegistry cb, RetryRegistry retry,
                              RateLimiterRegistry rl, BulkheadRegistry bh, MeterRegistry meters) {
        return new VendorCaller(clients, cb, retry, rl, bh, meters);
    }

    /** Bus support; skipped by services that exclude Kafka (login-service is sync only). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(KafkaTemplate.class)
    static class Bus {
        @Bean
        EventPublisher eventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
            return new EventPublisher(kafka, mapper);
        }

        /** All topics, 12 partitions each (key = entity id). Retry/DLT topics are created by the retry configuration. */
        @Bean
        KafkaAdmin.NewTopics demoTopics(@Value("${demo.kafka.partitions:12}") int partitions,
                                        @Value("${demo.kafka.replicas:1}") short replicas) {
            return new KafkaAdmin.NewTopics(Topics.ALL.stream()
                    .map(t -> TopicBuilder.name(t).partitions(partitions).replicas(replicas).build())
                    .toArray(NewTopic[]::new));
        }

        @Bean
        DeadLetterFailurePublisher deadLetterFailurePublisher(EventPublisher publisher, MeterRegistry meters) {
            return new DeadLetterFailurePublisher(publisher, meters);
        }

        /**
         * Non-blocking retries for command topics: a vendor timeout/5xx moves the record to a delay topic so the main
         * partition keeps flowing. 4 attempts: main, then +5s, +30s, +120s retry topics, then *-dlt.
         */
        @Bean
        RetryTopicConfiguration commandRetryTopics(KafkaTemplate<String, String> template,
                                                   @Value("${demo.kafka.partitions:12}") int partitions,
                                                   @Value("${demo.retry.initial-delay-ms:5000}") long initialDelay,
                                                   @Value("${demo.retry.multiplier:6}") double multiplier,
                                                   @Value("${demo.retry.max-delay-ms:120000}") long maxDelay,
                                                   @Value("${demo.retry.attempts:4}") int attempts) {
            return RetryTopicConfigurationBuilder.newInstance()
                    .exponentialBackoff(initialDelay, multiplier, maxDelay)
                    .maxAttempts(attempts)
                    .retryOn(VendorUnavailableException.class)
                    .traversingCauses()
                    .includeTopics(Topics.COMMAND_TOPICS)
                    .autoCreateTopicsWith(partitions, (short) 1)
                    .dltHandlerMethod("deadLetterFailurePublisher", "onDeadLetter")
                    .create(template);
        }

        @Bean
        @ConditionalOnBean(KafkaListenerEndpointRegistry.class)
        @ConditionalOnProperty(value = "demo.backpressure.enabled", matchIfMissing = true)
        CircuitBreakerBackpressure circuitBreakerBackpressure(CircuitBreakerRegistry breakers,
                                                              KafkaListenerEndpointRegistry listeners,
                                                              BackpressureProperties props, MeterRegistry meters) {
            return new CircuitBreakerBackpressure(breakers, listeners, props, meters);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcTemplate.class)
    static class Jdbc {
        @Bean
        @ConditionalOnBean(JdbcTemplate.class)
        ProcessedEvents processedEvents(JdbcTemplate jdbc) {
            return new ProcessedEvents(jdbc);
        }
    }
}
