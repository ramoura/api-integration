# Guia de Implementação do ApplicationWarmup para Spring Boot: Aplicações Reativas em Produção

O warmup de aplicações Spring Boot é crítico para alcançar performance consistente desde a primeira requisição, especialmente em aplicações reativas usando WebFlux, Redis e circuit breakers. Este guia abrangente fornece padrões prontos para produção otimizados para deployments AWS ECS/Fargate com Spring Boot 3.x.

## Visão geral da estratégia de warmup

**A abordagem ótima de warmup combina múltiplas técnicas**: ApplicationReadyEvent listeners para inicialização de componentes HTTP, CommandLineRunner para warmup de database, health indicators customizados para prevenir roteamento prematuro de tráfego, e otimizações específicas da JVM. Pesquisas mostram que esta estratégia multi-camadas pode reduzir latência da primeira requisição em 70-90% e melhorar a confiabilidade geral de startup da aplicação em ambientes containerizados.

Aplicações Spring Boot modernas se beneficiam de **padrões de warmup reactive-first** que aproveitam a arquitetura não-bloqueante do WebFlux. O insight chave é que abordagens tradicionais de warmup bloqueante não se alinham bem com modelos de programação reativa, requerendo padrões especializados para event loops do Netty, conexões Redis reativas, e inicialização de circuit breakers.

**Contextos de deployment AWS ECS** introduzem considerações adicionais sobre health checks de containers, timing de registro do load balancer, e otimização de recursos. O tamanho da imagem do container tem o maior impacto na prontidão da task - reduzir imagens de 900MB para 200MB pode melhorar o tempo de pull em 75%. Configuração de health check com períodos de grace apropriados previne roteamento prematuro de tráfego antes da conclusão do warmup.

## Seleção do mecanismo de warmup Spring Boot

### Comparação ApplicationListener vs @EventListener vs CommandLineRunner

**ApplicationListener<ApplicationReadyEvent>** fornece type safety em tempo de compilação e executa após toda auto-configuração estar completa, tornando-o ideal para warmup de componentes HTTP. Esta abordagem não afeta a medição do tempo de startup mas garante que toda infraestrutura esteja pronta.

```java
@Component
public class WebFluxWarmupListener implements ApplicationListener<ApplicationReadyEvent> {
    private final WebClient webClient;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Mono<Void> warmupSequence = Mono.when(
            warmupWebClient(),
            warmupRedisConnections(),
            warmupNettyEventLoops()
        );
        
        warmupSequence
            .timeout(Duration.ofMinutes(2))
            .doOnSuccess(v -> log.info("Warmup reativo completado"))
            .doOnError(error -> log.error("Warmup falhou", error))
            .subscribe();
    }
}
```

**@EventListener** oferece mais flexibilidade com execução condicional e capacidades de manipulação de múltiplos eventos. A abordagem baseada em anotação suporta configuração no nível do método e cenários complexos de processamento de eventos.

```java
@Component
public class FlexibleWarmupHandler {
    @EventListener
    @Order(1)
    public void handleApplicationReady(ApplicationReadyEvent event) {
        // Lógica de warmup principal
    }
    
    @EventListener(condition = "#event.timeTaken.toMillis() < 5000")
    public void handleFastStartup(ApplicationReadyEvent event) {
        // Otimização adicional para startups rápidos
    }
}
```

**CommandLineRunner** executa antes do ApplicationReadyEvent e afeta a medição do tempo de startup. Esta abordagem é ótima para connection pools de database e warmup da JVM que deve completar antes de aceitar qualquer tráfego.

```java
@Component
@Order(1)
public class DatabaseWarmupRunner implements CommandLineRunner {
    @Override
    public void run(String... args) throws Exception {
        initializeConnectionPools();
        performJvmWarmup();
        warmupCriticalBeans();
    }
}
```

### Considerações para native image Spring Boot 3.x

**Compatibilidade com native image** requer atenção cuidadosa ao uso de reflection no código de warmup. O processo de compilação nativa GraalVM analisa código em build time, requerendo configuração explícita para comportamentos dinâmicos.

```java
@RegisterReflectionForBinding({
    WarmupConfig.class,
    CacheConfiguration.class
})
@Component
public class NativeCompatibleWarmup implements ApplicationListener<ApplicationReadyEvent> {
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // Warmup seguro para native - evitar reflection, carregamento dinâmico
        warmupCaches();
        warmupConnectionPools();
    }
}
```

## Warmup de WebFlux e aplicações reativas

### Otimização do event loop Netty

**Inicialização do EventLoopGroup do Netty** usa por padrão `2 * processors disponíveis` threads mas recursos são inicializados sob demanda, causando latência na requisição inicial. Warmup explícito previne esta penalidade de cold start.

```java
@Configuration
public class NettyWarmupConfig {
    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> nettyCustomizer() {
        return factory -> {
            factory.addServerCustomizers(httpServer -> {
                return httpServer.warmup().then(httpServer);
            });
        };
    }
}
```

**Warmup do connection pool do WebClient** requer pré-estabelecimento de conexões para evitar atrasos de inicialização durante primeiras requisições. A configuração do connection provider impacta diretamente a efetividade do warmup.

```java
@Configuration
public class WebClientWarmupConfig {
    @Bean
    public WebClient webClient() {
        ConnectionProvider connectionProvider = ConnectionProvider
            .builder("webclient-conn-pool")
            .maxConnections(200)
            .maxIdleTime(Duration.ofSeconds(30))
            .maxLifeTime(Duration.ofSeconds(60))
            .pendingAcquireTimeout(Duration.ofSeconds(10))
            .build();
            
        HttpClient httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .responseTimeout(Duration.ofSeconds(30));
            
        // Crítico: warmup do HTTP client
        httpClient.warmup().block();
        
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
```

### Padrões de warmup Redis reativo

**ReactiveRedisTemplate com Lettuce** requer pré-inicialização do connection pool e execução de operação baseline para estabelecer características de performance ótimas desde o primeiro uso.

```java
@Component
public class RedisWarmupService {
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    
    @EventListener(ApplicationReadyEvent.class)
    public void warmupRedisConnections() {
        List<Mono<String>> warmupOperations = IntStream.range(0, 10)
            .mapToObj(i -> performWarmupOperation("warmup-key-" + i))
            .collect(Collectors.toList());
            
        Flux.merge(warmupOperations)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                result -> log.debug("Warmup Redis completado: {}", result),
                error -> log.warn("Warmup Redis falhou: {}", error.getMessage()),
                () -> log.info("Warmup do connection pool Redis completado")
            );
    }
    
    private Mono<String> performWarmupOperation(String key) {
        return redisTemplate.opsForValue()
            .set(key, "warmup-value", Duration.ofSeconds(10))
            .then(redisTemplate.opsForValue().get(key))
            .cast(String.class)
            .then(redisTemplate.delete(key))
            .thenReturn("completed");
    }
}
```

