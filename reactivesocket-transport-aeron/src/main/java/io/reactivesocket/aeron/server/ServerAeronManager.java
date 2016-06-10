/**
 * Copyright 2015 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reactivesocket.aeron.server;

import io.aeron.Aeron;
import io.aeron.AvailableImageHandler;
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.UnavailableImageHandler;
import io.reactivesocket.aeron.internal.Constants;
import io.reactivesocket.aeron.internal.Loggable;
import org.agrona.TimerWheel;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import rx.Observable;
import rx.Scheduler;
import rx.Single;
import rx.functions.Action0;
import rx.functions.Func0;
import rx.schedulers.Schedulers;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.reactivesocket.aeron.internal.Constants.SERVER_IDLE_STRATEGY;

/**
 * Class that manages the Aeron instance and the server's polling thread. Lets you register more
 * than one NewImageHandler to Aeron after the it's the Aeron instance has started
 */
public class ServerAeronManager implements Loggable {
    private static final ServerAeronManager INSTANCE = new ServerAeronManager();

    private final Aeron aeron;

    private final CopyOnWriteArrayList<AvailableImageHandler> availableImageHandlers = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<UnavailableImageHandler> unavailableImageHandlers = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<FragmentAssemblerHolder> fragmentAssemblerHolders = new CopyOnWriteArrayList<>();

    private final ManyToOneConcurrentArrayQueue<Action0> actions = new ManyToOneConcurrentArrayQueue<>(1024);

    private final TimerWheel timerWheel;

    private final Thread dutyThread;

    private ServerAeronManager() {
        final Aeron.Context ctx = new Aeron.Context();
        ctx.availableImageHandler(this::availableImageHandler);
        ctx.unavailableImageHandler(this::unavailableImage);
        ctx.errorHandler(t -> error("an exception occurred", t));

        aeron = Aeron.connect(ctx);

        this.timerWheel = new TimerWheel(Constants.SERVER_TIMER_WHEEL_TICK_DURATION_MS, TimeUnit.MILLISECONDS, Constants.SERVER_TIMER_WHEEL_BUCKETS);

        dutyThread = new Thread(() -> {
            for (; ; ) {
                try {
                    int poll = 0;
                    for (FragmentAssemblerHolder sh : fragmentAssemblerHolders) {
                        try {
                            if (sh.subscription.isClosed()) {
                                continue;
                            }

                            poll += sh.subscription.poll(sh.fragmentAssembler, Integer.MAX_VALUE);
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }

                    poll += actions.drain(Action0::call);

                    if (timerWheel.computeDelayInMs() < 0) {
                        poll += timerWheel.expireTimers();
                    }

                    SERVER_IDLE_STRATEGY.idle(poll);

                } catch (Throwable t) {
                    t.printStackTrace();
                }

            }

        });
        dutyThread.setName("reactive-socket-aeron-server");
        dutyThread.setDaemon(true);
        dutyThread.start();
    }

    public static ServerAeronManager getInstance() {
        return INSTANCE;
    }

    public void addAvailableImageHander(AvailableImageHandler handler) {
        availableImageHandlers.add(handler);
    }

    public void addUnavailableImageHandler(UnavailableImageHandler handler) {
        unavailableImageHandlers.add(handler);
    }

    public void addSubscription(Subscription subscription, FragmentAssembler fragmentAssembler) {
        debug("Adding subscription with session id {}", subscription.streamId());
        fragmentAssemblerHolders.add(new FragmentAssemblerHolder(subscription, fragmentAssembler));
    }

    public void removeSubscription(Subscription subscription) {
        debug("Removing subscription with session id {}", subscription.streamId());
        fragmentAssemblerHolders.removeIf(s -> s.subscription == subscription);
    }

    private void availableImageHandler(Image image) {
        availableImageHandlers
                .forEach(handler -> handler.onAvailableImage(image));
    }

    private void unavailableImage(Image image) {
        unavailableImageHandlers
                .forEach(handler -> handler.onUnavailableImage(image));
    }

    public Aeron getAeron() {
        return aeron;
    }

    public TimerWheel getTimerWheel() {
        return timerWheel;
    }

    /**
     * Submits an Action0 to be run but the duty thread.
     * @param action the action to be executed
     * @return true if it was successfully submitted
     */
    public boolean submitAction(Action0 action) {
        boolean submitted = true;
        Thread currentThread = Thread.currentThread();
        if (currentThread.equals(dutyThread)) {
            action.call();
        } else {
            submitted = actions.offer(action);
        }

        return submitted;
    }

    /**
     * Submits a task that is implemeted as a {@link Func0} that runs on the
     * server polling thread and returns an {@link Single}
     * @param task task to the run
     * @param <R> expected return type
     * @return an {@link Single} of type R
     */
    public <R> Single<R> submitTask(Func0<R> task) {
        return Single.create(s ->
            submitAction(() -> {
                try {
                    s.onSuccess(task.call());
                } catch (Throwable t) {
                    s.onError(t);
                }
            })
        );
    }

    /**
     *
     * @param tasks
     * @param <R>
     * @return
     */
    public <R> Observable<R> submitTasks(Observable<Func0<R>> tasks) {
        return submitTasks(tasks, Schedulers.computation());
    }

    /**
     * Submits an observable of tasks to be run on a specific scheduler
     * @param tasks
     * @param scheduler
     * @param <R>
     * @return
     */
    public <R> Observable<R> submitTasks(Observable<Func0<R>> tasks, Scheduler scheduler) {
        return tasks
            .observeOn(scheduler, true)
            .concatMap(task -> submitTask(task).toObservable());
    }

    /**
     * Schedules timeout on the TimerWheel in a thread-safe manner
     * @param delayTime
     * @param unit
     * @param action
     * @return true if it was successfully scheduled, otherwise false.
     */
    public boolean threadSafeTimeout(long delayTime, TimeUnit unit, Action0 action) {
        boolean scheduled = true;
        Thread currentThread = Thread.currentThread();
        if (currentThread.equals(dutyThread)) {
            timerWheel.newTimeout(delayTime, unit, action::call);
        } else {
            scheduled = actions.offer(() -> timerWheel.newTimeout(delayTime, unit, action::call));
        }

        return scheduled;
    }

    private class FragmentAssemblerHolder {
        private Subscription subscription;
        private FragmentAssembler fragmentAssembler;

        public FragmentAssemblerHolder(Subscription subscription, FragmentAssembler fragmentAssembler) {
            this.subscription = subscription;
            this.fragmentAssembler = fragmentAssembler;
        }
    }
}
