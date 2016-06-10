/**
 * Copyright 2015 Netflix, Inc.
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
package io.reactivesocket.internal;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.reactivesocket.Frame;
import io.reactivesocket.FrameType;
import io.reactivesocket.Payload;
import io.reactivesocket.internal.frame.PayloadFragmenter;

public class FragmentedPublisher implements Publisher<Frame> {

	private final PayloadFragmenter fragmenter = new PayloadFragmenter(Frame.METADATA_MTU, Frame.DATA_MTU);
	private final Publisher<Payload> responsePublisher;
	private final int streamId;
	private final FrameType type;

	public FragmentedPublisher(FrameType type, int streamId, Publisher<Payload> responsePublisher) {
		this.type = type;
		this.streamId = streamId;
		this.responsePublisher = responsePublisher;
	}
	
	@Override
	public void subscribe(Subscriber<? super Frame> child) {
		child.onSubscribe(new Subscription() {

			@Override
			public void request(long n) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void cancel() {
				// TODO Auto-generated method stub
				
			}});
	}

}
