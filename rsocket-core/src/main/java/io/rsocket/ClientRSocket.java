/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket;

import io.netty.buffer.Unpooled;
import io.netty.util.collection.IntObjectHashMap;
import io.rsocket.client.KeepAliveProvider;
import io.rsocket.exceptions.Exceptions;
import io.rsocket.internal.KnownErrorFilter;
import io.rsocket.internal.LimitableRequestPublisher;
import io.rsocket.lease.Lease;
import io.rsocket.lease.LeaseImpl;
import io.rsocket.util.PayloadImpl;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.publisher.UnicastProcessor;

import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Client Side of a RSocket socket. Sends {@link Frame}s
 * to a {@link ServerRSocket}
 */
public class ClientRSocket implements RSocket {

    private static final ClosedChannelException CLOSED_CHANNEL_EXCEPTION = new ClosedChannelException();

    private final DuplexConnection connection;
    private final Consumer<Throwable> errorConsumer;
    private final StreamIdSupplier streamIdSupplier;
    private final KeepAliveProvider keepAliveProvider;
    private final MonoProcessor<Void> started;
    private final IntObjectHashMap<LimitableRequestPublisher> senders;
    private final IntObjectHashMap<Subscriber<Payload>> receivers;

    private Disposable keepAliveSendSub;
    private volatile Consumer<Lease> leaseConsumer; // Provided on start()

    public ClientRSocket(DuplexConnection connection, Consumer<Throwable> errorConsumer,
                                StreamIdSupplier streamIdSupplier, KeepAliveProvider keepAliveProvider) {
        this.connection = connection;
        this.errorConsumer = new KnownErrorFilter(errorConsumer);
        this.streamIdSupplier = streamIdSupplier;
        this.keepAliveProvider = keepAliveProvider;
        this.started = MonoProcessor.create();
        this.senders = new IntObjectHashMap<>(256, 0.9f);
        this.receivers = new IntObjectHashMap<>(256, 0.9f);

        connection
            .onClose()
            .doFinally(signalType -> cleanup())
            .doOnError(errorConsumer::accept)
            .subscribe();
    }

    @Override
    public Mono<Void> fireAndForget(Payload payload) {
        Mono<Void> defer = Mono.defer(() -> {
            final int streamId = streamIdSupplier.nextStreamId();
            final Frame requestFrame = Frame.Request.from(streamId, FrameType.FIRE_AND_FORGET, payload, 1);
            return connection.sendOne(requestFrame);
        });

        return started.then(defer);
    }

    @Override
    public Mono<Payload> requestResponse(Payload payload) {
        return handleRequestResponse(payload);
    }

    @Override
    public Flux<Payload> requestStream(Payload payload) {
        return handleStreamResponse(Flux.just(payload), FrameType.REQUEST_STREAM);
    }

