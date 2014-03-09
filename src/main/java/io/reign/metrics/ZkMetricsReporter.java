package io.reign.metrics;

import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;

/**
 * A reporter which writes to ZooKeeper. Based off CsvReporter code from Codahale Metrics. Not extending
 * ScheduledReporter to control threads.
 * 
 * @author ypai
 */
public class ZkMetricsReporter {
    /**
     * Returns a new {@link Builder} for {@link ZkMetricsReporter}.
     * 
     * @param registry
     *            the registry to report
     * @return a {@link Builder} instance for a {@link ZkMetricsReporter}
     */
    public static Builder forRegistry(RotatingMetricRegistryRef registryRef) {
        return new Builder(registryRef);
    }

    /**
     * A builder for {@link ZkMetricsReporter} instances. Defaults to using the default locale, converting rates to
     * events/second, converting durations to milliseconds, and not filtering metrics.
     */
    public static class Builder {
        private final RotatingMetricRegistryRef registryRef;
        private Locale locale;
        private TimeUnit rateUnit;
        private TimeUnit durationUnit;
        // private final Clock clock;
        private MetricFilter filter;

        private Builder(RotatingMetricRegistryRef registryRef) {
            this.registryRef = registryRef;
            this.locale = Locale.getDefault();
            this.rateUnit = TimeUnit.SECONDS;
            this.durationUnit = TimeUnit.MILLISECONDS;
            // this.clock = Clock.defaultClock();
            this.filter = MetricFilter.ALL;
        }

        /**
         * Format numbers for the given {@link Locale}.
         * 
         * @param locale
         *            a {@link Locale}
         * @return {@code this}
         */
        public Builder formatFor(Locale locale) {
            this.locale = locale;
            return this;
        }

        /**
         * Convert rates to the given time unit.
         * 
         * @param rateUnit
         *            a unit of time
         * @return {@code this}
         */
        public Builder convertRatesTo(TimeUnit rateUnit) {
            this.rateUnit = rateUnit;
            return this;
        }

        /**
         * Convert durations to the given time unit.
         * 
         * @param durationUnit
         *            a unit of time
         * @return {@code this}
         */
        public Builder convertDurationsTo(TimeUnit durationUnit) {
            this.durationUnit = durationUnit;
            return this;
        }

        // /**
        // * Use the given {@link Clock} instance for the time.
        // *
        // * @param clock
        // * a {@link Clock} instance
        // * @return {@code this}
        // */
        // public Builder withClock(Clock clock) {
        // this.clock = clock;
        // return this;
        // }

        /**
         * Only report metrics which match the given filter.
         * 
         * @param filter
         *            a {@link MetricFilter}
         * @return {@code this}
         */
        public Builder filter(MetricFilter filter) {
            this.filter = filter;
            return this;
        }

        public ZkMetricsReporter build() {
            return new ZkMetricsReporter(registryRef, rateUnit, durationUnit, filter);
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(ZkMetricsReporter.class);
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final RotatingMetricRegistryRef registryRef;
    private final MetricFilter filter;
    private final double durationFactor;
    private final String durationUnit;
    private final double rateFactor;
    private final String rateUnit;

    private ZkMetricsReporter(RotatingMetricRegistryRef registryRef, TimeUnit rateUnit, TimeUnit durationUnit,
            MetricFilter filter) {
        this.registryRef = registryRef;
        this.filter = filter;
        this.rateFactor = rateUnit.toSeconds(1);
        this.rateUnit = calculateRateUnit(rateUnit);
        this.durationFactor = 1.0 / durationUnit.toNanos(1);
        this.durationUnit = durationUnit.toString().toLowerCase(Locale.US);

    }

    public StringBuilder report(StringBuilder sb) {
        return report(sb, registryRef.get().getGauges(filter), registryRef.get().getCounters(filter), registryRef.get()
                .getHistograms(filter), registryRef.get().getMeters(filter), registryRef.get().getTimers(filter));
    }

    public StringBuilder report(StringBuilder sb, SortedMap<String, Gauge> gauges, SortedMap<String, Counter> counters,
            SortedMap<String, Histogram> histograms, SortedMap<String, Meter> meters, SortedMap<String, Timer> timers) {
        sb.append("{\n");

        sb.append("\"interval_start_ts\":");
        sb.append(registryRef.getLastRotatedTimestamp());
        sb.append(",\n");

        sb.append("\"interval_length\":");
        sb.append(registryRef.getRotationInterval());
        sb.append(",\n");

        sb.append("\"interval_length_unit\":\"");
        sb.append(calculateRateUnit(registryRef.getRotationTimeUnit()));
        sb.append("\",\n");

        if (counters.size() > 0) {
            int i = 0;
            sb.append("\"counters\":{\n");
            for (Map.Entry<String, Counter> entry : counters.entrySet()) {
                if (i++ > 0) {
                    sb.append(",\n");
                }
                String name = entry.getKey();
                sb.append("    \"");
                sb.append(name);
                sb.append("\":");
                reportCounter(sb, name, entry.getValue());
            }
            sb.append("\n}");
        }

        if (gauges.size() > 0) {
            if (counters.size() > 0) {
                sb.append(",\n");
            }
            int i = 0;
            sb.append("\"gauges\":{\n");
            for (Map.Entry<String, Gauge> entry : gauges.entrySet()) {
                if (i++ > 0) {
                    sb.append(",\n");
                }
                String name = entry.getKey();
                sb.append("    { \"");
                sb.append(name);
                sb.append("\":");
                reportGauge(sb, name, entry.getValue());
                sb.append(" }");
            }
            sb.append("\n}");
        }

        if (histograms.size() > 0) {
            if (counters.size() > 0) {
                sb.append(",\n");
            }
            int i = 0;
            sb.append("\"histograms\":{\n");
            for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
                if (i++ > 0) {
                    sb.append(",\n");
                }
                String name = entry.getKey();
                sb.append("    { \"");
                sb.append(name);
                sb.append("\":");
                reportHistogram(sb, entry.getKey(), entry.getValue());
                sb.append(" }");
            }
            sb.append("\n}");
        }

        if (meters.size() > 0) {
            if (histograms.size() > 0) {
                sb.append(",\n");
            }
            sb.append(",\n");
            int i = 0;
            sb.append("\"meters\":{\n");
            for (Map.Entry<String, Meter> entry : meters.entrySet()) {
                if (i++ > 0) {
                    sb.append(",\n");
                }
                String name = entry.getKey();
                sb.append("    {\"");
                sb.append(name);
                sb.append("\":");
                reportMeter(sb, entry.getKey(), entry.getValue());
                sb.append(" }");
            }
            sb.append("\n}");
        }

        if (timers.size() > 0) {
            if (meters.size() > 0) {
                sb.append(",\n");
            }
            sb.append(",\n");
            int i = 0;
            sb.append("\"timers\":{\n");
            for (Map.Entry<String, Timer> entry : timers.entrySet()) {
                if (i++ > 0) {
                    sb.append(",\n");
                }
                String name = entry.getKey();
                sb.append("    {\"");
                sb.append(name);
                sb.append("\":");
                reportTimer(sb, entry.getKey(), entry.getValue());
                sb.append(" }");
            }
            sb.append("\n}");
        }
        sb.append("\n}");

        return sb;
    }

