/*
 * Copyright 2018 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.bigtable;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

import com.google.bigtable.v2.Mutation;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.TestStream;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TimestampedValue;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BigtableBulkWriterTest {

  @Rule
  public final TestPipeline p = TestPipeline.create();

  private Duration flushInterval;
  private static int numOfShard = 1;

  private static final Instant baseTime = new Instant(0);

  private Coder<KV<ByteString, Iterable<Mutation>>> bigtableCoder;
  private static final TypeDescriptor<KV<ByteString, Iterable<Mutation>>> BIGTABLE_WRITE_TYPE =
      new TypeDescriptor<KV<ByteString, Iterable<Mutation>>>() {};


  @Before
  public void setup() throws Exception {
    bigtableCoder = p.getCoderRegistry().getCoder(BIGTABLE_WRITE_TYPE);
    flushInterval = Duration.standardSeconds(1);
  }

  @Test
  public void testCreateBulkShards() {
    final List<KV<ByteString, Iterable<Mutation>>> expected = new ArrayList<>();

    final String key1 = "key1";
    final String value1 = "value1";
    final String key2 = "key2";
    final String value2 = "value2";
    final String key3 = "key3";
    final String value3 = "value3";
    final String key4 = "key4";
    final String value4 = "value4";

    final TimestampedValue<KV<ByteString, Iterable<Mutation>>>
        firstMutation =
        makeWrite(key1, value1, Duration.standardMinutes(1));

    expected.add(firstMutation.getValue());

    final TimestampedValue<KV<ByteString, Iterable<Mutation>>>
        secondtMutation =
        makeWrite(key2, value2, Duration.standardMinutes(5));
    expected.add(secondtMutation.getValue());

    final TimestampedValue<KV<ByteString, Iterable<Mutation>>>
        thirdMutation =
        makeWrite(key3, value3, Duration.standardMinutes(1));
    expected.add(thirdMutation.getValue());

    final TimestampedValue<KV<ByteString, Iterable<Mutation>>>
        fourthMutation =
        makeWrite(key4, value4, Duration.standardMinutes(1));
    expected.add(fourthMutation.getValue());

    final TestStream<KV<ByteString, Iterable<Mutation>>>
        kvTestStream =
        TestStream.create(bigtableCoder)
            .addElements(firstMutation)
            .advanceProcessingTime(Duration.standardMinutes(2))
            .addElements(secondtMutation)
            .advanceProcessingTime(Duration.standardMinutes(11))
            .advanceWatermarkTo(baseTime.plus(Duration.standardMinutes(15)))
            // Late elements are always observable within the global window - they arrive before
            // the window closes, so they will appear in a pane, even if they arrive after the
            // allowed lateness, and are taken into account alongside on-time elements
            .addElements(thirdMutation)
            .addElements(fourthMutation)
            .advanceProcessingTime(Duration.standardMinutes(20))
            .advanceWatermarkToInfinity();

    final PCollection<Iterable<KV<ByteString, Iterable<Mutation>>>>
        actual =
        p.apply(kvTestStream)
            .apply(new TestPTransform(numOfShard, flushInterval));

    PAssert.that(actual).inEarlyGlobalWindowPanes().satisfies(new VerifyKVStreamFn(expected));

    p.run();
  }

  private TimestampedValue<KV<ByteString, Iterable<Mutation>>> makeWrite(String key,
                                                                         String value,
                                                                         Duration baseTimeOffset) {
    Instant timestamp = baseTime.plus(baseTimeOffset);
    ByteString rowKey = ByteString.copyFromUtf8(key);
    Iterable<Mutation> mutations =
        ImmutableList.of(
            Mutation.newBuilder()
                .setSetCell(Mutation.SetCell.newBuilder().setValue(ByteString.copyFromUtf8(value)))
                .build());
    return TimestampedValue.of(KV.of(rowKey, mutations), timestamp);
  }

  /**
   * Hepler class to verify output of {@link PCollection} by converting {@link ByteString}
   * to {@link String} to able to verify values.
   */
  private static class VerifyKVStreamFn
      implements
      SerializableFunction<Iterable<Iterable<KV<ByteString, Iterable<Mutation>>>>, Void> {

    private final Iterable<KV<ByteString, Iterable<Mutation>>> expected;

    private VerifyKVStreamFn(
        Iterable<KV<ByteString, Iterable<Mutation>>> expected) {
      this.expected = expected;
    }

    @Override
    public Void apply(Iterable<Iterable<KV<ByteString, Iterable<Mutation>>>> input) {
      verify(input, expected);
      return null;
    }

    private Iterable<KV<String, Iterable<Mutation>>> convertExpected(
        final Iterable<KV<ByteString, Iterable<Mutation>>> input) {
      List<KV<String, Iterable<Mutation>>> mutations = new ArrayList<>();
      for (KV<ByteString, Iterable<Mutation>> kv : input) {
        final String key = kv.getKey().toString(StandardCharsets.UTF_8);
        mutations.add(KV.of(key, kv.getValue()));
      }
      return mutations;
    }

    private Iterable<KV<String, Iterable<Mutation>>> convertActual(
        final Iterable<Iterable<KV<ByteString, Iterable<Mutation>>>> input) {
      List<KV<String, Iterable<Mutation>>> mutations = new ArrayList<>();
      for (Iterable<KV<ByteString, Iterable<Mutation>>> kv : input) {
        for (KV<ByteString, Iterable<Mutation>> value : kv) {
          final String key = value.getKey().toString(StandardCharsets.UTF_8);
          mutations.add(KV.of(key, value.getValue()));
        }
      }
      return mutations;
    }

    private void verify(final Iterable<Iterable<KV<ByteString, Iterable<Mutation>>>> input,
                        final Iterable<KV<ByteString, Iterable<Mutation>>> expected) {
      final Iterable<KV<String, Iterable<Mutation>>> actual = convertActual(input);
      final Iterable<KV<String, Iterable<Mutation>>> expectedValues = convertExpected(expected);

      final KV[] kvs = Iterables.toArray(expectedValues, KV.class);

      assertThat(actual, containsInAnyOrder(kvs));
    }
  }

  /**
   * Hepler to test createBulkShards.
   */
  private static class TestPTransform extends
                                      PTransform<PCollection<KV<ByteString, Iterable<Mutation>>>,
                                          PCollection<Iterable<KV<ByteString, Iterable<Mutation>>>>> {

    private final int numOfShards;
    private final Duration flushInterval;

    private TestPTransform(int numOfShards, Duration flushInterval) {
      this.numOfShards = numOfShards;
      this.flushInterval = flushInterval;
    }

    @Override
    public PCollection<Iterable<KV<ByteString, Iterable<Mutation>>>> expand(
        PCollection<KV<ByteString, Iterable<Mutation>>> input) {
      return BigtableBulkWriter.createBulkShards(input, numOfShards, flushInterval);
    }
  }
}
