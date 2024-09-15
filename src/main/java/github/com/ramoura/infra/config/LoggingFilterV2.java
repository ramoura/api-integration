package github.com.ramoura.infra.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.List;


@Component
public class LoggingFilterV2 implements WebFilter {

    private static final Logger logger = LoggerFactory.getLogger(LoggingFilterV2.class);
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_ID_KEY = "traceId";
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
//        https://github.com/piomin/spring-boot-logging
        // Capture the request body
        final long startTime = System.currentTimeMillis();
        final String requestId = "requestId-1";
        ServerWebExchangeDecorator exchangeDecorator = new ServerWebExchangeDecorator(exchange) {

            @Override
            public ServerHttpRequest getRequest() {
                return new RequestLoggingInterceptor(super.getRequest(), true, requestId);
            }

            @Override
            public ServerHttpResponse getResponse() {
                return new ResponseLoggingInterceptor(super.getResponse(), startTime, true, requestId);
            }
        };
        return chain.filter(exchangeDecorator)
            .contextWrite(Context.of(MDC_TRACE_ID_KEY, requestId))
            .doOnSuccess(aVoid -> {
                logResponse(startTime, exchangeDecorator.getResponse(), exchangeDecorator.getResponse().getStatusCode().value(), requestId);
            })
            .doOnError(throwable -> {
                logResponse(startTime, exchangeDecorator.getResponse(), 500, requestId);
            });
    }
    private void logResponse(long startTime, ServerHttpResponse response, int overriddenStatus, String requestId) {
        final long duration = System.currentTimeMillis() - startTime;
        List<String> header = response.getHeaders().get("Content-Length");
        if (true && (header == null || header.get(0).equals("0"))) {
            if (true)
                logger.info("xResponse({} ms): id={}, status={}, headers={}", duration, requestId,
                    overriddenStatus, response.getHeaders());
            else
                logger.info("xResponse({} ms): id={}, status={}", duration, requestId,
                    overriddenStatus);
        }
    }
}
