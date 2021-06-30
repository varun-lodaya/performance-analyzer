/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.opendistro.elasticsearch.performanceanalyzer.writer;

import com.amazon.opendistro.elasticsearch.performanceanalyzer.PerformanceAnalyzerApp;
import com.amazon.opendistro.elasticsearch.performanceanalyzer.config.PerformanceAnalyzerController;
import com.amazon.opendistro.elasticsearch.performanceanalyzer.config.PluginSettings;
import com.amazon.opendistro.elasticsearch.performanceanalyzer.http_action.config.PerformanceAnalyzerConfigAction;
import com.amazon.opendistro.elasticsearch.performanceanalyzer.metrics.MetricsConfiguration;
import com.amazon.opendistro.elasticsearch.performanceanalyzer.metrics.PerformanceAnalyzerMetrics;
import com.amazon.opendistro.elasticsearch.performanceanalyzer.rca.framework.metrics.WriterMetrics;
import com.amazon.opendistro.elasticsearch.performanceanalyzer.reader_writer_shared.Event;
import com.amazon.opendistro.elasticsearch.performanceanalyzer.reader_writer_shared.EventLogFileHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class EventLogQueueProcessor {
    private static final Logger LOG = LogManager.getLogger(
            EventLogQueueProcessor.class);

    private final ScheduledExecutorService writerExecutor =
            Executors.newScheduledThreadPool(1);
    private final EventLogFileHandler eventLogFileHandler;
    private final long initialDelayMillis;
    private final long purgePeriodicityMillis;
    private final PerformanceAnalyzerController controller;
    private long lastTimeBucket;

    public EventLogQueueProcessor(EventLogFileHandler eventLogFileHandler,
                                  long initialDelayMillis,
                                  long purgePeriodicityMillis,
                                  PerformanceAnalyzerController controller) {
        this.eventLogFileHandler = eventLogFileHandler;
        this.initialDelayMillis = initialDelayMillis;
        this.purgePeriodicityMillis = purgePeriodicityMillis;
        this.lastTimeBucket = 0;
        this.controller = controller;
    }

    public void scheduleExecutor() {
        ScheduledFuture<?> futureHandle =
                writerExecutor.scheduleAtFixedRate(this::purgeQueueAndPersist,
                        // The initial delay is critical here. The collector threads
                        // start immediately with the Plugin. This thread purges the
                        // queue and writes data to file. So, it waits for one run of
                        // the collectors to complete before it starts, so that the
                        // queue has elements to drain.
                        initialDelayMillis,
                        purgePeriodicityMillis, TimeUnit.MILLISECONDS);
        new Thread(() -> {
            try {
                futureHandle.get();
            } catch (InterruptedException e) {
                LOG.error("Scheduled execution was interrupted", e);
            } catch (CancellationException e) {
                LOG.warn("Watcher thread has been cancelled", e);
            } catch (ExecutionException e) {
                LOG.error("QueuePurger interrupted. Caused by ",
                        e.getCause());
            }
        }).start();
    }

    // This executes every purgePeriodicityMillis interval.
    public void purgeQueueAndPersist() {
        // Delete the older event log files.
        // In case files deletion takes longer/fails, we are okay with eventQueue reaching
        // its max size (100000), post that {@link PerformanceAnalyzerMetrics#emitMetric()}
        // will emit metric {@link WriterMetrics#METRICS_WRITE_ERROR} and return.
        long currentTimeMillis = System.currentTimeMillis();
        int purgeInterval = PluginSettings.instance().getMetricsDeletionInterval();
        eventLogFileHandler.deleteFiles(currentTimeMillis, purgeInterval);

        // Drain the Queue, and if writer is enabled then persist to event log file.
        if (PerformanceAnalyzerConfigAction.getInstance() == null) {
            return;
        } else if (!controller.isPerformanceAnalyzerEnabled()) {
            // If PA is disabled, then we return as we don't want to generate
            // new files. But we also want to drain the queue so that when it is
            // enabled next, we don't have the current elements as they would be
            // old.
            if (PerformanceAnalyzerMetrics.metricQueue.size() > 0) {
                List<Event> metrics = new ArrayList<>();
                PerformanceAnalyzerMetrics.metricQueue.drainTo(metrics);
                LOG.info("Performance Analyzer no longer enabled. Drained the" +
                        "queue to remove stale data.");
            }
            return;
        } else if (PerformanceAnalyzerMetrics.metricQueue.size() == 0) {
            // If all the PA collectors are muted, the metricQueue will be
            // empty and in that case, we should return and not create empty files.
            PerformanceAnalyzerApp.WRITER_METRICS_AGGREGATOR.updateStat(
                    WriterMetrics.EMPTY_METRIC_QUEUE, "", 1);
            LOG.info("Performance Analyzer metrics queue is empty; possibly as all collectors are " +
                    "in muted state. Returning to avoid creating empty files.");
            return;
        }

        LOG.debug("Starting to purge the queue.");
        List<Event> metrics = new ArrayList<>();
        PerformanceAnalyzerMetrics.metricQueue.drainTo(metrics);
        LOG.debug("Queue draining successful.");

        // Calculate the timestamp on the file. For example, lets say the
        // purging started at time 12.5 then all the events between 5-10
        // are written to a file with name 5.
        long timeBucket = PerformanceAnalyzerMetrics.getTimeInterval(
                currentTimeMillis, MetricsConfiguration.SAMPLING_INTERVAL) -
                MetricsConfiguration.SAMPLING_INTERVAL;

        // When we are trying to collect the metrics for the 5th-10th second,
        // but doing that in the 12.5th second, there is a chance that a
        // collector ran in the 11th second and pushed the metrics in the
        // queue. This thread, should be able to filter them and write them
        // to their appropriate file, which should be 10 and not 5.
        long nextTimeBucket = timeBucket + MetricsConfiguration.SAMPLING_INTERVAL;

        List<Event> currMetrics = new ArrayList<>();
        List<Event> nextMetrics = new ArrayList<>();

        for (Event entry : metrics) {
            if (entry.epoch == timeBucket) {
                currMetrics.add(entry);
            } else if (entry.epoch == nextTimeBucket) {
                nextMetrics.add(entry);
            } else {
                //increment stale_metrics count when metrics to be collected is falling behind the current bucket
                PerformanceAnalyzerApp.WRITER_METRICS_AGGREGATOR.updateStat(
                    WriterMetrics.STALE_METRICS, "", 1);
            }
        }

        LOG.debug("Start serializing and writing to file.");
        writeAndRotate(currMetrics, timeBucket, currentTimeMillis);
        if (!nextMetrics.isEmpty()) {
            // The next bucket metrics don't need to be considered for
            // rotation just yet. So, we just write them to the
            // <nextTimeBucket>.tmp
            eventLogFileHandler.writeTmpFile(nextMetrics, nextTimeBucket);
        }
        LOG.debug("Writing to disk complete.");
    }

    private void writeAndRotate(final List<Event> currMetrics,
                                long currTimeBucket,
                                long currentTime) {
        // Going by the continuing example, we will rotate the 5.tmp file to
        // 5, which contains the metrics with epoch 5-10, whenever the purger
        // runs after the 15th second.
        if (lastTimeBucket != 0 && lastTimeBucket != currTimeBucket) {
            eventLogFileHandler.renameFromTmp(lastTimeBucket);
        }
        // This appends the data to a file named <currTimeBucket>.tmp
        eventLogFileHandler.writeTmpFile(currMetrics, currTimeBucket);
        lastTimeBucket = currTimeBucket;
    }
}
