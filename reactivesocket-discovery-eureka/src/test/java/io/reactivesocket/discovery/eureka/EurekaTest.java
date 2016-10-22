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

package io.reactivesocket.discovery.eureka;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.Builder;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.CacheRefreshedEvent;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaEventListener;
import io.reactivex.Flowable;
import io.reactivex.subscribers.TestSubscriber;
import org.hamcrest.MatcherAssert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Matchers.*;

@RunWith(MockitoJUnitRunner.class)
public class EurekaTest {

    @Mock
    public EurekaClient eurekaClient;

    @Test
    public void testFilterNonUp() throws Exception {
        List<InstanceInfo> instances = new ArrayList<>();
        Mockito.when(eurekaClient.getInstancesByVipAddress(anyString(), anyBoolean()))
               .thenReturn(instances);
        Eureka eureka = new Eureka(eurekaClient);

        final ArgumentCaptor<EurekaEventListener> listenerCaptor = ArgumentCaptor.forClass(EurekaEventListener.class);

        Flowable<Collection<SocketAddress>> src = Flowable.fromPublisher(eureka.subscribeToAsg("vip-1", false));
        TestSubscriber<Collection<SocketAddress>> testSubscriber = new TestSubscriber<>();

        src.subscribe(testSubscriber);

        Mockito.verify(eurekaClient).registerEventListener(listenerCaptor.capture());

        MatcherAssert.assertThat("Unexpected collection received.", testSubscriber.values(),
                                 hasSize(1));

        MatcherAssert.assertThat("Unexpected collection received before cache update.",
                                 testSubscriber.values().get(0),
                                 hasSize(0));

        EurekaEventListener listener = listenerCaptor.getValue();

        instances.add(newInstance(InstanceStatus.UP));

        listener.onEvent(new CacheRefreshedEvent());

        MatcherAssert.assertThat("Unexpected collection received.", testSubscriber.values(),
                                 hasSize(2));

        MatcherAssert.assertThat("Unexpected collection received after cache update.",
                                 testSubscriber.values().get(1),
                                 hasSize(1));

        instances.clear();
        instances.add(newInstance(InstanceStatus.DOWN));

        listener.onEvent(new CacheRefreshedEvent());

        MatcherAssert.assertThat("Unexpected collection received.", testSubscriber.values(),
                                 hasSize(3));

        MatcherAssert.assertThat("Unexpected collection received after cache update.",
                                 testSubscriber.values().get(2),
                                 hasSize(0));
    }

    private static InstanceInfo newInstance(InstanceStatus status) {
        return Builder.newBuilder()
                      .setInstanceId("1")
                      .setAppName("blah")
                      .setIPAddr("127.0.0.1")
                      .setPort(7001)
                      .setStatus(status)
                      .build();
    }
}
