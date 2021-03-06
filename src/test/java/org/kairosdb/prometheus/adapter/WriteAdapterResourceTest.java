package org.kairosdb.prometheus.adapter;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import org.junit.Before;
import org.junit.Test;
import org.kairosdb.core.datapoints.DoubleDataPoint;
import org.kairosdb.eventbus.FilterEventBus;
import org.kairosdb.eventbus.Publisher;
import org.kairosdb.events.DataPointEvent;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import prometheus.Remote.WriteRequest;
import prometheus.Types.Label;
import prometheus.Types.Sample;
import prometheus.Types.TimeSeries;

import javax.ws.rs.core.Response;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class WriteAdapterResourceTest
{
    @Mock
    private FilterEventBus mockEventBus;
    @Mock
    private Publisher<DataPointEvent> mockPublisher;

    @Before
    public void setup()
    {
        MockitoAnnotations.initMocks(this);
        when(mockEventBus.<DataPointEvent>createPublisher(any())).thenReturn(mockPublisher);
    }

    @Test
    /*
     * Verify that time series get published to Kairos via the publisher.
     */
    public void test()
            throws UnknownHostException
    {
        WriteAdapterResource writeAdapterResource = new WriteAdapterResource(mockEventBus, "", "", "");

        long timeStamp = System.currentTimeMillis();
        ImmutableSortedMap<String, String> labels = ImmutableSortedMap.of("label1", "value1", "label2", "value2");
        TimeSeries timeSeries = newTimeSeries("metric1", ImmutableMap.of(timeStamp, 5.0), labels);
        Response response = writeAdapterResource.write(newRequest(timeSeries));

        assertThat(response.getStatus(), equalTo(200));
        verify(mockPublisher).post(new DataPointEvent("metric1", labels, new DoubleDataPoint(timeStamp, 5.0)));
        verifyInternalMetrics("kairosdb.prometheus.write-adapter.metrics-sent.count", "sent", 1.0);
    }

    @Test
    /*
     * Verify that metrics that match the kairosdb.plugin.prometheus-adapter.writer.dropMetrics regex are not added to Kairos.
     */
    public void testDroppedMetrics()
            throws UnknownHostException
    {
        WriteAdapterResource writeAdapterResource = new WriteAdapterResource(mockEventBus, "myPrefix.", "^foo_.*$,^scrape_duration_seconds$","");

        long timeStamp = System.currentTimeMillis();
        ImmutableSortedMap<String, String> labels = ImmutableSortedMap.of("label1", "value1", "label2", "value2");
        TimeSeries timeSeries1 = newTimeSeries("foo_bar", ImmutableMap.of(timeStamp, 1.0), labels);
        TimeSeries timeSeries2 = newTimeSeries("foo.bar", ImmutableMap.of(timeStamp, 2.0), labels);
        TimeSeries timeSeries3 = newTimeSeries("foo_bob", ImmutableMap.of(timeStamp, 3.0), labels);
        TimeSeries timeSeries4 = newTimeSeries("scrape_duration_seconds", ImmutableMap.of(timeStamp, 3.0), labels);
        Response response = writeAdapterResource.write(newRequest(timeSeries1, timeSeries2, timeSeries3, timeSeries4));

        assertThat(response.getStatus(), equalTo(200));
        verify(mockPublisher).post(new DataPointEvent("myPrefix.foo.bar", labels, new DoubleDataPoint(timeStamp, 2.0)));
        verify(mockPublisher, never()).post(new DataPointEvent("myPrefix.foo_bar", labels, new DoubleDataPoint(timeStamp, 1.0)));
        verify(mockPublisher, never()).post(new DataPointEvent("myPrefix.foo_bob", labels, new DoubleDataPoint(timeStamp, 3.0)));
        verify(mockPublisher, never()).post(new DataPointEvent("myPrefix.scrape_duration_seconds", labels, new DoubleDataPoint(timeStamp, 3.0)));
        verifyInternalMetrics("kairosdb.prometheus.write-adapter.metrics-sent.count", "sent", 1.0);
        verifyInternalMetrics("kairosdb.prometheus.write-adapter.metrics-sent.count", "dropped", 3.0);
    }

    @Test
    /*
     * Verify that tags that match the kairosdb.plugin.prometheus-adapter.writer.dropLabels regex are not added with the metric.
     */
    public void testDroppedLabels()
            throws UnknownHostException
    {
        WriteAdapterResource writeAdapterResource = new WriteAdapterResource(mockEventBus, "", "", "^label1$, ^label2$ ,^label3$");

        long timeStamp = System.currentTimeMillis();
        ImmutableSortedMap<String, String> labels = ImmutableSortedMap.of("label1", "value1", "label2", "value2", "fooLabel", "fooValue");
        TimeSeries timeSeries = newTimeSeries("foo_bar", ImmutableMap.of(timeStamp, 1.0), labels);
        Response response = writeAdapterResource.write(newRequest(timeSeries));

        assertThat(response.getStatus(), equalTo(200));
        verify(mockPublisher).post(new DataPointEvent("foo_bar", ImmutableSortedMap.of("fooLabel", "fooValue"), new DoubleDataPoint(timeStamp, 1.0)));
        verifyInternalMetrics("kairosdb.prometheus.write-adapter.metrics-sent.count", "sent", 1.0);
    }

    @Test
    /*
     * Verify that prefix from kairosdb.plugin.prometheus-adapter.writer.prefix is added to the metrics
     */
    public void testPrefix()
            throws UnknownHostException
    {
        WriteAdapterResource writeAdapterResource = new WriteAdapterResource(mockEventBus,"thePrefix." , "", "");

        long timeStamp = System.currentTimeMillis();
        ImmutableSortedMap<String, String> labels = ImmutableSortedMap.of("label1", "value1", "label2", "value2");
        TimeSeries timeSeries1 = newTimeSeries("foo_bar", ImmutableMap.of(timeStamp, 1.0), labels);
        TimeSeries timeSeries2 = newTimeSeries("foo.bar", ImmutableMap.of(timeStamp, 2.0), labels);
        TimeSeries timeSeries3 = newTimeSeries("foo_bob", ImmutableMap.of(timeStamp, 3.0), labels);
        Response response = writeAdapterResource.write(newRequest(timeSeries1, timeSeries2, timeSeries3));

        assertThat(response.getStatus(), equalTo(200));
        verify(mockPublisher).post(new DataPointEvent("thePrefix.foo_bar", labels, new DoubleDataPoint(timeStamp, 1.0)));
        verify(mockPublisher).post(new DataPointEvent("thePrefix.foo.bar", labels, new DoubleDataPoint(timeStamp, 2.0)));
        verify(mockPublisher).post(new DataPointEvent("thePrefix.foo_bob", labels, new DoubleDataPoint(timeStamp, 3.0)));
        verifyInternalMetrics("kairosdb.prometheus.write-adapter.metrics-sent.count", "sent", 3.0);
    }

    @Test
    /*
     * Verify that a 500 error is returned on an exception and internal metrics are logged.
     */
    public void testException()
            throws UnknownHostException
    {
        WriteAdapterResource writeAdapterResource = new WriteAdapterResource(mockEventBus, "", "", "");

        long timeStamp = System.currentTimeMillis();
        ImmutableSortedMap<String, String> labels = ImmutableSortedMap.of("label1", "value1", "label2", "value2");
        TimeSeries timeSeries1 = newTimeSeries("foo_bar", ImmutableMap.of(timeStamp, 1.0), labels);
        TimeSeries timeSeries2 = newTimeSeries("foo.bar", ImmutableMap.of(timeStamp, 2.0), labels);
        TimeSeries timeSeries3 = newTimeSeries("foo_bob", ImmutableMap.of(timeStamp, 3.0), labels);
        TimeSeries timeSeries4 = newTimeSeries("", ImmutableMap.of(timeStamp, 4.0), labels); // Invalid timeseries will cause exception
        Response response = writeAdapterResource.write(newRequest(timeSeries1, timeSeries2, timeSeries3, timeSeries4));

        assertThat(response.getStatus(), equalTo(500));
        verify(mockPublisher, never()).post(new DataPointEvent("thePrefix.foo_bar", labels, new DoubleDataPoint(timeStamp, 1.0)));
        verify(mockPublisher, never()).post(new DataPointEvent("thePrefix.foo.bar", labels, new DoubleDataPoint(timeStamp, 2.0)));
        verify(mockPublisher, never()).post(new DataPointEvent("thePrefix.foo_bob", labels, new DoubleDataPoint(timeStamp, 3.0)));
        verifyInternalExceptionMetric("kairosdb.prometheus.write-adapter.exception.count", "No metric name was specified for the given metric. Missing __name__ label.");
    }

    @Test
    public void test_NAN_or_Infinite()
            throws UnknownHostException
    {
        WriteAdapterResource writeAdapterResource = new WriteAdapterResource(mockEventBus, "", "", "");

        long timeStamp = System.currentTimeMillis();
        TimeSeries timeSeries1 = newTimeSeries("foo_bar1", ImmutableMap.of(timeStamp, 1.0), ImmutableSortedMap.of());
        TimeSeries timeSeries2 = newTimeSeries("foo_bar2", ImmutableMap.of(timeStamp, Double.NaN), ImmutableSortedMap.of());
        TimeSeries timeSeries3 = newTimeSeries("foo_bar3", ImmutableMap.of(timeStamp, Double.NEGATIVE_INFINITY), ImmutableSortedMap.of());
        TimeSeries timeSeries4 = newTimeSeries("foo_bar4", ImmutableMap.of(timeStamp, Double.POSITIVE_INFINITY), ImmutableSortedMap.of());
        Response response = writeAdapterResource.write(newRequest(timeSeries1, timeSeries2, timeSeries3, timeSeries4));

        assertThat(response.getStatus(), equalTo(200));
        verify(mockPublisher).post(new DataPointEvent("foo_bar1", ImmutableSortedMap.of(), new DoubleDataPoint(timeStamp, 1.0)));
        verify(mockPublisher, never()).post(new DataPointEvent("foo_bar2", ImmutableSortedMap.of(), new DoubleDataPoint(timeStamp, Double.NaN)));
        verify(mockPublisher, never()).post(new DataPointEvent("foo_bar3", ImmutableSortedMap.of(), new DoubleDataPoint(timeStamp, Double.NEGATIVE_INFINITY)));
        verify(mockPublisher, never()).post(new DataPointEvent("foo_bar4", ImmutableSortedMap.of(), new DoubleDataPoint(timeStamp, Double.POSITIVE_INFINITY)));
        verifyInternalMetrics("kairosdb.prometheus.write-adapter.metrics-sent.count", "sent", 1.0);
        verifyInternalMetrics("kairosdb.prometheus.write-adapter.metrics-sent.count", "dropped", 3.0);

    }

    private void verifyInternalMetrics(String metricName, String status, double count)
            throws UnknownHostException
    {
        verify(mockPublisher).post(
                argThat(new DataPointEventMatcher(new DataPointEvent(metricName,
                        ImmutableSortedMap.of("host", getHostname(), "status", status),
                        new DoubleDataPoint(System.currentTimeMillis(), count)))));
    }

    private void verifyInternalExceptionMetric(String metricName, String exception)
            throws UnknownHostException
    {
        verify(mockPublisher).post(
                argThat(new DataPointEventMatcher(new DataPointEvent(metricName,
                        ImmutableSortedMap.of("host", getHostname(), "exception", exception),
                        new DoubleDataPoint(System.currentTimeMillis(), 1)))));
    }

    private String getHostname()
            throws UnknownHostException
    {
        return InetAddress.getLocalHost().getHostName();
    }

    private WriteRequest newRequest(TimeSeries... timeSeries)
    {
        return WriteRequest.newBuilder().addAllTimeseries(Arrays.asList(timeSeries)).build();
    }

    private TimeSeries newTimeSeries(String metricName, ImmutableMap<Long, Double> samples, ImmutableMap<String, String> labels)
    {
        TimeSeries.Builder timeSeriesBuilder = TimeSeries.newBuilder();

        timeSeriesBuilder.addLabels(Label.newBuilder().setName("__name__").setValue(metricName));

        for (String name : labels.keySet()) {
            Label.Builder labelBuilder = Label.newBuilder();
            labelBuilder.setName(name).setValue(labels.get(name));
            timeSeriesBuilder.addLabels(labelBuilder.build());
        }

        for (Long timestamp : samples.keySet()) {
            Sample.Builder sampleBuilder = Sample.newBuilder();
            sampleBuilder.setTimestamp(timestamp).setValue(samples.get(timestamp));
            timeSeriesBuilder.addSamples(sampleBuilder.build());
        }

        return timeSeriesBuilder.build();
    }

    private class DataPointEventMatcher implements ArgumentMatcher<DataPointEvent>
    {
        private DataPointEvent event;
        private String errorMessage;

        DataPointEventMatcher(DataPointEvent event)
        {
            this.event = event;
        }

        @Override
        public boolean matches(DataPointEvent dataPointEvent)
        {
            if (!event.getMetricName().equals(dataPointEvent.getMetricName()))
            {
                errorMessage = "Metric names don't match: " + event.getMetricName() + " != " + dataPointEvent.getMetricName();
                return false;
            }
            if (!event.getTags().equals(dataPointEvent.getTags()))
            {
                errorMessage = "Tags don't match: " + event.getTags() + " != " + dataPointEvent.getTags();
                return false;
            }
            if (event.getDataPoint().getDoubleValue() != dataPointEvent.getDataPoint().getDoubleValue())
            {
                errorMessage = "Data points don't match: " + event.getDataPoint().getDoubleValue() + " != " + dataPointEvent.getDataPoint().getDoubleValue();
                return false;
            }
            return true;
        }

        @Override
        public String toString()
        {
            if (errorMessage != null) {
                return errorMessage;
            }
            return "";
        }
    }
}