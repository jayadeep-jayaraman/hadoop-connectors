/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.util;

import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.pow;
import static org.junit.Assert.assertThrows;

import com.google.api.client.testing.util.MockSleeper;
import com.google.api.client.util.BackOff;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ResilientOperation}. */
@RunWith(JUnit4.class)
public class ResilientOperationTest {
  @Test
  public void testValidCallHasNoRetries() throws Exception {
    MockSleeper sleeper = new MockSleeper();
    CallableTester callTester = new CallableTester(new ArrayList<>());
    BackOff backoff = new RetryBoundedBackOff(new BackOffTester(), 3);
    ResilientOperation.retry(
        callTester, backoff, RetryDeterminer.DEFAULT, Exception.class, sleeper);
    assertThat(callTester.timesCalled()).isEqualTo(1);
    assertThat(sleeper.getCount()).isEqualTo(0);
  }

  @Test
  public void testCallFailsOnBadException() throws Exception {
    MockSleeper sleeper = new MockSleeper();
    ArrayList<Exception> exceptions = new ArrayList<>();
    exceptions.add(new IllegalArgumentException("FakeException"));
    CallableTester callTester = new CallableTester(exceptions);
    BackOff backoff = new RetryBoundedBackOff(new BackOffTester(), 3);

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ResilientOperation.retry(
                    callTester, backoff, RetryDeterminer.DEFAULT, Exception.class, sleeper));
    assertThat(thrown).hasMessageThat().contains("FakeException");

    assertThat(callTester.timesCalled()).isEqualTo(1);
    verifySleeper(sleeper, 0);
  }

  @Test
  public void testCallRetriesAndFails() throws Exception {
    MockSleeper sleeper = new MockSleeper();
    ArrayList<Exception> exceptions = new ArrayList<>();
    exceptions.add(new SocketTimeoutException("socket"));
    exceptions.add(new SocketTimeoutException("socket"));
    exceptions.add(new IllegalArgumentException("FakeException"));
    CallableTester callTester = new CallableTester(exceptions);
    BackOff backoff = new RetryBoundedBackOff(new BackOffTester(), 5);

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ResilientOperation.retry(
                    callTester, backoff, RetryDeterminer.DEFAULT, Exception.class, sleeper));
    assertThat(thrown).hasMessageThat().contains("FakeException");

    assertThat(callTester.timesCalled()).isEqualTo(3);
    verifySleeper(sleeper, 2);
  }

  @Test
  public void testCallRetriesAndFailsWithSocketErrors() throws Exception {
    MockSleeper sleeper = new MockSleeper();
    ArrayList<Exception> exceptions = new ArrayList<>();
    exceptions.add(new SocketTimeoutException("socket"));
    exceptions.add(new SocketTimeoutException("socket"));
    exceptions.add(new IOException("FakeException"));
    CallableTester callTester = new CallableTester(exceptions);
    BackOff backoff = new RetryBoundedBackOff(new BackOffTester(), 5);

    IOException thrown =
        assertThrows(
            IOException.class,
            () ->
                ResilientOperation.retry(
                    callTester,
                    backoff,
                    RetryDeterminer.SOCKET_ERRORS,
                    IOException.class,
                    sleeper));
    assertThat(thrown).hasMessageThat().contains("FakeException");

    assertThat(callTester.timesCalled()).isEqualTo(3);
    verifySleeper(sleeper, 2);
  }

  public void verifySleeper(MockSleeper sleeper, int retry) {
    assertThat(retry).isEqualTo(sleeper.getCount());
    if (retry == 0) {
      return;
    }
    assertThat((long) pow(2, retry)).isEqualTo(sleeper.getLastMillis());
  }

  @Test
  public void testCallMaxRetries() throws Exception {
    MockSleeper sleeper = new MockSleeper();
    ArrayList<Exception> exceptions = new ArrayList<>();
    exceptions.add(new SocketTimeoutException("socket"));
    exceptions.add(new SocketTimeoutException("socket2"));
    exceptions.add(new SocketTimeoutException("socket3"));
    CallableTester callTester = new CallableTester(exceptions);
    BackOff backoff = new RetryBoundedBackOff(new BackOffTester(), 2);

    SocketTimeoutException thrown =
        assertThrows(
            SocketTimeoutException.class,
            () ->
                ResilientOperation.retry(
                    callTester, backoff, RetryDeterminer.DEFAULT, Exception.class, sleeper));
    assertThat(thrown).hasMessageThat().contains("socket3");

    assertThat(callTester.timesCalled()).isEqualTo(3);
    verifySleeper(sleeper, 2);
  }

  @Test
  public void testCallRetriesAndSucceeds() throws Exception {
    MockSleeper sleeper = new MockSleeper();
    ArrayList<Exception> exceptions = new ArrayList<>();
    exceptions.add(new SocketTimeoutException("socket"));
    exceptions.add(new SocketTimeoutException("socket2"));
    exceptions.add(new SocketTimeoutException("socket3"));
    CallableTester callTester = new CallableTester(exceptions);
    BackOff backoff = new RetryBoundedBackOff(new BackOffTester(), 3);
    assertThat(
            ResilientOperation.retry(
                callTester, backoff, RetryDeterminer.DEFAULT, Exception.class, sleeper))
        .isEqualTo(3);
    assertThat(callTester.timesCalled()).isEqualTo(4);
    verifySleeper(sleeper, 3);
  }

  private static class CallableTester implements Callable<Integer> {
    int called = 0;
    ArrayList<Exception> exceptions;

    public CallableTester(ArrayList<Exception> exceptions) {
      this.exceptions = exceptions;
    }

    @Override
    public Integer call() throws Exception {
      if (called < exceptions.size()) {
        throw exceptions.get(called++);
      }
      return called++;
    }

    public int timesCalled() {
      return called;
    }
  }

  private static class BackOffTester implements BackOff {
    int counter = 1;

    @Override
    public void reset() {
      counter = 1;
    }

    @Override
    public long nextBackOffMillis() {
      counter *= 2;
      return counter;
    }
  }
}
