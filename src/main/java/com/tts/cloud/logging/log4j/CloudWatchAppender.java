package com.tts.cloud.logging.log4j;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.logs.AWSLogs;
import com.amazonaws.services.logs.AWSLogsClient;
import com.amazonaws.services.logs.AWSLogsClientBuilder;
import com.amazonaws.services.logs.model.*;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Layout;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.spi.LoggingEvent;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static com.tts.cloud.logging.log4j.Config.*;

public class CloudWatchAppender extends AppenderSkeleton {

    // FIXME don't do this static
    private static final String AWS_INSTANCE_ID = retrieveInstanceId();

    private static final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");

    // <OPTIONS>
    private String awsRegion;
    public void setAwsRegion(String awsRegion) {
        this.awsRegion = awsRegion;
    }

    private String awsLogGroup;
    public void setAwsLogGroup(String awsLogGroup) {
        this.awsLogGroup = awsLogGroup;
    }

    private String awsLogStreamPrefix;
    public void setAwsLogStreamPrefix(String awsLogStreamPrefix) {
        this.awsLogStreamPrefix = awsLogStreamPrefix;
    }

    private int awsLogStreamFlushPeriodInSeconds;
    private int flushPeriodMillis = AWS_LOG_STREAM_FLUSH_PERIOD_IN_SECONDS;
    public void setAwsLogStreamFlushPeriodInSeconds(int awsLogStreamFlushPeriodInSeconds) {
        this.awsLogStreamFlushPeriodInSeconds = awsLogStreamFlushPeriodInSeconds;
        this.flushPeriodMillis = awsLogStreamFlushPeriodInSeconds * 1000;
    }

    private String stackTraceFormat;
    public void setStackTrace(String stackTraceFormat) {
        this.stackTraceFormat = stackTraceFormat;
    }
    // </OPTIONS>

    private ThrowableRenderer throwableRenderer = ThrowableRenderers.NONE;

    private String logGroupName;
    private String logStreamPrefix;
    private String logStreamName;

    private AWSLogs awsLogsClient = null;

    private String sequenceTokenCache = null; // aws doc: "Every PutLogEvents request must include the sequenceToken obtained from the response of the previous request.

    private final BlockingQueue<InputLogEvent> queue = new LinkedBlockingQueue<InputLogEvent>(AWS_LOG_STREAM_MAX_QUEUE_DEPTH);
    private volatile boolean shutdown = false;
    private final Object monitor = new Object();
    private Thread deliveryThread;

    private long lastReportedTimestamp = -1;

    private volatile boolean queueFull = false;

    @Override
    public void setName(String name) {
        super.setName(name == null ? DEFAULT_LOG_APPENDER_NAME : name);
    }

    @Override
    public boolean requiresLayout() {
        System.out.println("-- requiresLayout");
        return true;
    }

    @Override
    public void setLayout(Layout layout) {
        System.out.println("-- setLayout");
        super.setLayout(layout == null ? new PatternLayout(PatternLayout.TTCC_CONVERSION_PATTERN) : layout);
    }

    @Override
    public void activateOptions() {

        System.out.format("-- activateOptions\n" +
                "\tlogGroup = %s\n" +
                "\tlogStreamPrefix = %s\n" +
                "\tlogStreamFlushPeriodInSeconds = %s\n" +
                "\tstackTraceFormat = %s\n\n",
                this.awsLogGroup,
                this.awsLogStreamPrefix,
                this.awsLogStreamFlushPeriodInSeconds,
                this.stackTraceFormat);

        // <::Constructor>

        // FIXME :: dependency inversion
        AWSLogsClientBuilder clientBuilder = AWSLogsClientBuilder.standard();
        activateRegion(clientBuilder);
        this.awsLogsClient = clientBuilder.build();

        activateThrowableRenderer();
        activateLogGroup();
        activateLogStreamPrefix();

        rotateLogStream();

        // </::Constructor>

        // <::start>
        debug("Starting cloudWatchAppender for: " + logGroupName + ":" + logStreamName);
        deliveryThread = new Thread(messageProcessor, "CloudWatchAppenderDeliveryThread");
        deliveryThread.start();
        // </::start>
    }

