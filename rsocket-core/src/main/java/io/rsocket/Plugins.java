package io.rsocket;

import io.rsocket.fragmentation.FragmentationDuplexConnection;
import reactor.core.publisher.Mono;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 *
 */
public class Plugins {

    public interface DuplexConnectionInterceptor extends BiFunction<DuplexConnectionInterceptor.Type, DuplexConnection, DuplexConnection> {
        enum Type { STREAM_ZERO, CLIENT, SERVER, SOURCE }
    }
    public interface RSocketInterceptor extends Function<RSocket, Mono<RSocket>> {}

    public static final DuplexConnectionInterceptor NOOP_DUPLEX_CONNECTION_INTERCEPTOR = (type, connection) -> connection;
    private static final RSocketInterceptor NOOP_INTERCEPTOR = Mono::just;

    public static volatile DuplexConnectionInterceptor DUPLEX_CONNECTION_INTERCEPTOR = NOOP_DUPLEX_CONNECTION_INTERCEPTOR;
    public static volatile RSocketInterceptor CLIENT_REACTIVE_SOCKET_INTERCEPTOR = NOOP_INTERCEPTOR;
    public static volatile RSocketInterceptor SERVER_REACTIVE_SOCKET_INTERCEPTOR = NOOP_INTERCEPTOR;

    private Plugins() {}

    static {
        if (Boolean.getBoolean("io.rsocket.fragmentation.enable")) {
            int mtu = Integer.getInteger("io.rsocket.fragmentation.mtu", 1024);

            if (Plugins.DUPLEX_CONNECTION_INTERCEPTOR == null) {
                Plugins.DUPLEX_CONNECTION_INTERCEPTOR = (type, connection) -> {
                    if (type == Plugins.DuplexConnectionInterceptor.Type.SOURCE) {
                        return new FragmentationDuplexConnection(connection, mtu);
                    } else {
                        return connection;
                    }
                };
            } else {
                Plugins.DuplexConnectionInterceptor original = Plugins.DUPLEX_CONNECTION_INTERCEPTOR;
                Plugins.DUPLEX_CONNECTION_INTERCEPTOR = (type, connection) -> {
                    if (type == Plugins.DuplexConnectionInterceptor.Type.SOURCE) {
                        return original.apply(type, new FragmentationDuplexConnection(connection, mtu));
                    } else {
                        return original.apply(type, connection);
                    }
                };
            }
        }
    }


}
