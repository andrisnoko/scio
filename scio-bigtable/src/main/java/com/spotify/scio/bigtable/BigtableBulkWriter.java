package com.spotify.scio.bigtable;

import com.google.bigtable.v2.Mutation;
import com.google.cloud.bigtable.config.BigtableOptions;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.beam.sdk.io.gcp.bigtable.BigtableServiceHelper;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.AfterProcessingTime;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.Repeatedly;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BigtableBulkWriter extends
                                PTransform<PCollection<KV<ByteString, Iterable<Mutation>>>, PDone> {

  private static final Logger LOG = LoggerFactory.getLogger(BigtableBulkWriter.class);

  private final BigtableOptions bigtableOptions;
  private final String tableName;
  private final int numOfShards;
  private final Duration flushInterval;

  public BigtableBulkWriter(String tableName,
                            BigtableOptions bigtableOptions,
                            int numOfShards,
                            Duration flushInterval){
   this.bigtableOptions = bigtableOptions;
   this.tableName = tableName;
   this.numOfShards = numOfShards;
   this.flushInterval = flushInterval;
  }

  @Override
  public PDone expand(PCollection<KV<ByteString, Iterable<Mutation>>> input) {
   input
        .apply("Assign To Shard", ParDo.of(new AssignToShard(numOfShards)))
        .apply("Window", Window
            .<KV<Long, KV<ByteString, Iterable<Mutation>>>>into(new GlobalWindows())
            .triggering(Repeatedly.forever(
                AfterProcessingTime
                    .pastFirstElementInPane()
                    .plusDelayOf(flushInterval)))
            .discardingFiredPanes())
        .apply("Group By Shard", GroupByKey.create())
        .apply("Gets Mutations", ParDo
            .of(new DoFn<KV<Long, Iterable<KV<ByteString, Iterable<Mutation>>>>,
                Iterable<KV<ByteString, Iterable<Mutation>>>>() {
              @ProcessElement
              public void process(ProcessContext c) {
                c.output(c.element().getValue());
              }
            }))
        .apply("Bigtable BulkWrite", ParDo.of(new BigtableBulkWriterFn()));
    return PDone.in(input.getPipeline());
  }

  private class BigtableBulkWriterFn extends
                                     DoFn<Iterable<KV<ByteString, Iterable<Mutation>>>, Void> {

    private BigtableServiceHelper.Writer bigtableWriter;
    private long recordsWritten;
    private final ConcurrentLinkedQueue<BigtableWriteException> failures;

    public BigtableBulkWriterFn() {
     this.failures = new ConcurrentLinkedQueue<>();
    }

    @StartBundle
    public void startBundle(StartBundleContext c) throws IOException {
      if (bigtableWriter == null) {
        bigtableWriter = new BigtableServiceHelper(bigtableOptions)
            .openForWriting(tableName);
      }
      recordsWritten = 0;
    }

    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      checkForFailures(failures);
      final Iterable<KV<ByteString, Iterable<Mutation>>> elements = c.element();
      final Iterator<KV<ByteString, Iterable<Mutation>>> iterator = elements.iterator();

      while (iterator.hasNext()) {
        final KV<ByteString, Iterable<Mutation>> value = iterator.next();
        bigtableWriter
            .writeRecord(value)
            .whenComplete(
                (mutationResult, exception) -> {
                  if (exception != null) {
                    failures.add(new BigtableWriteException(value, exception));
                  }
                });
        ++recordsWritten;
      }
    }

    @FinishBundle
    public void finishBundle() throws Exception {
      bigtableWriter.flush();
      checkForFailures(failures);
      LOG.debug("Wrote {} records", recordsWritten);
    }

    @Teardown
    public void tearDown() throws Exception {
      if (bigtableWriter != null) {
        bigtableWriter.close();
        bigtableWriter = null;
      }
    }

    /**
     * If any write has asynchronously failed, fail the bundle with a useful error.
     */
    private void checkForFailures(final ConcurrentLinkedQueue<BigtableWriteException> failures)
        throws IOException {
      // Note that this function is never called by multiple threads and is the only place that
      // we remove from failures, so this code is safe.
      if (failures.isEmpty()) {
        return;
      }

      StringBuilder logEntry = new StringBuilder();
      int i = 0;
      List<BigtableWriteException> suppressed = Lists.newArrayList();
      for (; i < 10 && !failures.isEmpty(); ++i) {
        BigtableWriteException exc = failures.remove();
        logEntry.append("\n").append(exc.getMessage());
        if (exc.getCause() != null) {
          logEntry.append(": ").append(exc.getCause().getMessage());
        }
        suppressed.add(exc);
      }
      String message =
          String.format(
              "At least %d errors occurred writing to Bigtable. First %d errors: %s",
              i + failures.size(),
              i,
              logEntry.toString());
      LOG.error(message);
      IOException exception = new IOException(message);
      for (BigtableWriteException e : suppressed) {
        exception.addSuppressed(e);
      }
      throw exception;
    }

    /**
     * An exception that puts information about the failed record being written in its message.
     */
    class BigtableWriteException extends IOException {

      public BigtableWriteException(KV<ByteString, Iterable<Mutation>> record, Throwable cause) {
        super(
            String.format(
                "Error mutating row %s with mutations %s",
                record.getKey().toStringUtf8(),
                record.getValue()),
            cause);
      }
    }
  }

  static class AssignToShard extends DoFn<KV<ByteString, Iterable<Mutation>>,
      KV<Long, KV<ByteString, Iterable<Mutation>>>> {

    private final int numOfShards;

    AssignToShard(int numOfShards) {
      this.numOfShards = numOfShards;
    }

    @ProcessElement
    public void processElement(final ProcessContext c) {
      // assign this element to a random shard
      final long shard = ThreadLocalRandom.current().nextLong(numOfShards);
      c.output(KV.of(shard, c.element()));
    }
  }
}