    private void activateRegion(AWSLogsClientBuilder clientBuilder) {

        Region region = null;

        try {
            region = Region.getRegion(Regions.fromName(this.awsRegion != null ? this.awsRegion : ""));
        } catch(IllegalArgumentException iae) {
            region = Regions.getCurrentRegion();
        }

        if(region == null) {
            region = Region.getRegion(Regions.DEFAULT_REGION);
        }

        clientBuilder.setRegion(region.getName());
    }

    private void activateThrowableRenderer() {
        ThrowableRenderers renderer = ThrowableRenderers.of(this.stackTraceFormat);
        this.throwableRenderer = renderer != null ? renderer : ThrowableRenderers.NONE;
    }

    private void activateLogGroup() {
        this.logGroupName = awsLogGroup == null ? DEFAULT_AWS_LOG_GROUP_NAME : awsLogGroup;
        createLogGroupIfNecessary(awsLogGroup);
    }

    private void activateLogStreamPrefix() {
        StringBuilder sb = new StringBuilder();
        sb.append(awsLogStreamPrefix == null ? ENV_LOG_STREAM_NAME : awsLogStreamPrefix);
        sb.append(sb.length() > 0 ? " " : "").append(AWS_INSTANCE_ID);
        this.logStreamPrefix = sb.toString();
    }

    private void rotateLogStream() {
        String name = generateLogStreamName();
        if(!name.equals(this.logStreamName)) {
            debug("Rotate to stream from \"" + this.logStreamName + "\" to \"" + name + "\"");
            this.sequenceTokenCache = createLogStreamIfNecessary(name);
            this.logStreamName = name;
        }
    }

    private String generateLogStreamName() {
        StringBuilder sb = new StringBuilder();
        sb.append(getTimeNow()).append("/").append(this.logStreamPrefix);
        return sb.toString();
    }

