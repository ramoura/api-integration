package github.com.ramoura.infra.config;

import org.apache.commons.io.IOUtils;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;


//@Component
public class LoggingFilter implements WebFilter {

    private static final Logger logger = LoggerFactory.getLogger(LoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
//        https://github.com/piomin/spring-boot-logging
        // Capture the request body
        ServerHttpRequest request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return super.getBody().doOnNext(buffer -> {
                    DataBufferUtils.join(Flux.just(buffer))
                        .map(dataBuffer -> {
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            DataBufferUtils.release(dataBuffer);
                            return new String(bytes, StandardCharsets.UTF_8);
                        })
                        .subscribe(body -> logger.info("Request Body: {}", body));
                });
            }
        };

        // Capture the response body
        ServerHttpResponse response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                Flux<DataBuffer> buffer = Flux.from(body);
                return super.writeWith(buffer.doOnNext(dataBuffer -> {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try {
                        Channels.newChannel(baos).write(dataBuffer.asByteBuffer().asReadOnlyBuffer());
                        String bodyRes = IOUtils.toString(baos.toByteArray(), "UTF-8");
                        logger.info("Response Body: {}", bodyRes);
                        Object requestId = "id-1";
                        long startTime = System.currentTimeMillis();
                        logger.info("Response({} ms): id={}, status={}, headers={}, payload={}", System.currentTimeMillis() - startTime, requestId,
                            getStatusCode().value(), getDelegate().getHeaders(), bodyRes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        try {
                            baos.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }));
            }
        };

        // Continue processing the request and response
        return chain.filter(exchange.mutate().request(request).response(response).build());
    }
}