### Warmup de scheduler e thread pool

**Warmup de scheduler reativo** garante que thread pools são pré-criados e otimizados antes de lidar com workloads de produção. Schedulers diferentes servem propósitos distintos e requerem abordagens de warmup personalizadas.

```java
@Component
public class SchedulerWarmupService {
    @EventListener(ApplicationReadyEvent.class)
    public void warmupSchedulers() {
        // Warmup parallel scheduler para tarefas CPU-intensivas
        List<Mono<Void>> parallelTasks = IntStream.range(0, 100)
            .mapToObj(i -> Mono.fromRunnable(() -> {
                Math.pow(i, 2); // Simular trabalho de CPU
            }).subscribeOn(Schedulers.parallel()))
            .collect(Collectors.toList());
            
        // Warmup bounded elastic scheduler para operações I/O
        List<Mono<Void>> elasticTasks = IntStream.range(0, 50)
            .mapToObj(i -> Mono.fromCallable(() -> {
                Thread.sleep(10); // Simular I/O
                return null;
            }).subscribeOn(Schedulers.boundedElastic()).then())
            .collect(Collectors.toList());
            
        Flux.merge(parallelTasks)
            .then(Flux.merge(elasticTasks).then())
            .subscribe(
                null,
                error -> log.warn("Warmup de scheduler falhou", error),
                () -> log.info("Warmup de scheduler completado")
            );
    }
}
```

## Warmup de circuit breaker com Resilience4j

### Estabelecimento de métricas baseline

**Warmup de circuit breaker** requer estabelecer métricas baseline antes do tráfego de produção chegar. Circuit breakers Resilience4j precisam de contagens mínimas de chamadas antes que cálculos de taxa de falha comecem.

```java
@Component
public class CircuitBreakerWarmupService {
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
    
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        warmupCircuitBreakers();
    }
    
    private void warmupCircuitBreakers() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry
            .circuitBreaker("backendService");
        
        // Configurar para fase de warmup - coletar métricas sem abrir
        circuitBreaker.transitionToMetricsOnlyState();
        
        // Realizar chamadas baseline
        for (int i = 0; i < 10; i++) {
            try {
                String result = circuitBreaker.executeSupplier(() -> 
                    performHealthCheck());
            } catch (Exception e) {
                log.debug("Chamada de warmup falhou (esperado)", e);
            }
        }
        
        // Transição para operação normal
        circuitBreaker.transitionToClosedState();
        log.info("Warmup de circuit breaker completado");
    }
}
```

### Integração de circuit breaker reativo

**Warmup de circuit breaker WebFlux** combina streams reativos com padrões de resiliência, requerendo operadores especializados para integração adequada.

```java
@Service
public class ReactiveCircuitBreakerWarmup {
    private final WebClient webClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    
    @CircuitBreaker(name = "reactiveService")
    public Mono<String> performReactiveWarmup() {
        return webClient.get()
            .uri("/warmup")
            .retrieve()
            .bodyToMono(String.class)
            .transformDeferred(CircuitBreakerOperator.of(
                circuitBreakerRegistry.circuitBreaker("reactiveService")))
            .timeout(Duration.ofSeconds(5))
            .retry(3);
    }
}
```

### Tratamento de erro durante warmup

**Estratégias de degradação graciosa** garantem que o startup da aplicação tenha sucesso mesmo quando operações de warmup falhem, prevenindo falhas em cascata durante deployment.

```java
@Component
public class FallbackChainWarmup {
    @CircuitBreaker(name = "primaryDb", fallbackMethod = "secondaryDbFallback")
    public String getPrimaryData() {
        return primaryDatabase.getData();
    }
    
    @CircuitBreaker(name = "secondaryDb", fallbackMethod = "cacheFallback")  
    public String secondaryDbFallback(Exception ex) {
        return secondaryDatabase.getData();
    }
    
    @CircuitBreaker(name = "cache", fallbackMethod = "staticFallback")
    public String cacheFallback(Exception ex) {
        return cacheService.getData();
    }
    
    public String staticFallback(Exception ex) {
        return "Resposta padrão - serviços fazendo warmup";
    }
}
```

## AWS ECS e padrões de deployment em produção

### Integração com health check de container

**Configuração de health check ECS** deve considerar o tempo de warmup da aplicação para prevenir terminação prematura de task ou roteamento de tráfego.

```json
{
  "healthCheck": {
    "command": ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health/readiness || exit 1"],
    "interval": 30,
    "timeout": 10,
    "retries": 3,
    "startPeriod": 120
  }
}
```

**Health indicators customizados** fornecem sinais de readiness conscientes do warmup, prevenindo registro no load balancer até que a aplicação esteja totalmente preparada.

```java
@Component
public class WarmupHealthIndicator implements HealthIndicator {
    private volatile boolean warmupComplete = false;
    private final AtomicInteger warmupProgress = new AtomicInteger(0);
    private final int totalWarmupSteps = 4;
    
    @Override
    public Health health() {
        if (warmupComplete) {
            return Health.up()
                .withDetail("warmup", "completed")
                .withDetail("progress", "100%")
                .build();
        } else {
            int progress = (warmupProgress.get() * 100) / totalWarmupSteps;
            return Health.down()
                .withDetail("warmup", "in-progress")
                .withDetail("progress", progress + "%")
                .build();
        }
    }
    
    public void incrementProgress() {
        int current = warmupProgress.incrementAndGet();
        if (current >= totalWarmupSteps) {
            warmupComplete = true;
        }
    }
}
```

### Otimização de performance para containers

**Otimização de startup de container** foca na redução do tamanho da imagem e configuração de recursos. Pesquisas mostram que o tamanho da imagem de container tem o maior impacto no tempo de prontidão da task.