    private Runnable messageProcessor = new Runnable() {
        @Override
        public void run() {
            debug("Draining queue for " + logStreamPrefix + " stream every " + (flushPeriodMillis / 1000) + "s...");
            while (!shutdown) {
                try {
                    flush();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                if (!shutdown && queue.size() < AWS_DRAIN_LIMIT) {
                    try {
                        synchronized (monitor) {
                            monitor.wait(flushPeriodMillis);
                        }
                    } catch (InterruptedException ix) {
                        ix.printStackTrace();
                    }
                }
            }

            while (!queue.isEmpty()) {
                flush();
            }
        }
    };

    private void flush() {

        int drained;
        final List<InputLogEvent> logEvents = new ArrayList<InputLogEvent>(AWS_DRAIN_LIMIT);

        do {
            drained = queue.drainTo(logEvents, AWS_DRAIN_LIMIT);

            if (logEvents.isEmpty()) {
                break;
            }

            sortEventsByTimestamp(logEvents);
            skipPastEvents(logEvents);

            final PutLogEventsRequest putLogEventsRequest = new PutLogEventsRequest(logGroupName, logStreamName, logEvents);
            putLogEventsRequest.setSequenceToken(sequenceTokenCache);
            try {
                final PutLogEventsResult putLogEventsResult = awsLogsClient.putLogEvents(putLogEventsRequest); // 1 MB or 10000 messages AWS cap!
                sequenceTokenCache = putLogEventsResult.getNextSequenceToken();
            } catch (final DataAlreadyAcceptedException daae) {
                debug("DataAlreadyAcceptedException, will reset the token to the expected one");
                sequenceTokenCache = daae.getExpectedSequenceToken();
            } catch (final InvalidSequenceTokenException iste) {
                debug("InvalidSequenceTokenException, will reset the token to the expected one");
                sequenceTokenCache = iste.getExpectedSequenceToken();
            } catch (Exception e) {
                debug("Error writing logs");
                e.printStackTrace();
            }

            logEvents.clear();
        } while (drained >= AWS_DRAIN_LIMIT);

        rotateLogStream();
    }

    private void sortEventsByTimestamp(List<InputLogEvent> logEvents) {
        Collections.sort(logEvents, new Comparator<InputLogEvent>() {
            @Override
            public int compare(InputLogEvent o1, InputLogEvent o2) {
                return o1.getTimestamp().compareTo(o2.getTimestamp());
            }
        });
    }

    private void skipPastEvents(List<InputLogEvent> logEvents) {
        if (lastReportedTimestamp > 0) {
            //in the off chance that the new events start with older TS than the last sent event
            //reset their timestamps to the last timestamp until we reach an event with
            //higher timestamp
            for (InputLogEvent event : logEvents) {
                if (event.getTimestamp() < lastReportedTimestamp) {
                    event.setTimestamp(lastReportedTimestamp);
                }  else {
                    break;
                }
            }
        }
        lastReportedTimestamp = logEvents.get(logEvents.size() - 1).getTimestamp();
    }

    private void createLogGroupIfNecessary(String logGroupName) {

        final DescribeLogGroupsResult describeLogGroupsResult = awsLogsClient.describeLogGroups(new DescribeLogGroupsRequest().withLogGroupNamePrefix(logGroupName));
        boolean createLogGroup = true;
        if (describeLogGroupsResult != null && describeLogGroupsResult.getLogGroups() != null && !describeLogGroupsResult.getLogGroups().isEmpty()) {
            for (final LogGroup lg : describeLogGroupsResult.getLogGroups()) {
                if (logGroupName.equals(lg.getLogGroupName())) {
                    createLogGroup = false;
                    break;
                }
            }
        }

        if (createLogGroup) {
            debug("Creating logGroup: " + logGroupName);
            final CreateLogGroupRequest createLogGroupRequest = new CreateLogGroupRequest(logGroupName);
            awsLogsClient.createLogGroup(createLogGroupRequest);
        }
    }

    /**
     * Create log group ans log stream if needed.
     *
     * @param logStreamName the name of the stream
     * @return sequence token for the created stream
     */
    private String createLogStreamIfNecessary(String logStreamName) {

        String logSequenceToken = null;
        boolean createLogStream = true;
        final DescribeLogStreamsRequest describeLogStreamsRequest = new DescribeLogStreamsRequest(logGroupName).withLogStreamNamePrefix(logStreamName);
        final DescribeLogStreamsResult describeLogStreamsResult = awsLogsClient.describeLogStreams(describeLogStreamsRequest);
        if (describeLogStreamsResult != null && describeLogStreamsResult.getLogStreams() != null && !describeLogStreamsResult.getLogStreams().isEmpty()) {
            for (final LogStream ls : describeLogStreamsResult.getLogStreams()) {
                if (logStreamName.equals(ls.getLogStreamName())) {
                    createLogStream = false;
                    logSequenceToken = ls.getUploadSequenceToken();
                }
            }
        }

        if (createLogStream) {
            debug("Creating logStream: " + logStreamName);
            final CreateLogStreamRequest createLogStreamRequest = new CreateLogStreamRequest(logGroupName, logStreamName);
            awsLogsClient.createLogStream(createLogStreamRequest);
        }

        return logSequenceToken;
    }

    @Override
    protected void append(LoggingEvent event) {

        final long timestamp = event.getTimeStamp();
        final StringBuilder message = new StringBuilder().append(getLayout().format(event));
        this.throwableRenderer.renderToStringBuilder(message, event);

        System.err.print(message);

        final InputLogEvent awsLogEvent = new InputLogEvent();
        awsLogEvent.setTimestamp(timestamp);
        awsLogEvent.setMessage(message.toString());

        if (!queue.offer(awsLogEvent) && !queueFull) {
            debug("Log queue is full!");
            queueFull = true;
        } else if (queueFull) {
            queueFull = false;
        }
    }

    @Override
    public void close() {
        System.out.println("-- close");
        shutdown = true;
        if (deliveryThread != null) {
            synchronized (monitor) {
                monitor.notify();
            }
            try {
                deliveryThread.join(SHUTDOWN_TIMEOUT_MILLIS);
            } catch (InterruptedException ix) {
                ix.printStackTrace();
            }
        }
        if (queue.size() > 0) {
            flush();
        }
    }

    private String getTimeNow() { return simpleDateFormat.format(new Date()); }
    private void debug(final String s) { System.out.println(getTimeNow() + " CloudWatchAppender: " + s); }
}
