package github.com.ramoura.infra.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Hooks;


@Component
public class ReactorMDCInitializer {

    @PostConstruct
    public void setupHooks() {
        Hooks.onEachOperator(
            reactor.core.publisher.Operators.lift(
                (scannable, coreSubscriber) -> new MdcContextLifter<>(coreSubscriber)
            )
        );
    }

    static class MdcContextLifter<T> implements reactor.core.CoreSubscriber<T> {
        private final reactor.core.CoreSubscriber<? super T> coreSubscriber;

        MdcContextLifter(reactor.core.CoreSubscriber<? super T> coreSubscriber) {
            this.coreSubscriber = coreSubscriber;
        }

        @Override
        public void onSubscribe(org.reactivestreams.Subscription subscription) {
            coreSubscriber.onSubscribe(subscription);
        }

        @Override
        public void onNext(T t) {
            coreSubscriber.onNext(t);
        }

        @Override
        public void onError(Throwable throwable) {
            coreSubscriber.onError(throwable);
        }

        @Override
        public void onComplete() {
            coreSubscriber.onComplete();
        }

        @Override
        public reactor.util.context.Context currentContext() {
            reactor.util.context.Context context = coreSubscriber.currentContext();
            if (context.hasKey("traceId")) {
                MDC.put("traceId", context.get("traceId"));
            }
            return context;
        }
    }
}