```dockerfile
# Build multi-stage otimizado para Spring Boot 3.x
FROM openjdk:17-jdk-slim as builder
WORKDIR /app
COPY . .
RUN ./mvnw clean package -DskipTests

FROM openjdk:17-jre-slim
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=3"
COPY --from=builder /app/target/app.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

**Configuração de serviço ECS** para comportamento ótimo de warmup inclui alocação adequada de recursos e configurações de deployment.

```json
{
  "deploymentConfiguration": {
    "maximumPercent": 200,
    "minimumHealthyPercent": 50,
    "deploymentCircuitBreaker": {
      "enable": true,
      "rollback": true
    }
  },
  "healthCheckGracePeriodSeconds": 300
}
```

## Otimização da JVM para efetividade do warmup

### Aceleração da compilação JIT

**Otimização de compilação tiered** fornece o melhor equilíbrio entre tempo de startup e performance de pico para aplicações Spring Boot.

```bash
# Flags JVM otimizadas para warmup Spring Boot
-XX:+TieredCompilation
-XX:TieredStopAtLevel=3
-XX:CompileThreshold=1000
-XX:ReservedCodeCacheSize=256M
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
```

**Implementação de warmup de código** força compilação JIT de caminhos críticos através de padrões de execução repetitivos.

```java
@Component
public class JITWarmupService {
    @PostConstruct
    public void warmupCriticalPaths() {
        // Forçar compilação JIT de métodos hot
        for (int i = 0; i < 20000; i++) {
            executeBusinessLogic();
            processOrder(createTestOrder());
            calculateMetrics();
        }
    }
    
    private void executeBusinessLogic() {
        // Simular caminhos críticos da aplicação
        validateInput();
        transformData();
        processResult();
    }
}
```

### Padrões de alocação de memória

**Estratégias de pré-alocação** estabelecem padrões ótimos de memória durante o warmup, reduzindo pressão de garbage collection durante operação de produção.

```java
@Service
public class MemoryWarmupService {
    private final List<ProcessingTask> taskBuffer = new ArrayList<>(10000);
    private final ObjectPool<ExpensiveObject> objectPool = 
        new ObjectPool<>(ExpensiveObject::new, 100);
    
    @PostConstruct
    public void initializeMemory() {
        preAllocateBuffers();
        warmUpObjectPools();
    }
    
    private void preAllocateBuffers() {
        // Estabelecer padrões de alocação na young generation
        for (int i = 0; i < 50000; i++) {
            taskBuffer.add(new ProcessingTask());
        }
        taskBuffer.clear(); // Disparar GC para estabelecer padrões
    }
}
```

## Implementação abrangente para produção

### Serviço de warmup orquestrado

**Orquestração de warmup pronta para produção** coordena múltiplas fases de warmup com tratamento adequado de erro e integração de monitoramento.

```java
@Component
@Slf4j
public class ProductionWarmupOrchestrator implements ApplicationListener<ApplicationReadyEvent> {
    
    private final WebClient webClient;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final MeterRegistry meterRegistry;
    private final WarmupHealthIndicator healthIndicator;
    
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Timer.Sample sample = Timer.start(meterRegistry);
        log.info("Iniciando sequência de warmup de produção...");
        
        Mono<Void> warmupSequence = Mono.when(
            warmupNettyComponents(),
            warmupRedisConnections(),
            warmupCircuitBreakers(),
            warmupExternalServices()
        );
        
