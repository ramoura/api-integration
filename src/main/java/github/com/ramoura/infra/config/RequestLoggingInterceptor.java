package github.com.ramoura.infra.config;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;


public class RequestLoggingInterceptor extends ServerHttpRequestDecorator {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestLoggingInterceptor.class);

    private boolean logHeaders;
    private String requestId;

    public RequestLoggingInterceptor(ServerHttpRequest delegate, boolean logHeaders, String requestId) {
        super(delegate);
        this.logHeaders = logHeaders;
        this.requestId = requestId;
    }

    @Override
    public Flux<DataBuffer> getBody() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        return super.getBody().doOnNext(dataBuffer -> {
            try {
                Channels.newChannel(baos).write(dataBuffer.asByteBuffer().asReadOnlyBuffer());
                String body = IOUtils.toString(baos.toByteArray(), "UTF-8");
                if (logHeaders)
                    LOGGER.info("yRequest: id={}, method={}, uri={}, headers={}, payload={}", requestId, getDelegate().getMethod(),
                            getDelegate().getPath(), getDelegate().getHeaders(), body);
                else
                    LOGGER.info("yRequest: id={}, method={}, uri={}, payload={}", requestId, getDelegate().getMethod(),
                            getDelegate().getPath(), body);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    baos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

}
