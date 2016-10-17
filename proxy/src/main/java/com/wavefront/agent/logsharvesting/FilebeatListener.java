package com.wavefront.agent.logsharvesting;

import com.google.common.annotations.VisibleForTesting;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.wavefront.agent.PointHandler;
import com.wavefront.agent.config.LogsIngestionConfig;
import com.wavefront.agent.config.MetricMatcher;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.Metric;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricsRegistry;
import com.yammer.metrics.core.WavefrontHistogram;

import org.logstash.beats.IMessageListener;
import org.logstash.beats.Message;

import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

import io.netty.channel.ChannelHandlerContext;
import sunnylabs.report.TimeSeries;

/**
 * Listens for messages from Filebeat and processes them, sending telemetry to a given sink.
 *
 * @author Mori Bellamy (mori@wavefront.com)
 */
public class FilebeatListener implements IMessageListener {
  protected static final Logger logger = Logger.getLogger(FilebeatListener.class.getCanonicalName());
  private final FlushProcessor flushProcessor;
  private static final ReadProcessor readProcessor = new ReadProcessor();
  private final PointHandler pointHandler;
  private final MetricsRegistry metricsRegistry;
  private final LogsIngestionConfig logsIngestionConfig;
  private final LoadingCache<MetricName, Metric> metricCache;
  // Keep track of the last timestamp we read each metric name from the log stream.
  private final ConcurrentHashMap<MetricName, Long> lastReadTime;
  private final Counter received, unparsed, parsed, sent, malformed;
  private final Histogram drift;
  private final String prefix;
  private final Supplier<Long> currentMillis;
  private final MetricsReporter metricsReporter;

  /**
   * @param pointHandler              Play parsed metrics and meta-metrics to this
   * @param logsIngestionConfig       configuration object for logs harvesting
   * @param prefix                    all harvested metrics start with this prefix
   * @param currentMillis             supplier of the current time in millis
   * @param metricsReapIntervalMillis millisecond interval for scanning old metrics to remove from the registry.
   */
  public FilebeatListener(PointHandler pointHandler, LogsIngestionConfig logsIngestionConfig,
                          String prefix, Supplier<Long> currentMillis, int metricsReapIntervalMillis) {
    this.pointHandler = pointHandler;
    this.prefix = prefix;
    this.logsIngestionConfig = logsIngestionConfig;
    this.metricsRegistry = new MetricsRegistry();
    // Meta metrics.
    this.received = Metrics.newCounter(new MetricName("logsharvesting", "", "received"));
    this.unparsed = Metrics.newCounter(new MetricName("logsharvesting", "", "unparsed"));
    this.parsed = Metrics.newCounter(new MetricName("logsharvesting", "", "parsed"));
    this.malformed = Metrics.newCounter(new MetricName("logsharvesting", "", "malformed"));
    this.sent = Metrics.newCounter(new MetricName("logsharvesting", "", "sent"));
    this.drift = Metrics.newHistogram(new MetricName("logsharvesting", "", "drift"));
    this.currentMillis = currentMillis;
    this.flushProcessor = new FlushProcessor(sent, currentMillis);
    this.lastReadTime = new ConcurrentHashMap<>();

    // Set up user specified metric harvesting.
    this.metricCache = Caffeine.<MetricName, Metric>newBuilder()
        .build(new MetricCacheLoader(metricsRegistry, currentMillis));

    // Continually flush user metrics to Wavefront.
    this.metricsReporter = new MetricsReporter(
        metricsRegistry, flushProcessor, "FilebeatMetricsReporter", pointHandler, prefix);
    this.metricsReporter.start(logsIngestionConfig.aggregationIntervalSeconds, TimeUnit.SECONDS);

    // Continually pace the lastReadTime map, removing stale entries.
    new Timer().schedule(new TimerTask() {
      @Override
      public void run() {
        reapOldMetrics();
      }
    }, metricsReapIntervalMillis, metricsReapIntervalMillis);
  }

  @VisibleForTesting
  void reapOldMetrics() {
    for (Map.Entry<MetricName, Long> entry : lastReadTime.entrySet()) {
      if (currentMillis.get() - entry.getValue() > logsIngestionConfig.expiryMillis) {
        metricCache.asMap().remove(entry.getKey());
        metricsRegistry.removeMetric(entry.getKey());
        lastReadTime.remove(entry.getKey());
      }
    }
  }

  @VisibleForTesting
  MetricsReporter getMetricsReporter() {
    return metricsReporter;
  }

  @Override
  public void onNewMessage(ChannelHandlerContext ctx, Message message) {
    received.inc();
    FilebeatMessage filebeatMessage;
    boolean success = false;
    try {
      filebeatMessage = new FilebeatMessage(message);
    } catch (MalformedMessageException exn) {
      logger.severe("Malformed message received from filebeat, dropping.");
      malformed.inc();
      return;
    }

    if (filebeatMessage.getTimestampMillis() != null) {
      drift.update(currentMillis.get() - filebeatMessage.getTimestampMillis());
    }

    Double[] output = {null};
    for (MetricMatcher metricMatcher : logsIngestionConfig.counters) {
      TimeSeries timeSeries = metricMatcher.timeSeries(filebeatMessage, output);
      if (timeSeries == null) continue;
      readMetric(Counter.class, timeSeries, output[0]);
      success = true;
    }

    for (MetricMatcher metricMatcher : logsIngestionConfig.gauges) {
      TimeSeries timeSeries = metricMatcher.timeSeries(filebeatMessage, output);
      if (timeSeries == null) continue;
      readMetric(Gauge.class, timeSeries, output[0]);
      success = true;
    }

    for (MetricMatcher metricMatcher : logsIngestionConfig.histograms) {
      TimeSeries timeSeries = metricMatcher.timeSeries(filebeatMessage, output);
      if (timeSeries == null) continue;
      readMetric(logsIngestionConfig.useWavefrontHistograms ? WavefrontHistogram.class : Histogram.class,
          timeSeries, output[0]);
      success = true;
    }
    if (!success) unparsed.inc();
  }

  private void readMetric(Class clazz, TimeSeries timeSeries, Double value) {
    MetricName metricName = TimeSeriesUtils.toMetricName(clazz, timeSeries);
    lastReadTime.put(metricName, currentMillis.get());
    Metric metric = metricCache.get(metricName);
    try {
      metric.processWith(readProcessor, metricName, new ReadProcessorContext(value));
    } catch (Exception e) {
      logger.severe("Could not process metric " + metricName.toString());
      e.printStackTrace();
    }
    parsed.inc();
  }

  @Override
  public void onNewConnection(ChannelHandlerContext ctx) {
  }

  @Override
  public void onConnectionClose(ChannelHandlerContext ctx) {
  }

  @Override
  public void onException(ChannelHandlerContext ctx, Throwable cause) {
  }

  @Override
  public void onChannelInitializeException(ChannelHandlerContext ctx, Throwable cause) {
  }
}