        warmupSequence
            .timeout(Duration.ofMinutes(3))
            .doOnSuccess(v -> {
                sample.stop(Timer.builder("app.warmup.duration")
                    .register(meterRegistry));
                healthIndicator.markWarmupComplete();
                log.info("Warmup de produção completado com sucesso");
            })
            .doOnError(error -> {
                log.error("Warmup falhou mas aplicação continuará", error);
                healthIndicator.markWarmupPartiallyComplete();
            })
            .subscribe();
    }
    
    private Mono<Void> warmupNettyComponents() {
        return Mono.fromRunnable(() -> {
            // Warmup dos connection pools do WebClient
            IntStream.range(0, 10).parallel().forEach(i -> {
                webClient.get()
                    .uri("http://httpbin.org/delay/1")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .onErrorResume(e -> Mono.empty())
                    .subscribe();
            });
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    private Mono<Void> warmupRedisConnections() {
        return Flux.range(0, 5)
            .flatMap(i -> redisTemplate.opsForValue()
                .set("warmup:" + i, "value", Duration.ofSeconds(10))
                .then(redisTemplate.delete("warmup:" + i)))
            .then()
            .onErrorResume(error -> {
                log.warn("Warmup Redis falhou", error);
                return Mono.empty();
            });
    }
    
    private Mono<Void> warmupCircuitBreakers() {
        return Mono.fromRunnable(() -> {
            circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(cb -> {
                    cb.transitionToMetricsOnlyState();
                    // Realizar chamadas baseline
                    for (int i = 0; i < 5; i++) {
                        try {
                            cb.executeSupplier(() -> "warmup");
                        } catch (Exception e) {
                            log.debug("Chamada de warmup do circuit breaker falhou", e);
                        }
                    }
                    cb.transitionToClosedState();
                });
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    private Mono<Void> warmupExternalServices() {
        return Flux.fromIterable(Arrays.asList(
                "/actuator/health",
                "/api/status",
                "/warmup/endpoint"
            ))
            .flatMap(endpoint -> webClient.get()
                .uri("http://localhost:8080" + endpoint)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(error -> {
                    log.debug("Warmup de endpoint falhou para {}", endpoint);
                    return Mono.empty();
                }))
            .then();
    }
}
```

### Integração de monitoramento e observabilidade

**Monitoramento abrangente de warmup** fornece visibilidade na efetividade do warmup e características de performance através de diferentes ambientes de deployment.

```java
@Component
public class WarmupObservabilityService {
    private final MeterRegistry meterRegistry;
    
    public WarmupObservabilityService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        configureWarmupMetrics();
    }
    
    private void configureWarmupMetrics() {
        // Monitoramento de compilação JIT
        Gauge.builder("jvm.compilation.pending")
            .description("Tarefas de compilação JIT pendentes")
            .register(meterRegistry, this, this::getPendingCompilations);
        
        // Taxa de alocação de memória durante warmup
        Gauge.builder("jvm.memory.allocation.rate")
            .description("Taxa de alocação de memória")
            .register(meterRegistry, this, this::getAllocationRate);
        
        // Estados de circuit breaker
        circuitBreakerRegistry.getAllCircuitBreakers()
            .forEach(cb -> Gauge.builder("resilience4j.circuitbreaker.state")
                .tag("name", cb.getName())
                .description("Estado do circuit breaker")
                .register(meterRegistry, cb, 
                    circuitBreaker -> circuitBreaker.getState().getOrder()));
    }
    
    private double getPendingCompilations(WarmupObservabilityService service) {
        return ManagementFactory.getCompilationMXBean().getTotalCompilationTime();
    }
    
    private double getAllocationRate(WarmupObservabilityService service) {
        return ManagementFactory.getMemoryMXBean()
            .getHeapMemoryUsage().getUsed();
    }
}
```

Esta implementação abrangente de warmup fornece padrões prontos para produção para aplicações Spring Boot 3.x usando WebFlux, Redis e circuit breakers, otimizados para ambientes de deployment AWS ECS/Fargate. A abordagem multi-camadas garante performance consistente desde a primeira requisição enquanto mantém tratamento robusto de erro e observabilidade.



### Warmup completo dos Controllers
Adicione este método na sua classe:
```java
/**
 * Aquece os controllers da aplicação realizando chamadas reais aos endpoints
 */
private Mono<Void> warmupControllers() {
    log.debug("Iniciando warmup dos controllers...");
    
    // Liste os principais endpoints da sua aplicação
    List<WarmupEndpoint> endpoints = List.of(
        new WarmupEndpoint("GET", "/api/users", null),
        new WarmupEndpoint("POST", "/api/users", createSampleUserPayload()),
        new WarmupEndpoint("GET", "/api/products", null),
        new WarmupEndpoint("GET", "/actuator/health", null),
        // Adicione seus endpoints principais aqui
    );
    
    return Flux.fromIterable(endpoints)
        .flatMap(endpoint -> performControllerWarmup(endpoint))
        .collectList()
        .doOnSuccess(results -> {
            long successCount = results.stream().filter(Boolean::booleanValue).count();
            log.debug("Controllers warmup: {}/{} endpoints aquecidos", successCount, results.size());
            meterRegistry.counter("webflux.warmup.controllers.success").increment(successCount);
        })
        .then();
}

private Mono<Boolean> performControllerWarmup(WarmupEndpoint endpoint) {
    WebClient.RequestHeadersSpec<?> request = switch (endpoint.method()) {
        case "GET" -> webClient.get().uri("http://localhost:" + serverPort + endpoint.path());
        case "POST" -> webClient.post()
                .uri("http://localhost:" + serverPort + endpoint.path())
                .bodyValue(endpoint.payload() != null ? endpoint.payload() : "{}");
        case "PUT" -> webClient.put()
                .uri("http://localhost:" + serverPort + endpoint.path())
                .bodyValue(endpoint.payload() != null ? endpoint.payload() : "{}");
        default -> webClient.get().uri("http://localhost:" + serverPort + endpoint.path());
    };
    
    // Fazer múltiplas chamadas para trigger JIT compilation
    return Flux.range(0, 15) // 15 chamadas por endpoint
        .flatMap(i -> request.retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(5))
            .map(response -> true)
            .onErrorReturn(false))
        .collectList()
        .map(results -> results.stream().anyMatch(Boolean::booleanValue));
}

private record WarmupEndpoint(String method, String path, Object payload) {}

@Value("${server.port:8080}")
private int serverPort;
```



```java
```

### Exemplos de Implementação


```java
@Component
@Slf4j
public class ApplicationWarmup implements ApplicationListener<ApplicationReadyEvent> {
    
    @Autowired
    private ReactiveRedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private IntegratorSessionRepository repository;
    
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("Iniciando warmup da aplicação...");
        
        // Warmup Redis
        warmupRedis()
            .doOnSuccess(v -> log.info("Warmup Redis completado"))
            .doOnError(e -> log.warn("Warmup Redis falhou", e))
            .subscribe();
        
        // Warmup JVM
        warmupJVM();
        
        log.info("Warmup da aplicação concluído");
    }
    
    private Mono<Void> warmupRedis() {
        return Flux.range(0, 20)
            .flatMap(i -> {
                String key = "warmup:session:" + i;
                return redisTemplate.opsForValue()
                    .set(key, "warmup-value", Duration.ofSeconds(5))
                    .then(redisTemplate.delete(key));
            })
            .then();
    }
    
    private void warmupJVM() {
        // Simular operações críticas para disparar compilação JIT
        for (int i = 0; i < 1000; i++) {
            // Suas operações mais comuns
            repository.findBySessionId(new SessionId("warmup-" + i))
                .subscribe();
        }
    }
}
```


```java
package com.example.warmup;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class WebFluxWarmupListener implements ApplicationListener<ApplicationReadyEvent> {
    
    private static final Logger log = LoggerFactory.getLogger(WebFluxWarmupListener.class);
    
    private final WebClient webClient;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;
    
    @Autowired
    public WebFluxWarmupListener(WebClient webClient, 
                                ReactiveRedisTemplate<String, Object> redisTemplate,
                                MeterRegistry meterRegistry) {
        this.webClient = webClient;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }
    
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Timer.Sample sample = Timer.start(meterRegistry);
        log.info("Iniciando warmup do WebFlux...");
        
        Mono<Void> warmupSequence = Mono.when(
            warmupWebClient(),
            warmupRedisConnections(),
            warmupNettyEventLoops()
        );
        
        warmupSequence
            .timeout(Duration.ofMinutes(2))
            .doOnSuccess(v -> {
                sample.stop(Timer.builder("webflux.warmup.duration")
                    .description("Tempo total de warmup WebFlux")
                    .register(meterRegistry));
                log.info("Warmup WebFlux completado com sucesso");
            })
            .doOnError(error -> {
                log.error("Warmup WebFlux falhou mas aplicação continuará", error);
                meterRegistry.counter("webflux.warmup.errors",
                    "error.type", error.getClass().getSimpleName())
                    .increment();
            })
            .subscribe();
    }
    
    /**
     * Aquece o WebClient realizando requisições de teste para estabelecer
     * connection pools e compilação JIT
     */
    private Mono<Void> warmupWebClient() {
        log.debug("Iniciando warmup do WebClient...");
        
        return Mono.fromRunnable(() -> {
            // Lista de endpoints para warmup
            List<String> warmupEndpoints = List.of(
                "http://httpbin.org/status/200",
                "http://httpbin.org/delay/1",
                "http://httpbin.org/json",
                "https://httpbin.org/get"
            );
            
            // Realizar múltiplas requisições paralelas para cada endpoint
            List<Mono<String>> warmupRequests = warmupEndpoints.stream()
                .flatMap(endpoint -> IntStream.range(0, 3) // 3 requisições por endpoint
                    .mapToObj(i -> performWarmupRequest(endpoint, i)))
                .collect(Collectors.toList());
            
            // Executar todas as requisições em paralelo
            Flux.merge(warmupRequests)
                .parallel(4) // 4 threads paralelas
                .runOn(Schedulers.boundedElastic())
                .sequential()
                .collectList()
                .doOnSuccess(results -> {
                    long successCount = results.stream()
                        .filter(result -> !"ERROR".equals(result))
                        .count();
                    log.debug("WebClient warmup completado: {}/{} requisições bem-sucedidas", 
                        successCount, results.size());
                    
                    meterRegistry.counter("webflux.warmup.webclient.requests.total")
                        .increment(results.size());
                    meterRegistry.counter("webflux.warmup.webclient.requests.success")
                        .increment(successCount);
                })
                .doOnError(error -> log.warn("Erro durante warmup do WebClient", error))
                .onErrorResume(error -> Mono.empty())
                .block(Duration.ofSeconds(30));
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
    }
    
    /**
     * Realiza uma requisição de warmup individual
     */
    private Mono<String> performWarmupRequest(String endpoint, int attempt) {
        return webClient.get()
            .uri(endpoint)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(5))
            .map(response -> "SUCCESS-" + attempt)
            .onErrorReturn("ERROR")
            .doOnNext(result -> log.trace("Warmup request para {} (tentativa {}): {}", 
                endpoint, attempt, result));
    }
    
    /**
     * Aquece as conexões Redis realizando operações básicas para estabelecer
     * connection pool e testar performance
     */
    private Mono<Void> warmupRedisConnections() {
        log.debug("Iniciando warmup das conexões Redis...");
        
        return Flux.range(0, 20) // 20 operações de warmup
            .flatMap(i -> performRedisWarmupOperation("warmup:key:" + i, "warmup-value-" + i))
            .collectList()
            .doOnSuccess(results -> {
                long successCount = results.stream()
                    .filter(Boolean::booleanValue)
                    .count();
                log.debug("Redis warmup completado: {}/{} operações bem-sucedidas", 
                    successCount, results.size());
                
                meterRegistry.counter("webflux.warmup.redis.operations.total")
                    .increment(results.size());
                meterRegistry.counter("webflux.warmup.redis.operations.success")
                    .increment(successCount);
            })
            .doOnError(error -> {
                log.warn("Erro durante warmup do Redis", error);
                meterRegistry.counter("webflux.warmup.redis.errors").increment();
            })
            .onErrorResume(error -> Mono.just(List.of())) // Continue mesmo com erro
            .then();
    }
    
    /**
     * Realiza uma operação completa de Redis: SET -> GET -> DELETE
     */
    private Mono<Boolean> performRedisWarmupOperation(String key, String value) {
        return redisTemplate.opsForValue()
            .set(key, value, Duration.ofSeconds(10)) // TTL de 10 segundos
            .then(redisTemplate.opsForValue().get(key))
            .flatMap(retrievedValue -> {
                if (value.equals(retrievedValue)) {
                    return redisTemplate.delete(key).thenReturn(true);
                } else {
                    log.trace("Valor Redis não confere: esperado={}, obtido={}", value, retrievedValue);
                    return Mono.just(false);
                }
            })
            .timeout(Duration.ofSeconds(2))
            .doOnNext(success -> log.trace("Operação Redis para chave {}: {}", key, 
                success ? "SUCESSO" : "FALHA"))
            .onErrorReturn(false);
    }
    
    /**
     * Aquece os Event Loops do Netty criando conexões temporárias e
     * executando tarefas para inicializar threads e buffers
     */
    private Mono<Void> warmupNettyEventLoops() {
        log.debug("Iniciando warmup dos Event Loops Netty...");
        
        return Mono.fromCallable(() -> {
            try {
                // Criar EventLoopGroup temporário para warmup
                EventLoopGroup warmupEventLoopGroup = new NioEventLoopGroup(4);
                
                // Criar ConnectionProvider personalizado para warmup
                ConnectionProvider warmupConnectionProvider = ConnectionProvider
                    .builder("warmup-pool")
                    .maxConnections(10)
                    .maxIdleTime(Duration.ofSeconds(5))
                    .maxLifeTime(Duration.ofSeconds(10))
                    .pendingAcquireTimeout(Duration.ofSeconds(2))
                    .build();
                
                // Criar HttpClient temporário
                HttpClient warmupHttpClient = HttpClient
                    .create(warmupConnectionProvider)
                    .runOn(warmupEventLoopGroup)
                    .responseTimeout(Duration.ofSeconds(3));
                
                // Realizar múltiplas conexões para aquecer os event loops
                CountDownLatch latch = new CountDownLatch(8);
                
                for (int i = 0; i < 8; i++) {
                    final int attempt = i;
                    warmupHttpClient
                        .get()
                        .uri("http://httpbin.org/status/200")
                        .response()
                        .timeout(Duration.ofSeconds(3))
                        .doOnNext(response -> {
                            log.trace("Netty warmup connection {} - Status: {}", 
                                attempt, response.status().code());
                            meterRegistry.counter("webflux.warmup.netty.connections.success")
                                .increment();
                        })
                        .doOnError(error -> {
                            log.trace("Netty warmup connection {} falhou: {}", 
                                attempt, error.getMessage());
                            meterRegistry.counter("webflux.warmup.netty.connections.error")
                                .increment();
                        })
                        .doFinally(signal -> latch.countDown())
                        .subscribe();
                }
                
                // Aguardar conclusão com timeout
                boolean completed = latch.await(15, TimeUnit.SECONDS);
                log.debug("Netty warmup: {} conexões processadas em 15s", 
                    8 - latch.getCount());
                
                // Limpar recursos temporários
                try {
                    warmupConnectionProvider.dispose();
                    warmupEventLoopGroup.shutdownGracefully(100, 500, TimeUnit.MILLISECONDS)
                        .sync();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug("Interrompido durante cleanup do Netty warmup");
                }
                
                // Aquecer schedulers do Reactor
                warmupReactorSchedulers();
                
                return completed;
                
            } catch (Exception e) {
                log.warn("Erro durante warmup do Netty", e);
                meterRegistry.counter("webflux.warmup.netty.errors").increment();
                return false;
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnNext(success -> log.debug("Netty Event Loops warmup: {}", 
            success ? "SUCESSO" : "PARCIAL"))
        .then();
    }
    
    /**
     * Aquece os schedulers do Reactor executando tarefas em cada tipo
     */
    private void warmupReactorSchedulers() {
        log.trace("Aquecendo schedulers do Reactor...");
        
        // Warmup parallel scheduler (CPU-bound tasks)
        List<Mono<Integer>> parallelTasks = IntStream.range(0, 50)
            .mapToObj(i -> Mono.fromCallable(() -> {
                // Simular trabalho CPU-intensivo
                int result = 0;
                for (int j = 0; j < 1000; j++) {
                    result += Math.sin(i * j) * Math.cos(i * j);
                }
                return result;
            }).subscribeOn(Schedulers.parallel()))
            .collect(Collectors.toList());
        
        // Warmup boundedElastic scheduler (I/O-bound tasks)
        List<Mono<Void>> elasticTasks = IntStream.range(0, 20)
            .mapToObj(i -> Mono.fromCallable(() -> {
                try {
                    // Simular I/O
                    Thread.sleep(5);
                    return null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }).subscribeOn(Schedulers.boundedElastic()).then())
            .collect(Collectors.toList());
        
        // Executar ambos os tipos de task
        try {
            Mono.when(
                Flux.merge(parallelTasks).then(),
                Flux.merge(elasticTasks).then()
            )
            .timeout(Duration.ofSeconds(5))
            .block();
            
            log.trace("Reactor schedulers aquecidos com sucesso");
            meterRegistry.counter("webflux.warmup.schedulers.success").increment();
            
        } catch (Exception e) {
            log.trace("Erro durante warmup dos schedulers: {}", e.getMessage());
            meterRegistry.counter("webflux.warmup.schedulers.error").increment();
        }
    }
}

// =============================================================================
// CONFIGURAÇÃO ADICIONAL PARA WEBFLUX WARMUP
// =============================================================================

@Configuration
@ConditionalOnProperty(name = "app.warmup.webflux.enabled", havingValue = "true", matchIfMissing = true)
class WebFluxWarmupConfiguration {
    
    /**
     * WebClient otimizado para warmup com connection pooling adequado
     */
    @Bean
    @Primary
    public WebClient webClient() {
        ConnectionProvider connectionProvider = ConnectionProvider
            .builder("warmup-ready-pool")
            .maxConnections(100)
            .maxIdleTime(Duration.ofSeconds(30))
            .maxLifeTime(Duration.ofMinutes(5))
            .pendingAcquireTimeout(Duration.ofSeconds(10))
            .evictInBackground(Duration.ofSeconds(60))
            .build();
        
        HttpClient httpClient = HttpClient
            .create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .responseTimeout(Duration.ofSeconds(30))
            .keepAlive(true);
        
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}

// =============================================================================
// PROPERTIES DE CONFIGURAÇÃO
// =============================================================================

@ConfigurationProperties(prefix = "app.warmup.webflux")
@Data
class WebFluxWarmupProperties {
    
    /**
     * Habilitar warmup do WebFlux
     */
    private boolean enabled = true;
    
    /**
     * Timeout total para warmup
     */
    private Duration timeout = Duration.ofMinutes(2);
    
    /**
     * Número de requisições de warmup por endpoint
     */
    private int warmupRequestsPerEndpoint = 3;
    
    /**
     * Número de operações Redis para warmup
     */
    private int redisWarmupOperations = 20;
    
    /**
     * Número de conexões Netty para warmup
     */
    private int nettyWarmupConnections = 8;
    
    /**
     * Endpoints customizados para warmup (opcional)
     */
    private List<String> customWarmupEndpoints = List.of();
}

// =============================================================================
// HEALTH INDICATOR PARA MONITORAMENTO
// =============================================================================

@Component
public class WebFluxWarmupHealthIndicator implements HealthIndicator {
    
    private volatile boolean warmupCompleted = false;
    private volatile String lastWarmupStatus = "NOT_STARTED";
    private volatile Instant lastWarmupTime;
    
    @EventListener
    public void handleWarmupCompletion(ApplicationReadyEvent event) {
        // Este método será chamado após o warmup
        this.warmupCompleted = true;
        this.lastWarmupStatus = "COMPLETED";
        this.lastWarmupTime = Instant.now();
    }
    
    @Override
    public Health health() {
        Health.Builder builder = warmupCompleted ? Health.up() : Health.down();
        
        return builder
            .withDetail("warmup.status", lastWarmupStatus)
            .withDetail("warmup.completed", warmupCompleted)
            .withDetail("warmup.lastExecution", lastWarmupTime)
            .withDetail("warmup.components", List.of("WebClient", "Redis", "Netty"))
            .build();
    }
}

```

V2
```java
package com.example.warmup;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class WebFluxWarmupListener implements ApplicationListener<ApplicationReadyEvent> {
    
    private static final Logger log = LoggerFactory.getLogger(WebFluxWarmupListener.class);
    
    private final WebClient webClient;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final WebFluxWarmupHealthIndicator healthIndicator;
    
    @Autowired
    public WebFluxWarmupListener(WebClient webClient, 
                                ReactiveRedisTemplate<String, Object> redisTemplate,
                                MeterRegistry meterRegistry,
                                WebFluxWarmupHealthIndicator healthIndicator) {
        this.webClient = webClient;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.healthIndicator = healthIndicator;
    }
    
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Timer.Sample sample = Timer.start(meterRegistry);
        log.info("Iniciando warmup do WebFlux...");
        
        // Marcar início do warmup no health indicator
        healthIndicator.markWarmupStarted();
        
        // Executar warmup sequencial para melhor controle de progresso
        warmupWebClient()
            .doOnSuccess(v -> healthIndicator.updateWarmupProgress("WebClient", true))
            .doOnError(e -> healthIndicator.updateWarmupProgress("WebClient", false))
            .onErrorResume(e -> Mono.empty())
            .then(warmupRedisConnections())
            .doOnSuccess(v -> healthIndicator.updateWarmupProgress("Redis", true))
            .doOnError(e -> healthIndicator.updateWarmupProgress("Redis", false))
            .onErrorResume(e -> Mono.empty())
            .then(warmupNettyEventLoops())
            .doOnSuccess(v -> healthIndicator.updateWarmupProgress("Netty", true))
            .doOnError(e -> healthIndicator.updateWarmupProgress("Netty", false))
            .onErrorResume(e -> Mono.empty())
            .timeout(Duration.ofMinutes(2))
            .doOnSuccess(v -> {
                sample.stop(Timer.builder("webflux.warmup.duration")
                    .description("Tempo total de warmup WebFlux")
                    .register(meterRegistry));
                log.info("Warmup WebFlux completado com sucesso");
            })
            .doOnError(error -> {
                log.error("Warmup WebFlux falhou mas aplicação continuará", error);
                healthIndicator.markWarmupFailed(error.getMessage());
                meterRegistry.counter("webflux.warmup.errors",
                    "error.type", error.getClass().getSimpleName())
                    .increment();
            })
            .subscribe();
    }
    
    /**
     * Aquece o WebClient realizando requisições internas para estabelecer
     * connection pools e compilação JIT SEM chamadas externas
     */
    private Mono<Void> warmupWebClient() {
        log.debug("Iniciando warmup do WebClient (apenas endpoints internos)...");
        
        return Mono.fromRunnable(() -> {
            // Usar APENAS endpoints internos da própria aplicação
            List<String> internalEndpoints = List.of(
                "http://localhost:8080/actuator/health",
                "http://localhost:8080/actuator/info",
                "http://localhost:8080/actuator/metrics"
            );
            
            // Realizar múltiplas requisições paralelas para cada endpoint interno
            List<Mono<String>> warmupRequests = internalEndpoints.stream()
                .flatMap(endpoint -> IntStream.range(0, 3) // 3 requisições por endpoint
                    .mapToObj(i -> performInternalWarmupRequest(endpoint, i)))
                .collect(Collectors.toList());
            
            // Executar todas as requisições em paralelo
            Flux.merge(warmupRequests)
                .parallel(3) // 3 threads paralelas
                .runOn(Schedulers.boundedElastic())
                .sequential()
                .collectList()
                .doOnSuccess(results -> {
                    long successCount = results.stream()
                        .filter(result -> !"ERROR".equals(result))
                        .count();
                    log.debug("WebClient warmup completado: {}/{} requisições internas bem-sucedidas", 
                        successCount, results.size());
                    
                    meterRegistry.counter("webflux.warmup.webclient.requests.total")
                        .increment(results.size());
                    meterRegistry.counter("webflux.warmup.webclient.requests.success")
                        .increment(successCount);
                })
                .doOnError(error -> log.warn("Erro durante warmup do WebClient", error))
                .onErrorResume(error -> Mono.empty())
                .block(Duration.ofSeconds(15));
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
    }
    
    /**
     * Realiza uma requisição de warmup interna (sem chamadas externas)
     */
    private Mono<String> performInternalWarmupRequest(String endpoint, int attempt) {
        return webClient.get()
            .uri(endpoint)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(3))
            .map(response -> "SUCCESS-" + attempt)
            .onErrorReturn("ERROR")
            .doOnNext(result -> log.trace("Warmup request interno para {} (tentativa {}): {}", 
                endpoint, attempt, result));
    }
    
    /**
     * Aquece as conexões Redis realizando operações básicas para estabelecer
     * connection pool e testar performance
     */
    private Mono<Void> warmupRedisConnections() {
        log.debug("Iniciando warmup das conexões Redis...");
        
        return Flux.range(0, 20) // 20 operações de warmup
            .flatMap(i -> performRedisWarmupOperation("warmup:key:" + i, "warmup-value-" + i))
            .collectList()
            .doOnSuccess(results -> {
                long successCount = results.stream()
                    .filter(Boolean::booleanValue)
                    .count();
                log.debug("Redis warmup completado: {}/{} operações bem-sucedidas", 
                    successCount, results.size());
                
                meterRegistry.counter("webflux.warmup.redis.operations.total")
                    .increment(results.size());
                meterRegistry.counter("webflux.warmup.redis.operations.success")
                    .increment(successCount);
            })
            .doOnError(error -> {
                log.warn("Erro durante warmup do Redis", error);
                meterRegistry.counter("webflux.warmup.redis.errors").increment();
            })
            .onErrorResume(error -> Mono.just(List.of())) // Continue mesmo com erro
            .then();
    }
    
    /**
     * Realiza uma operação completa de Redis: SET -> GET -> DELETE
     */
    private Mono<Boolean> performRedisWarmupOperation(String key, String value) {
        return redisTemplate.opsForValue()
            .set(key, value, Duration.ofSeconds(10)) // TTL de 10 segundos
            .then(redisTemplate.opsForValue().get(key))
            .flatMap(retrievedValue -> {
                if (value.equals(retrievedValue)) {
                    return redisTemplate.delete(key).thenReturn(true);
                } else {
                    log.trace("Valor Redis não confere: esperado={}, obtido={}", value, retrievedValue);
                    return Mono.just(false);
                }
            })
            .timeout(Duration.ofSeconds(2))
            .doOnNext(success -> log.trace("Operação Redis para chave {}: {}", key, 
                success ? "SUCESSO" : "FALHA"))
            .onErrorReturn(false);
    }
    
    /**
     * Aquece os Event Loops do Netty criando conexões INTERNAS temporárias e
     * executando tarefas para inicializar threads e buffers
     */
    private Mono<Void> warmupNettyEventLoops() {
        log.debug("Iniciando warmup dos Event Loops Netty (apenas conexões internas)...");
        
        return Mono.fromCallable(() -> {
            try {
                // Criar EventLoopGroup temporário para warmup
                EventLoopGroup warmupEventLoopGroup = new NioEventLoopGroup(4);
                
                // Criar ConnectionProvider personalizado para warmup
                ConnectionProvider warmupConnectionProvider = ConnectionProvider
                    .builder("warmup-pool")
                    .maxConnections(10)
                    .maxIdleTime(Duration.ofSeconds(5))
                    .maxLifeTime(Duration.ofSeconds(10))
                    .pendingAcquireTimeout(Duration.ofSeconds(2))
                    .build();
                
                // Criar HttpClient temporário
                HttpClient warmupHttpClient = HttpClient
                    .create(warmupConnectionProvider)
                    .runOn(warmupEventLoopGroup)
                    .responseTimeout(Duration.ofSeconds(3));
                
                // Realizar múltiplas conexões INTERNAS para aquecer os event loops
                CountDownLatch latch = new CountDownLatch(6);
                
                // Usar apenas endpoints internos da aplicação
                List<String> internalEndpoints = List.of(
                    "http://localhost:8080/actuator/health",
                    "http://localhost:8080/actuator/info"
                );
                
                for (int i = 0; i < 6; i++) {
                    final int attempt = i;
                    String endpoint = internalEndpoints.get(i % internalEndpoints.size());
                    
                    warmupHttpClient
                        .get()
                        .uri(endpoint)
                        .response()
                        .timeout(Duration.ofSeconds(3))
                        .doOnNext(response -> {
                            log.trace("Netty warmup connection {} para {} - Status: {}", 
                                attempt, endpoint, response.status().code());
                            meterRegistry.counter("webflux.warmup.netty.connections.success")
                                .increment();
                        })
                        .doOnError(error -> {
                            log.trace("Netty warmup connection {} falhou: {}", 
                                attempt, error.getMessage());
                            meterRegistry.counter("webflux.warmup.netty.connections.error")
                                .increment();
                        })
                        .doFinally(signal -> latch.countDown())
                        .subscribe();
                }
                
                // Aguardar conclusão com timeout
                boolean completed = latch.await(10, TimeUnit.SECONDS);
                log.debug("Netty warmup: {} conexões internas processadas em 10s", 
                    6 - latch.getCount());
                
                // Limpar recursos temporários
                try {
                    warmupConnectionProvider.dispose();
                    warmupEventLoopGroup.shutdownGracefully(100, 500, TimeUnit.MILLISECONDS)
                        .sync();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug("Interrompido durante cleanup do Netty warmup");
                }
                
                // Aquecer schedulers do Reactor
                warmupReactorSchedulers();
                
                return completed;
                
            } catch (Exception e) {
                log.warn("Erro durante warmup do Netty", e);
                meterRegistry.counter("webflux.warmup.netty.errors").increment();
                return false;
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnNext(success -> log.debug("Netty Event Loops warmup: {}", 
            success ? "SUCESSO" : "PARCIAL"))
        .then();
    }
    
    /**
     * Aquece os schedulers do Reactor executando tarefas em cada tipo
     */
    private void warmupReactorSchedulers() {
        log.trace("Aquecendo schedulers do Reactor...");
        
        // Warmup parallel scheduler (CPU-bound tasks)
        List<Mono<Integer>> parallelTasks = IntStream.range(0, 50)
            .mapToObj(i -> Mono.fromCallable(() -> {
                // Simular trabalho CPU-intensivo
                int result = 0;
                for (int j = 0; j < 1000; j++) {
                    result += Math.sin(i * j) * Math.cos(i * j);
                }
                return result;
            }).subscribeOn(Schedulers.parallel()))
            .collect(Collectors.toList());
        
        // Warmup boundedElastic scheduler (I/O-bound tasks)
        List<Mono<Void>> elasticTasks = IntStream.range(0, 20)
            .mapToObj(i -> Mono.fromCallable(() -> {
                try {
                    // Simular I/O
                    Thread.sleep(5);
                    return null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }).subscribeOn(Schedulers.boundedElastic()).then())
            .collect(Collectors.toList());
        
        // Executar ambos os tipos de task
        try {
            Mono.when(
                Flux.merge(parallelTasks).then(),
                Flux.merge(elasticTasks).then()
            )
            .timeout(Duration.ofSeconds(5))
            .block();
            
            log.trace("Reactor schedulers aquecidos com sucesso");
            meterRegistry.counter("webflux.warmup.schedulers.success").increment();
            
        } catch (Exception e) {
            log.trace("Erro durante warmup dos schedulers: {}", e.getMessage());
            meterRegistry.counter("webflux.warmup.schedulers.error").increment();
        }
    }
}

// =============================================================================
// CONFIGURAÇÃO ADICIONAL PARA WEBFLUX WARMUP
// =============================================================================

@Configuration
@ConditionalOnProperty(name = "app.warmup.webflux.enabled", havingValue = "true", matchIfMissing = true)
class WebFluxWarmupConfiguration {
    
    /**
     * WebClient otimizado para warmup com connection pooling adequado
     */
    @Bean
    @Primary
    public WebClient webClient() {
        ConnectionProvider connectionProvider = ConnectionProvider
            .builder("warmup-ready-pool")
            .maxConnections(100)
            .maxIdleTime(Duration.ofSeconds(30))
            .maxLifeTime(Duration.ofMinutes(5))
            .pendingAcquireTimeout(Duration.ofSeconds(10))
            .evictInBackground(Duration.ofSeconds(60))
            .build();
        
        HttpClient httpClient = HttpClient
            .create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .responseTimeout(Duration.ofSeconds(30))
            .keepAlive(true);
        
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}

// =============================================================================
// PROPERTIES DE CONFIGURAÇÃO
// =============================================================================

@ConfigurationProperties(prefix = "app.warmup.webflux")
@Data
class WebFluxWarmupProperties {
    
    /**
     * Habilitar warmup do WebFlux
     */
    private boolean enabled = true;
    
    /**
     * Timeout total para warmup
     */
    private Duration timeout = Duration.ofMinutes(2);
    
    /**
     * Número de requisições de warmup por endpoint
     */
    private int warmupRequestsPerEndpoint = 3;
    
    /**
     * Número de operações Redis para warmup
     */
    private int redisWarmupOperations = 20;
    
    /**
     * Número de conexões Netty para warmup
     */
    private int nettyWarmupConnections = 8;
    
    /**
     * Endpoints customizados para warmup (opcional)
     */
    private List<String> customWarmupEndpoints = List.of();
}

// =============================================================================
// HEALTH INDICATOR PARA MONITORAMENTO COM CONTROLE DE ESTADO
// =============================================================================

@Component
public class WebFluxWarmupHealthIndicator implements HealthIndicator {
    
    private volatile boolean warmupCompleted = false;
    private volatile String lastWarmupStatus = "NOT_STARTED";
    private volatile Instant lastWarmupTime;
    private final AtomicInteger warmupProgress = new AtomicInteger(0);
    private final int totalWarmupSteps = 3; // WebClient, Redis, Netty
    
    /**
     * Método chamado pelo WebFluxWarmupListener para atualizar o progresso
     */
    public void updateWarmupProgress(String component, boolean success) {
        if (success) {
            int current = warmupProgress.incrementAndGet();
            this.lastWarmupStatus = String.format("IN_PROGRESS (%d/%d) - %s completed", 
                current, totalWarmupSteps, component);
            
            if (current >= totalWarmupSteps) {
                this.warmupCompleted = true;
                this.lastWarmupStatus = "COMPLETED";
                this.lastWarmupTime = Instant.now();
            }
        } else {
            this.lastWarmupStatus = String.format("PARTIAL_FAILURE - %s failed", component);
        }
    }
    
    /**
     * Marca o início do warmup
     */
    public void markWarmupStarted() {
        this.warmupCompleted = false;
        this.lastWarmupStatus = "STARTED";
        this.warmupProgress.set(0);
        this.lastWarmupTime = Instant.now();
    }
    
    /**
     * Marca falha geral do warmup
     */
    public void markWarmupFailed(String reason) {
        this.warmupCompleted = false;
        this.lastWarmupStatus = "FAILED: " + reason;
        this.lastWarmupTime = Instant.now();
    }
    
    @Override
    public Health health() {
        Health.Builder builder = warmupCompleted ? Health.up() : Health.down();
        
        return builder
            .withDetail("warmup.status", lastWarmupStatus)
            .withDetail("warmup.completed", warmupCompleted)
            .withDetail("warmup.progress", String.format("%d/%d", 
                warmupProgress.get(), totalWarmupSteps))
            .withDetail("warmup.lastExecution", lastWarmupTime)
            .withDetail("warmup.components", Map.of(
                "webClient", warmupProgress.get() >= 1 ? "COMPLETED" : "PENDING",
                "redis", warmupProgress.get() >= 2 ? "COMPLETED" : "PENDING", 
                "netty", warmupProgress.get() >= 3 ? "COMPLETED" : "PENDING"
            ))
            .build();
    }
}
```