    @Override
    public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
        return handleStreamResponse(Flux.from(payloads), FrameType.REQUEST_CHANNEL);
    }

    @Override
    public Mono<Void> metadataPush(Payload payload) {
        final Frame requestFrame = Frame.Request.from(0, FrameType.METADATA_PUSH, payload, 1);
        return connection.sendOne(requestFrame);
    }

    @Override
    public double availability() {
        return connection.availability();
    }

    @Override
    public Mono<Void> close() {
        return connection.close();
    }

    @Override
    public Mono<Void> onClose() {
        return connection.onClose();
    }

    public ClientRSocket start(Consumer<Lease> leaseConsumer) {
        this.leaseConsumer = leaseConsumer;

        keepAliveSendSub = connection.send(keepAliveProvider.ticks()
            .map(i -> Frame.Keepalive.from(Unpooled.EMPTY_BUFFER, true)))
            .subscribe(null, errorConsumer);

        connection
            .receive()
            .doOnSubscribe(subscription -> started.onComplete())
            .doOnNext(this::handleIncomingFrames)
            .doOnError(errorConsumer)
            .subscribe();

        return this;
    }

    private Mono<Payload> handleRequestResponse(final Payload payload) {
        return started.then(() -> {
            int streamId = streamIdSupplier.nextStreamId();
            final Frame requestFrame = Frame.Request.from(streamId, FrameType.REQUEST_RESPONSE, payload, 1);

            MonoProcessor<Payload> receiver = MonoProcessor.create();

            synchronized (this) {
                receivers.put(streamId, receiver);
            }

            MonoProcessor<Void> subscribedRequest =
                connection.sendOne(requestFrame)
                .doOnError(t -> {
                    errorConsumer.accept(t);
                    receiver.cancel();
                })
                .subscribe();

            return receiver
                .doOnError(t -> {
                    if (contains(streamId) && connection.availability() > 0.0 && !receiver.isTerminated()) {
                        connection
                            .sendOne(Frame.Error.from(streamId, t))
                            .doOnError(errorConsumer::accept)
                            .subscribe();
                    }
                })
                .doOnCancel(() -> {
                    if (contains(streamId) && connection.availability() > 0.0 && !receiver.isTerminated()) {
                        connection
                            .sendOne(Frame.Cancel.from(streamId))
                            .doOnError(errorConsumer::accept)
                            .subscribe();
                    }
                    subscribedRequest.cancel();
                })
                .doFinally(s ->
                    removeReceiver(streamId)
                );
        });
    }

    private Flux<Payload> handleStreamResponse(Flux<Payload> request, FrameType requestType) {
        return started.thenMany(new Supplier<Publisher<Payload>>() {
            final UnicastProcessor<Payload> receiver = UnicastProcessor.create();
            final int streamId = streamIdSupplier.nextStreamId();
            volatile MonoProcessor<Void> subscribedRequests;
            boolean firstRequest = true;

            boolean isValidToSendFrame() {
                return contains(streamId) && connection.availability() > 0.0 && !receiver.isTerminated();
            }

            void sendOneFrame(Frame frame) {
                if (isValidToSendFrame()) {
                    connection
                        .sendOne(frame)
                        .doOnError(errorConsumer::accept)
                        .subscribe();
                }
            }

            @Override
            public Publisher<Payload> get() {
                return receiver
                    .doOnRequest(l -> {
                        boolean _firstRequest = false;
                        synchronized (ClientRSocket.this) {
                            if (firstRequest) {
                                _firstRequest = true;
                                firstRequest = false;
                            }
                        }

                        if (_firstRequest) {
                            Flux<Frame> requestFrames =
                                request
                                    .transform(f -> {
                                        LimitableRequestPublisher<Payload> wrapped = LimitableRequestPublisher.wrap(f);
                                        synchronized (ClientRSocket.this) {
                                            senders.put(streamId, wrapped);
                                            receivers.put(streamId, receiver);
                                        }

                                        return wrapped;
                                    })
                                    .map(new Function<Payload, Frame>() {
                                        boolean firstPayload = true;

                                        @Override
                                        public Frame apply(Payload payload) {
                                            boolean _firstPayload = false;
                                            synchronized (this) {
                                                if (firstPayload) {
                                                    firstPayload = false;
                                                    _firstPayload = true;
                                                }
                                            }

                                            if (_firstPayload) {
                                                return Frame.Request.from(streamId, requestType, payload, l);
                                            } else {
                                                return Frame.PayloadFrame.from(streamId, FrameType.NEXT, payload);
                                            }
                                        }
                                    })
                                    .doOnComplete(() -> {
                                        if (FrameType.REQUEST_CHANNEL == requestType) {
                                            sendOneFrame(Frame.PayloadFrame.from(streamId, FrameType.COMPLETE));
                                        }
                                    });

                            subscribedRequests = connection
                                .send(requestFrames)
                                .doOnError(t -> {
                                    errorConsumer.accept(t);
                                    receiver.cancel();
                                })
                                .subscribe();
                        } else {
                            sendOneFrame(Frame.RequestN.from(streamId, l));
                        }
                    })
                    .doOnError(t -> sendOneFrame(Frame.Error.from(streamId, t)))
                    .doOnCancel(() -> {
                        sendOneFrame(Frame.Cancel.from(streamId));
                        if (subscribedRequests != null) {
                            subscribedRequests.cancel();
                        }
                    })
                    .doFinally(s -> {
                        removeReceiver(streamId);
                        removeSender(streamId);
                    });
            }
        });
    }

    private boolean contains(int streamId) {
        synchronized (ClientRSocket.this) {
            return receivers.containsKey(streamId);
        }
    }

    protected void cleanup() {
        senders
            .forEach((integer, limitableRequestPublisher) -> cleanUpLimitableRequestPublisher(limitableRequestPublisher));

        receivers
            .forEach((integer, subscriber) -> cleanUpSubscriber(subscriber));

        synchronized (this) {
            senders.clear();
            receivers.clear();
        }

        if (null != keepAliveSendSub) {
            keepAliveSendSub.dispose();
        }
    }

    private synchronized void cleanUpLimitableRequestPublisher(LimitableRequestPublisher<?> limitableRequestPublisher) {
        limitableRequestPublisher.cancel();
    }

    private synchronized void cleanUpSubscriber(Subscriber<?> subscriber) {
        subscriber.onError(CLOSED_CHANNEL_EXCEPTION);
    }

    private void handleIncomingFrames(Frame frame) {
        try {
            int streamId = frame.getStreamId();
            FrameType type = frame.getType();
            if (streamId == 0) {
                handleStreamZero(type, frame);
            } else {
                handleFrame(streamId, type, frame);
            }
        } finally {
            frame.release();
        }
    }

    private void handleStreamZero(FrameType type, Frame frame) {
        switch (type) {
            case ERROR:
                throw Exceptions.from(frame);
            case LEASE: {
                if (leaseConsumer != null) {

                    leaseConsumer.accept(new LeaseImpl(frame));
                }
                break;
            }
            case KEEPALIVE:
                if (!Frame.Keepalive.hasRespondFlag(frame)) {
                    // Respond flag absent => Ack of KeepAlive
                    keepAliveProvider.ack();
                }
                break;
            default:
                // Ignore unknown frames. Throwing an error will close the socket.
                errorConsumer.accept(new IllegalStateException("Client received supported frame on stream 0: "
                    + frame.toString()));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleFrame(int streamId, FrameType type, Frame frame) {
        Subscriber<Payload> receiver;
        synchronized (this) {
            receiver = receivers.get(streamId);
        }
        if (receiver == null) {
            handleMissingResponseProcessor(streamId, type, frame);
        } else {
            switch (type) {
                case ERROR:
                    receiver.onError(Exceptions.from(frame));
                    removeReceiver(streamId);
                    break;
                case NEXT_COMPLETE:
                    receiver.onNext(new PayloadImpl(frame));
                    receiver.onComplete();
                    break;
                case CANCEL: {
                    LimitableRequestPublisher sender;
                    synchronized (this) {
                        sender = senders.remove(streamId);
                        removeReceiver(streamId);
                    }
                    if (sender != null) {
                        sender.cancel();
                    }
                    break;
                }
                case NEXT:
                    receiver.onNext(new PayloadImpl(frame));
                    break;
                case REQUEST_N: {
                    LimitableRequestPublisher sender;
                    synchronized (this) {
                        sender = senders.get(streamId);
                    }
                    if (sender != null) {
                        int n = Frame.RequestN.requestN(frame);
                        sender.increaseRequestLimit(n);
                    }
                    break;
                }
                case COMPLETE:
                    receiver.onComplete();
                    synchronized (this) {
                        receivers.remove(streamId);
                    }
                    break;
                default:
                    throw new IllegalStateException(
                        "Client received supported frame on stream " + streamId + ": " + frame.toString());
            }
        }
    }

    private void handleMissingResponseProcessor(int streamId, FrameType type, Frame frame) {
        if (!streamIdSupplier.isBeforeOrCurrent(streamId)) {
            if (type == FrameType.ERROR) {
                // message for stream that has never existed, we have a problem with
                // the overall connection and must tear down
                String errorMessage = StandardCharsets.UTF_8.decode(frame.getData()).toString();

                throw new IllegalStateException("Client received error for non-existent stream: "
                    + streamId + " Message: " + errorMessage);
            } else {
                throw new IllegalStateException("Client received message for non-existent stream: " + streamId +
                    ", frame type: " + type);
            }
        }
        // receiving a frame after a given stream has been cancelled/completed,
        // so ignore (cancellation is async so there is a race condition)
    }

    private synchronized void removeReceiver(int streamId) {
        receivers.remove(streamId);
    }

    private synchronized void removeSender(int streamId) {
        senders.remove(streamId);
    }
}
