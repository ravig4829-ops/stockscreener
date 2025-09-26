package com.ravi.stockscreener.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ravi.stockscreener.model.Operand;
import com.ravi.stockscreener.util.*;
import io.lettuce.core.api.StatefulConnection;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.http.converter.json.GsonHttpMessageConverter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

// spring imports
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;


import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class RedisConfig {

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    // toggle to disable Redis-based beans in dev if needed
    @Value("${app.redis.enabled:true}")
    private boolean appRedisEnabled;

    // Pool + client tuning
    private static final int POOL_MAX_TOTAL = 20;
    private static final int POOL_MAX_IDLE = 10;
    private static final int POOL_MIN_IDLE = 1;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POOL_MAX_WAIT = Duration.ofSeconds(5);

    private final Executor cacheExecutor = Executors.newFixedThreadPool(4);




    @Bean
    public LettuceConnectionFactory lettuceConnectionFactory() {
        // correct generic type for pool config
        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(POOL_MAX_TOTAL);
        poolConfig.setMaxIdle(POOL_MAX_IDLE);
        poolConfig.setMinIdle(POOL_MIN_IDLE);

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .commandTimeout(COMMAND_TIMEOUT)
                .poolConfig(poolConfig)
                .build();

        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (redisPassword != null && !redisPassword.isBlank()) {
            serverConfig.setPassword(redisPassword);
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(serverConfig, clientConfig);
        // ensure factory initializes now
        factory.afterPropertiesSet();
        log.info("Configured LettuceConnectionFactory (host={}, port={}, cmdTimeout={}s)", redisHost, redisPort, COMMAND_TIMEOUT.getSeconds());
        return factory;
    }


    // WebClient for reactive HTTP
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    // 2) create WebClient from builder (optional; you can also @Autowired Builder where needed)
    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        // set to 16 MB (adjust to expected file size)
                        .maxInMemorySize(16 * 1024 * 1024))
                .build();

        return builder
                .exchangeStrategies(strategies)
                .build();
    }

    @Bean
    public Gson gson() {
        return new GsonBuilder()
                .registerTypeAdapter(Operand.class, new OperandDeserializer())
                .serializeNulls()
                .setPrettyPrinting()
                .create();
    }

    @Bean
    public GsonHttpMessageConverter gsonHttpMessageConverter(Gson gson) {
        return new GsonHttpMessageConverter(gson);
    }





    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(LettuceConnectionFactory lettuceConnectionFactory) {
        // ensure factory initialized
        lettuceConnectionFactory.afterPropertiesSet();

        RedisSerializationContext<String, String> serializationContext =
                RedisSerializationContext.<String, String>newSerializationContext(new StringRedisSerializer())
                        .key(new StringRedisSerializer())
                        .value(new StringRedisSerializer())
                        .hashKey(new StringRedisSerializer())
                        .hashValue(new StringRedisSerializer())
                        .build();

        ReactiveStringRedisTemplate template = new ReactiveStringRedisTemplate(lettuceConnectionFactory, serializationContext);
        log.info("ReactiveStringRedisTemplate created");
        return template;
    }


    @Bean
    @ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true", matchIfMissing = true)
    public ReactiveRedisMessageListenerContainer reactiveRedisMessageListenerContainer(LettuceConnectionFactory lettuceConnectionFactory) {
        try {
            // quick check to fail fast if unreachable
            // attempt to get reactive connection (may throw)
            ((ReactiveRedisConnectionFactory) lettuceConnectionFactory).getReactiveConnection();
            ReactiveRedisMessageListenerContainer container = new ReactiveRedisMessageListenerContainer(lettuceConnectionFactory);
            log.info("ReactiveRedisMessageListenerContainer created");
            return container;
        } catch (Exception ex) {
            log.warn("Redis appears unavailable; reactive listener container will not be functional: {}", ex.toString());
            // still return a container object — but it may not subscribe until Redis is reachable.
            // If you prefer to avoid creating it at all, set app.redis.enabled=false in dev.
            return new ReactiveRedisMessageListenerContainer(lettuceConnectionFactory);
        }
    }


    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(60))
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))
                .retryOnConnectionFailure(true)
                .connectionPool(new okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build();
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler t = new ThreadPoolTaskScheduler();
        t.setPoolSize(5);
        t.setThreadNamePrefix("market-scheduler-");
        t.initialize();
        return t;
    }


}