    private void reportTimer(StringBuilder sb, String name, Timer timer) {
        final Snapshot snapshot = timer.getSnapshot();

        report(sb, name, new String[] { "count", "max", "mean", "min", "stddev", "p50", "p75", "p95", "p98", "p99",
                "p999", "mean_rate", "m1_rate", "m5_rate", "m15_rate", "rate_unit", "duration_unit" },
                timer.getCount(), convertDuration(snapshot.getMax()), convertDuration(snapshot.getMean()),
                convertDuration(snapshot.getMin()), convertDuration(snapshot.getStdDev()),
                convertDuration(snapshot.getMedian()), convertDuration(snapshot.get75thPercentile()),
                convertDuration(snapshot.get95thPercentile()), convertDuration(snapshot.get98thPercentile()),
                convertDuration(snapshot.get99thPercentile()), convertDuration(snapshot.get999thPercentile()),
                convertRate(timer.getMeanRate()), convertRate(timer.getOneMinuteRate()),
                convertRate(timer.getFiveMinuteRate()), convertRate(timer.getFifteenMinuteRate()), getRateUnit(),
                getDurationUnit());
    }

    private void reportMeter(StringBuilder sb, String name, Meter meter) {
        report(sb, name, new String[] { "count", "mean_rate", "m1_rate", "m5_rate", "m15_rate", "rate_unit" },
                meter.getCount(), convertRate(meter.getMeanRate()), convertRate(meter.getOneMinuteRate()),
                convertRate(meter.getFiveMinuteRate()), convertRate(meter.getFifteenMinuteRate()), getRateUnit());
    }

    private void reportHistogram(StringBuilder sb, String name, Histogram histogram) {
        final Snapshot snapshot = histogram.getSnapshot();
        report(sb, name, new String[] { "count", "max", "mean", "min", "stddev", "p50", "p75", "p95", "p98", "p99",
                "p999" }, histogram.getCount(), snapshot.getMax(), snapshot.getMean(), snapshot.getMin(),
                snapshot.getStdDev(), snapshot.getMedian(), snapshot.get75thPercentile(), snapshot.get95thPercentile(),
                snapshot.get98thPercentile(), snapshot.get99thPercentile(), snapshot.get999thPercentile());
    }

    private void reportCounter(StringBuilder sb, String name, Counter counter) {
        report(sb, name, new String[] { "count" }, counter.getCount());
    }

    private void reportGauge(StringBuilder sb, String name, Gauge gauge) {
        report(sb, name, new String[] { "value" }, gauge.getValue());
    }

    private void report(StringBuilder sb, String name, String[] keys, Object... values) {

        // encode metrics data
        sb.append("{");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            String key = keys[i];
            sb.append("\"").append(key).append("\"");
            sb.append(":");
            sb.append("\"").append(values[i]).append("\"");
        }
        sb.append("}");

    }

    protected String sanitize(String name) {
        return name;
    }

    protected String getRateUnit() {
        return rateUnit;
    }

    protected String getDurationUnit() {
        return durationUnit;
    }

    protected double convertDuration(double duration) {
        return duration * durationFactor;
    }

    protected double convertRate(double rate) {
        return rate * rateFactor;
    }

    private String calculateRateUnit(TimeUnit unit) {
        // final String s = unit.toString().toLowerCase(Locale.US);
        // return s.substring(0, s.length() - 1);
        return unit.name();
    }
}