/*-
 * -\-\-
 * bigtable-autoscaler
 * --
 * Copyright (C) 2018 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.autoscaler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.cloud.monitoring.v3.PagedResponseWrappers;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.ProjectName;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.protobuf.Timestamp;
import com.spotify.autoscaler.db.BigtableCluster;
import com.spotify.autoscaler.db.BigtableClusterBuilder;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

public class ClusterMetricsDataGenerator {

  private static final String PROJECT_ID = "project";
  private static final String INSTANCE_ID = "instance";
  private static final String CLUSTER_ID = "cluster";

  private static final String CLUSTER_FILTER =
      "resource.labels.project_id=\"%s\" AND resource.labels.instance=\"%s\""
      + " AND resource.labels.cluster=\"%s\"";

  private static final String NODE_COUNT_METRIC =
      String.format(
          "metric.type=\"bigtable.googleapis.com/cluster/node_count\" " + "AND %s", CLUSTER_FILTER);

  private static final String DISK_USAGE_METRIC =
      String.format(
          "metric.type=\"bigtable.googleapis.com/cluster/storage_utilization\" " + "AND %s",
          CLUSTER_FILTER);

  private static final String CPU_LOAD_METRIC =
      String.format(
          "metric.type=\"bigtable.googleapis.com/cluster/cpu_load\" " + "AND %s", CLUSTER_FILTER);

  private static final String RECEIVED_BYTES_METRIC =
      String.format(
          "metric.type=\"bigtable.googleapis.com/server/received_bytes_count\" " + "AND %s",
          CLUSTER_FILTER);

  private static final String SENT_BYTES_METRIC =
      String.format(
          "metric.type=\"bigtable.googleapis.com/server/sent_bytes_count\" " + "AND %s",
          CLUSTER_FILTER);

  private static final String REQUEST_COUNT_METRIC =
      String.format(
          "metric.type=\"bigtable.googleapis.com/server/request_count\" " + "AND %s",
          CLUSTER_FILTER);

  private static final String MODIFIED_ROWS_METRIC =
      String.format(
          "metric.type=\"bigtable.googleapis.com/server/modified_rows_count\" " + "AND %s",
          CLUSTER_FILTER);

  private static final String RETURNED_ROWS_METRIC =
      String.format(
          "metric.type=\"bigtable.googleapis.com/server/returned_rows_count\" " + "AND %s",
          CLUSTER_FILTER);

  private static final String ERROR_COUNT_METRIC =
      String.format(
          "metric.type=\"bigtable.googleapis.com/server/error_count\" " + "AND %s", CLUSTER_FILTER);

  public static void main(final String[] args) throws IOException {
    final BigtableCluster cluster =
        new BigtableClusterBuilder()
            .projectId(PROJECT_ID)
            .instanceId(INSTANCE_ID)
            .clusterId(CLUSTER_ID)
            .build();

    final TimeInterval interval = interval(Duration.ofHours(24));

    final Map<Instant, ClusterMetricsData> metrics = new TreeMap<>();
    populateIntervalWithValue(interval, metrics, p -> ClusterMetricsData.builder().build());

    final MetricServiceClient metricServiceClient = MetricServiceClient.create();
    populateMetric(metricServiceClient, metrics, cluster, interval, RECEIVED_BYTES_METRIC, true);
    populateMetric(metricServiceClient, metrics, cluster, interval, SENT_BYTES_METRIC, true);
    populateMetric(metricServiceClient, metrics, cluster, interval, REQUEST_COUNT_METRIC, true);
    populateMetric(metricServiceClient, metrics, cluster, interval, MODIFIED_ROWS_METRIC, true);
    populateMetric(metricServiceClient, metrics, cluster, interval, RETURNED_ROWS_METRIC, true);
    populateMetric(metricServiceClient, metrics, cluster, interval, ERROR_COUNT_METRIC, true);
    populateMetric(metricServiceClient, metrics, cluster, interval, DISK_USAGE_METRIC, false);
    populateMetric(metricServiceClient, metrics, cluster, interval, NODE_COUNT_METRIC, false);
    populateMetric(metricServiceClient, metrics, cluster, interval, CPU_LOAD_METRIC, false);

    // infer the value of loadDelta from the existing metrics, i.e. try to guess if a data job
    // started at some point
    calculateLoadDelta(metricServiceClient, metrics, cluster, interval);

    // save metrics as json
    final ObjectMapper mapper = new ObjectMapper();
    mapper.enable(SerializationFeature.INDENT_OUTPUT);
    try (final FileWriter file =
             new FileWriter(FakeBTCluster.getFilePathForCluster(cluster).toString())) {
      file.write(mapper.writeValueAsString(metrics));
      file.flush();
    } catch (final IOException e) {
      e.printStackTrace();
    }
  }

  // TODO
  private static void calculateLoadDelta(
      final MetricServiceClient metricServiceClient,
      final Map<Instant, ClusterMetricsData> metrics,
      final BigtableCluster cluster,
      final TimeInterval interval) {
    final double averageCPU =
        metrics
            .values()
            .stream()
            .map(ClusterMetricsData::cpuLoad)
            .mapToDouble(Double::doubleValue)
            .sum()
        / metrics.values().size();
    System.out.println("Average CPU: " + averageCPU);
    for (final Instant instant : metrics.keySet()) {
      if (metrics.get(instant).cpuLoad() > averageCPU * 2) {
        System.out.println("Job started at " + instant);
      }
    }
  }

  private static void populateMetric(
      final MetricServiceClient metricServiceClient,
      final Map<Instant, ClusterMetricsData> metrics,
      final BigtableCluster cluster,
      final TimeInterval interval,
      final String metricName,
      final boolean distribute) {

    aggregate(
        getMetric(metricServiceClient, cluster, interval, metricName),
        metrics,
        TypedValue::getDoubleValue,
        (existing, newValue) -> {
          final ClusterMetricsData.ClusterMetricsDataBuilder builder =
              ClusterMetricsData.ClusterMetricsDataBuilder.from(existing);
          System.out.println(newValue);
          if (metricName.equals(DISK_USAGE_METRIC)) {
            builder.diskUtilization(Math.max(existing.diskUtilization(), newValue));
          } else if (metricName.equals(NODE_COUNT_METRIC)) {
            builder.nodeCount(Math.max(existing.nodeCount(), newValue));
          } else if (metricName.equals(CPU_LOAD_METRIC)) {
            builder.cpuLoad(Math.max(existing.cpuLoad(), newValue));
          } else if (metricName.equals(RECEIVED_BYTES_METRIC)) {
            builder.receivedBytes(Double.sum(existing.receivedBytes(), newValue));
          } else if (metricName.equals(SENT_BYTES_METRIC)) {
            builder.sentBytes(Double.sum(existing.sentBytes(), newValue));
          } else if (metricName.equals(REQUEST_COUNT_METRIC)) {
            builder.requestCount(Double.sum(existing.requestCount(), newValue));
          } else if (metricName.equals(MODIFIED_ROWS_METRIC)) {
            builder.modifiedRows(Double.sum(existing.modifiedRows(), newValue));
          } else if (metricName.equals(ERROR_COUNT_METRIC)) {
            builder.returnedRows(Double.sum(existing.returnedRows(), newValue));
          }
          return builder.build();
        },
        distribute);
  }

  private static PagedResponseWrappers.ListTimeSeriesPagedResponse getMetric(
      final MetricServiceClient metricServiceClient,
      final BigtableCluster bigtableCluster,
      final TimeInterval interval,
      final String metricName) {
    return metricServiceClient.listTimeSeries(
        ProjectName.of(bigtableCluster.projectId()),
        String.format(
            metricName,
            bigtableCluster.projectId(),
            bigtableCluster.instanceId(),
            bigtableCluster.clusterId()),
        interval,
        ListTimeSeriesRequest.TimeSeriesView.FULL);
  }

  private static void aggregate(
      final PagedResponseWrappers.ListTimeSeriesPagedResponse response,
      final Map<Instant, ClusterMetricsData> metrics,
      final Function<TypedValue, Double> converter,
      final BiFunction<ClusterMetricsData, Double, ClusterMetricsData> valueCalculator,
      final boolean distribute) {

    for (final PagedResponseWrappers.ListTimeSeriesPage page : response.iteratePages()) {
      for (final TimeSeries ts : page.getValues()) {
        for (final Point p : ts.getPointsList()) {
          final Double value = converter.apply(p.getValue());
          if (distribute) {
            final Instant startInstant =
                Instant.ofEpochSecond(p.getInterval().getStartTime().getSeconds());
            final Instant endInstant =
                Instant.ofEpochSecond(p.getInterval().getEndTime().getSeconds());
            final long minutesBetween =
                Duration.between(startInstant, endInstant).abs().toMinutes();
            final double distributedValue = value / minutesBetween;
            populateIntervalWithValue(
                p.getInterval(), metrics, q -> valueCalculator.apply(q, distributedValue));
          } else {
            populateIntervalWithValue(
                p.getInterval(), metrics, q -> valueCalculator.apply(q, value));
          }
        }
      }
    }
  }

  private static void populateIntervalWithValue(
      final TimeInterval interval,
      final Map<Instant, ClusterMetricsData> result,
      final Function<ClusterMetricsData, ClusterMetricsData> valueCalculator) {

    final Instant startInstant = Instant.ofEpochSecond(interval.getStartTime().getSeconds());
    final Instant startMinute = startInstant.truncatedTo(ChronoUnit.MINUTES);
    final Instant endInstant = Instant.ofEpochSecond(interval.getEndTime().getSeconds());
    final Instant endMinute = endInstant.truncatedTo(ChronoUnit.MINUTES);

    for (Instant i = startMinute;
         i.isBefore(endMinute) || i.equals(startMinute);
         i = i.plus(1, ChronoUnit.MINUTES)) {
      result.put(i, valueCalculator.apply(result.get(i)));
    }
  }

  private static TimeInterval interval(final Duration duration) {
    final long currentTimeSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());

    final Timestamp pastTimestamp =
        Timestamp.newBuilder().setSeconds(currentTimeSeconds - duration.getSeconds()).build();
    final Timestamp currentTimestamp =
        Timestamp.newBuilder().setSeconds(currentTimeSeconds).build();
    return TimeInterval.newBuilder()
        .setStartTime(pastTimestamp)
        .setEndTime(currentTimestamp)
        .build();
  }
}
