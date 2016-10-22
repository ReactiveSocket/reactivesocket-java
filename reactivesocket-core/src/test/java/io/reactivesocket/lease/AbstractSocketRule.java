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

package io.reactivesocket.lease;

import io.reactivesocket.AbstractReactiveSocket;
import io.reactivesocket.ReactiveSocket;
import io.reactivesocket.test.util.MockReactiveSocket;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public abstract class AbstractSocketRule<T extends ReactiveSocket> extends ExternalResource {

    private T socket;
    private MockReactiveSocket mockSocket;
    private ReactiveSocket requestHandler;

    @Override
    public Statement apply(final Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                init();
                base.evaluate();
            }
        };
    }

    protected void init() {
        if (null == requestHandler) {
            requestHandler = new AbstractReactiveSocket() { };
        }
        mockSocket = new MockReactiveSocket(requestHandler);
        socket = newSocket(mockSocket);
    }

    public MockReactiveSocket getMockSocket() {
        return mockSocket;
    }

    public T getReactiveSocket() {
        return socket;
    }

    protected abstract T newSocket(ReactiveSocket delegate);

    protected void setRequestHandler(ReactiveSocket socket) {
        requestHandler = socket;
    }
}
