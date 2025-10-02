package com.ravi.stockscreener.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;

import okhttp3.OkHttpClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.TcpClient;


import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class RedisConfig {

    @Value("${spring.r2dbc.url}")
    private String r2dbcUrl;

    @Value("${spring.r2dbc.username:}")
    private String r2dbcUser;

    @Value("${spring.r2dbc.password:}")
    private String r2dbcPassword;

    @Value("${app.db.max-size:8}")
    private int poolMaxSize;

    @Value("${app.db.initial-size:2}")
    private int poolInitialSize;


    // WebClient for reactive HTTP
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    // 2) create WebClient from builder (optional; you can also @Autowired Builder where needed)
    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        // tune this to match your upstoxSemaphore (e.g. semaphore = 8 => maxConnections 12-16)
        ConnectionProvider provider = ConnectionProvider.builder("upstox-pool")
                .maxConnections(16)                          // keep <= sensible limit
                .pendingAcquireMaxCount(500)
                .pendingAcquireTimeout(Duration.ofSeconds(30))
                .build();

        // TcpClient wired to the ConnectionProvider
        TcpClient tcpClient = TcpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30))
                        .addHandlerLast(new WriteTimeoutHandler(30)));

        // HttpClient derived from the configured TcpClient
        HttpClient httpClient = HttpClient.from(tcpClient)
                .responseTimeout(Duration.ofSeconds(30))
                .option(ChannelOption.SO_KEEPALIVE, true)
                .compress(true);

        // allow larger in-memory buffers for big JSON payloads if needed
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(16 * 1024 * 1024)) // 16 MB
                .build();

        return builder
                .baseUrl("https://api.upstox.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }


    /*@Bean
    public Gson gson() {
        return new GsonBuilder()
                .registerTypeAdapter(Operand.class, new OperandDeserializer())
                .serializeNulls()
                .setPrettyPrinting()
                .create();
    }*/

    /*@Bean
    public GsonHttpMessageConverter gsonHttpMessageConverter(Gson gson) {
        return new GsonHttpMessageConverter(gson);
    }
*/

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
