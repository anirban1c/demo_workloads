package com.demo.workloads.gateway.throttle;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Dedicated Lettuce client for Bucket4j (Spring Data Redis keeps its own pool for the status store). */
@Configuration(proxyBeanMethods = false)
public class RedisClientConfig {
    @Bean(destroyMethod = "shutdown")
    RedisClient bucket4jRedisClient(RedisProperties props) {
        RedisURI.Builder uri = RedisURI.builder().withHost(props.getHost()).withPort(props.getPort());
        if (props.getPassword() != null) uri.withPassword(props.getPassword().toCharArray());
        return RedisClient.create(uri.build());
    }
}
