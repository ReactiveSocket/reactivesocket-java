/*
 * Copyright 2015-2020 the original author or authors.
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

import io.rsocket.frame.ErrorType;
import javax.annotation.Nullable;

/**
 * Despite being a valid request, the Responder decided to reject it. The Responder guarantees that
 * it didn't process the request. The reason for the rejection is explained in the Error Data
 * section.
 *
 * @see <a href="https://github.com/rsocket/rsocket/blob/master/Protocol.md#error-codes">Error
 *     Codes</a>
 */
public class RejectedException extends RSocketException implements Retryable {

  private static final long serialVersionUID = 3926231092835143715L;

  /**
   * Constructs a new exception with the specified message.
   *
   * @param message the message
   */
  public RejectedException(String message) {
    this(message, null);
  }

  /**
   * Constructs a new exception with the specified message and cause.
   *
   * @param message the message
   * @param cause the cause of this exception
   */
  public RejectedException(String message, @Nullable Throwable cause) {
    super(ErrorType.REJECTED, message, cause);
  }
}
