package io.rsocket.examples.transport.tcp.lease.advanced.invertmulticlient;

import com.netflix.concurrency.limits.limit.VegasLimit;
import io.rsocket.RSocket;
import io.rsocket.SocketAcceptor;
import io.rsocket.core.RSocketConnector;
import io.rsocket.examples.transport.tcp.lease.advanced.common.LeaseManager;
import io.rsocket.examples.transport.tcp.lease.advanced.common.LimitBasedLeaseSender;
import io.rsocket.examples.transport.tcp.lease.advanced.controller.TasksHandlingRSocket;
import io.rsocket.transport.netty.client.TcpClientTransport;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class RespondingClient {
  private static final Logger logger = LoggerFactory.getLogger(RespondingClient.class);

  public static final int PROCESSING_TASK_TIME = 500;
  public static final int CONCURRENT_WORKERS_COUNT = 1;
  public static final int QUEUE_CAPACITY = 50;

  public static void main(String[] args) {
    // Queue for incoming messages represented as Flux
    // Imagine that every fireAndForget that is pushed is processed by a worker
    BlockingQueue<Runnable> tasksQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    ThreadPoolExecutor threadPoolExecutor =
        new ThreadPoolExecutor(1, CONCURRENT_WORKERS_COUNT, 1, TimeUnit.MINUTES, tasksQueue);

    Scheduler workScheduler = Schedulers.fromExecutorService(threadPoolExecutor);

    LeaseManager periodicLeaseSender =
        new LeaseManager(CONCURRENT_WORKERS_COUNT, PROCESSING_TASK_TIME);

    Disposable.Composite disposable = Disposables.composite();
    RSocket clientRSocket =
        RSocketConnector.create()
            .acceptor(
                SocketAcceptor.with(
                    new TasksHandlingRSocket(disposable, workScheduler, PROCESSING_TASK_TIME)))
            .lease(
                (config) ->
                    config.sender(
                        new LimitBasedLeaseSender(
                            UUID.randomUUID().toString(),
                            periodicLeaseSender,
                            VegasLimit.newBuilder()
                                .initialLimit(CONCURRENT_WORKERS_COUNT)
                                .maxConcurrency(QUEUE_CAPACITY)
                                .build())))
            .connect(TcpClientTransport.create("localhost", 7000))
            .block();

    Objects.requireNonNull(clientRSocket);
    disposable.add(clientRSocket);
    clientRSocket.onClose().block();
  }
}
