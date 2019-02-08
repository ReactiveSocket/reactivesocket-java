/*
 * Copyright 2015-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.exceptions;

import io.netty.buffer.ByteBuf;
import io.rsocket.frame.ErrorFrameFlyweight;

import java.util.Objects;

import static io.rsocket.frame.ErrorFrameFlyweight.*;

/** Utility class that generates an exception from a frame. */
public final class Exceptions {

  private Exceptions() {}

  /**
   * Create a {@link RSocketException} from a Frame that matches the error code it contains.
   *
   * @param frame the frame to retrieve the error code and message from
   * @return a {@link RSocketException} that matches the error code in the Frame
   * @throws NullPointerException if {@code frame} is {@code null}
   */
  public static RuntimeException from(ByteBuf frame) {
    Objects.requireNonNull(frame, "frame must not be null");

    int errorCode = ErrorFrameFlyweight.errorCode(frame);
    String message = ErrorFrameFlyweight.dataUtf8(frame);

    switch (errorCode) {
      case APPLICATION_ERROR:
        return new ApplicationErrorException(message);
      case CANCELED:
        return new CanceledException(message);
      case CONNECTION_CLOSE:
        return new ConnectionCloseException(message);
      case CONNECTION_ERROR:
        return new ConnectionErrorException(message);
      case INVALID:
        return new InvalidException(message);
      case INVALID_SETUP:
        return new InvalidSetupException(message);
      case REJECTED:
        return new RejectedException(message);
      case REJECTED_RESUME:
        return new RejectedResumeException(message);
      case REJECTED_SETUP:
        return new RejectedSetupException(message);
      case UNSUPPORTED_SETUP:
        return new UnsupportedSetupException(message);
      default:
        return new IllegalArgumentException(
            String.format("Invalid Error frame: %d '%s'", errorCode, message));
    }
  }
}
