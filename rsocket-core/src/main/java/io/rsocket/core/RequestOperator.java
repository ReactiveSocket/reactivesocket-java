package io.rsocket.core;

import io.rsocket.Payload;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CorePublisher;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.publisher.Operators;
import reactor.core.publisher.SignalType;
import reactor.util.context.Context;

/**
 * This is a support class for handling of request input, intended for use with {@link
 * Operators#lift}. It ensures serial execution of cancellation vs first request signals and also
 * provides hooks for separate handling of first vs subsequent {@link Subscription#request}
 * invocations.
 */
abstract class RequestOperator
    implements CoreSubscriber<Payload>,
        CorePublisher<Payload>,
        Fuseable.QueueSubscription<Payload>,
        Fuseable {

  final CorePublisher<Payload> source;
  final String errorMessageOnSecondSubscription;

  CoreSubscriber<? super Payload> actual;

  Subscription s;
  Fuseable.QueueSubscription<Payload> qs;

  int streamId;
  boolean firstRequest = true;

  volatile int wip;
  static final AtomicIntegerFieldUpdater<RequestOperator> WIP =
      AtomicIntegerFieldUpdater.newUpdater(RequestOperator.class, "wip");

  RequestOperator(CorePublisher<Payload> source, String errorMessageOnSecondSubscription) {
    this.source = source;
    this.errorMessageOnSecondSubscription = errorMessageOnSecondSubscription;
    WIP.lazySet(this, -1);
  }

  @Override
  public void subscribe(Subscriber<? super Payload> actual) {
    subscribe(Operators.toCoreSubscriber(actual));
  }

  @Override
  public void subscribe(CoreSubscriber<? super Payload> actual) {
    if (this.wip == -1 && WIP.compareAndSet(this, -1, 0)) {
      this.actual = actual;
      source.subscribe(this);
      actual.onSubscribe(this);
    } else {
      Operators.error(actual, new IllegalStateException(this.errorMessageOnSecondSubscription));
    }
  }

  /**
   * Optional hook executed exactly once on the first {@link Subscription#request) invocation
   * and right after the {@link Subscription#request} was propagated to the upstream subscription.
   *
   * <p><b>Note</b>: this hook may not be invoked if cancellation happened before this invocation
   */
  void hookOnFirstRequest(long n) {}

  /**
   * Optional hook executed after the {@link Subscription#request} was propagated to the upstream
   * subscription and excludes the first {@link Subscription#request} invocation.
   */
  void hookOnRemainingRequests(long n) {}

  /** Optional hook executed after this {@link Subscription} cancelling. */
  void hookOnCancel() {}

  /**
   * Optional hook executed after {@link org.reactivestreams.Subscriber} termination events
   * (onError, onComplete).
   *
   * @param signalType the type of termination event that triggered the hook ({@link
   *     SignalType#ON_ERROR} or {@link SignalType#ON_COMPLETE})
   */
  void hookOnTerminal(SignalType signalType) {}

  @Override
  public Context currentContext() {
    return actual.currentContext();
  }

  @Override
  public void request(long n) {
    this.s.request(n);
    if (!firstRequest) {
      try {
        this.hookOnRemainingRequests(n);
      } catch (Throwable throwable) {
        onError(throwable);
      }
      return;
    }

    if (WIP.getAndIncrement(this) != 0) {
      return;
    }

    this.firstRequest = false;
    int missed = 1;

    boolean firstLoop = true;
    for (; ; ) {
      if (firstLoop) {
        firstLoop = false;
        try {
          this.hookOnFirstRequest(n);
        } catch (Throwable throwable) {
          onError(throwable);
          return;
        }
      } else {
        try {
          this.hookOnCancel();
        } catch (Throwable throwable) {
          onError(throwable);
        }
        return;
      }

      missed = WIP.addAndGet(this, -missed);
      if (missed == 0) {
        return;
      }
    }
  }

  @Override
  public void cancel() {
    this.s.cancel();

    if (WIP.getAndIncrement(this) != 0) {
      return;
    }

    hookOnCancel();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void onSubscribe(Subscription s) {
    if (Operators.validate(this.s, s)) {
      this.s = s;
      if (s instanceof Fuseable.QueueSubscription) {
        this.qs = (Fuseable.QueueSubscription<Payload>) s;
      }
    }
  }

  @Override
  public void onNext(Payload t) {
    this.actual.onNext(t);
  }

  @Override
  public void onError(Throwable t) {
    this.actual.onError(t);
    try {
      this.hookOnTerminal(SignalType.ON_ERROR);
    } catch (Throwable throwable) {
      Operators.onErrorDropped(throwable, currentContext());
    }
  }

  @Override
  public void onComplete() {
    this.actual.onComplete();
    try {
      this.hookOnTerminal(SignalType.ON_COMPLETE);
    } catch (Throwable throwable) {
      Operators.onErrorDropped(throwable, currentContext());
    }
  }

  @Override
  public int requestFusion(int requestedMode) {
    if (this.qs != null) {
      return this.qs.requestFusion(requestedMode);
    } else {
      return Fuseable.NONE;
    }
  }

  @Override
  public Payload poll() {
    return this.qs.poll();
  }

  @Override
  public int size() {
    return this.qs.size();
  }

  @Override
  public boolean isEmpty() {
    return this.qs.isEmpty();
  }

  @Override
  public void clear() {
    this.qs.clear();
  }
}
