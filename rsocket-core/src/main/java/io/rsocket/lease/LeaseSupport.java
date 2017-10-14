package io.rsocket.lease;

import io.rsocket.DuplexConnection;
import io.rsocket.plugins.PluginRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import reactor.core.publisher.Flux;

public class LeaseSupport {
  private final LeaseRSocketRegistry leaseRSocketRegistry = new LeaseRSocketRegistry();
  private final Consumer<Throwable> errorConsumer;
  private Consumer<LeaseControl> leaseControlConsumer;
  private AtomicBoolean leaseControlConsumed = new AtomicBoolean();

  public LeaseSupport(
      Consumer<Throwable> errorConsumer, Consumer<LeaseControl> leaseControlConsumer) {
    this.errorConsumer = errorConsumer;
    this.leaseControlConsumer = leaseControlConsumer;
  }

  public DuplexConnection wrap(PluginRegistry localPlugins, DuplexConnection clientConnection) {
    if (leaseControlConsumed.compareAndSet(false, true)) {
      leaseControlConsumer.accept(new LeaseControl(leaseRSocketRegistry));
    }
    LeaseListenerConnection listenerConnection = new LeaseListenerConnection(clientConnection);
    Flux<Lease> leaseReceivedFlux = listenerConnection.leaseReceived();

    RSocketLeaseSupport rsocketLeaseSupport =
        RSocketLeaseSupport.ofClient(clientConnection, leaseReceivedFlux, errorConsumer);
    localPlugins.addClientPlugin(rsocketLeaseSupport.getRequesterInterceptor());
    localPlugins.addServerPlugin(rsocketLeaseSupport.getResponderInterceptor());
    localPlugins.addServerPlugin(
        rsocket -> {
          leaseRSocketRegistry.addLeaseRSocket(
              rsocketLeaseSupport.responderRefFactory().apply(rsocket));
          return rsocket;
        });
    return listenerConnection;
  }
}
