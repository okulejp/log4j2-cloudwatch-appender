package com.java.javacodegeeks.log4j2.cloudwatch;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.logs.AWSLogs;
import com.amazonaws.services.logs.model.CreateLogGroupRequest;
import com.amazonaws.services.logs.model.CreateLogStreamRequest;
import com.amazonaws.services.logs.model.CreateLogStreamResult;
import com.amazonaws.services.logs.model.DataAlreadyAcceptedException;
import com.amazonaws.services.logs.model.DescribeLogGroupsRequest;
import com.amazonaws.services.logs.model.DescribeLogStreamsRequest;
import com.amazonaws.services.logs.model.InputLogEvent;
import com.amazonaws.services.logs.model.InvalidSequenceTokenException;
import com.amazonaws.services.logs.model.PutLogEventsRequest;
import com.amazonaws.services.logs.model.PutLogEventsResult;
import com.amazonaws.client.builder.AwsClientBuilder;

@Plugin(name = "CLOUDW", category = "Core", elementType = "appender", printObject = true)
public class CloudwatchAppender extends AbstractAppender {

    /**
     *
     */
    private static final long serialVersionUID = 12321345L;

    /**
     * CloudWatch Logs quotas batch size 1MB({@value}Byte) (maximum). This quota can't be changed.
     */
    private static final int BATCH_MAX_SIZE_BYTE = 1048576;

    /**
     * The byte size of fixed message added to log messages
     */
    private static final int ADDITIONAL_MSG_BYTE_SIZE = 26;

    /**
     * CloudWatch Logs quotas event size 256KB({@value}Byte) (maximum). This quota can't be changed.
     */
    private static final int EVENT_MAX_SIZE_BYTE = 262144;

    private static Logger logger2 = LogManager.getLogger(CloudwatchAppender.class);

	 private final Boolean DEBUG_MODE = System.getProperty("log4j.debug") != null;

    /**
     * Used to make sure that on close() our daemon thread isn't also trying to sendMessage()s
     */
    private Object sendMessagesLock = new Object();

    /**
     * The queue used to buffer log entries
     */
    private LinkedBlockingQueue<LogEvent> loggingEventsQueue;

    /**
     * the AWS Cloudwatch Logs API client
     */
    private AWSLogs awsLogsClient;

    private AtomicReference<String> lastSequenceToken = new AtomicReference<>();

    /**
     * The AWS Cloudwatch Log group name
     */
    private String logGroupName;

    /**
     * The AWS Cloudwatch Log stream name
     */
    private String logStreamName;

    /**
     * The queue / buffer size
     */
    private int queueLength = 1024;

    private String awsAccessKey;
    private String awsAccessSecret;
    private String awsRegion;
    private String endpoint;

    /**
     * The count of retries when error occurs.
     */
    private int retryCount;

    /**
     * The number of milliseconds to sleep when error occurs.
     */
    private long retrySleepMSec;

    /**
     * The maximum number of log entries to send in one go to the AWS Cloudwatch Log service
     */
    private int messagesBatchSize = 128;

    /**
     * Whether to check the following CloudWatch Logs quotas.
     * <table border="1">
     * <tr>Event size:<td>256 KB (maximum). This quota can't be changed.</td></tr>
     * <tr>PutLogEvents:<td>The maximum batch size of a PutLogEvents request is 1MB.</td></tr>
     * </table>
     */
    private boolean logsQuotasSizeCheck;

    private AtomicBoolean cloudwatchAppenderInitialised = new AtomicBoolean(false);


    private CloudwatchAppender(final String name,
                               final Layout layout,
                               final Filter filter,
                               final boolean ignoreExceptions, String logGroupName,
                               String logStreamName,
                               final String awsAccessKey,
                               final String awsSecretKey,
                               final String awsRegion,
                               Integer queueLength,
                               Integer messagesBatchSize,
                               String endpoint,
                               int retryCount,
                               long retrySleepMSec,
                               boolean logsQuotasSizeCheck
    ) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
        this.logGroupName = logGroupName;
        this.logStreamName = logStreamName;
        this.awsAccessKey = awsAccessKey;
        this.awsAccessSecret = awsSecretKey;
        this.awsRegion = awsRegion;
        this.queueLength = queueLength;
        this.messagesBatchSize = messagesBatchSize;
        this.endpoint = endpoint;
        this.retryCount = retryCount;
        this.retrySleepMSec = retrySleepMSec;
        this.logsQuotasSizeCheck = logsQuotasSizeCheck;
        this.activateOptions();
    }

    @Override
    public void append(LogEvent event) {
        if (cloudwatchAppenderInitialised.get()) {
            if (!loggingEventsQueue.offer(event.toImmutable())) {
                if (DEBUG_MODE) {
                    System.err.println("Cloudwatch Logs for LogGroupName: " + logGroupName + " and LogStreamName: " + logStreamName + " not output because queue is full.");
                }
            }
        } else {
            // just do nothing
        }
    }

    public void activateOptions() {
        if (isBlank(logGroupName) || isBlank(logStreamName)) {
            logger2.error("Could not initialise CloudwatchAppender because either or both LogGroupName(" + logGroupName + ") and LogStreamName(" + logStreamName + ") are null or empty");
            this.stop();
        } else {
            //Credentials management could be customized
            com.amazonaws.services.logs.AWSLogsClientBuilder clientBuilder = com.amazonaws.services.logs.AWSLogsClientBuilder.standard();
            if (!isBlank(this.awsAccessKey) && !isBlank(this.awsAccessSecret)) {
                clientBuilder.setCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(this.awsAccessKey, this.awsAccessSecret)));
            }
            if (this.endpoint != null) {
                clientBuilder.withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(this.endpoint, this.awsRegion));
            } else {
                if (!isBlank(awsRegion)) {
                    clientBuilder.withRegion(Regions.fromName(awsRegion));
                }
            }
            this.awsLogsClient = clientBuilder.build();
            loggingEventsQueue = new LinkedBlockingQueue<>(queueLength);
            try {
                initializeCloudwatchResources();
                initCloudwatchDaemon();
                cloudwatchAppenderInitialised.set(true);
            } catch (Exception e) {
                logger2.error("Could not initialise Cloudwatch Logs for LogGroupName: " + logGroupName + " and LogStreamName: " + logStreamName, e);
                if (DEBUG_MODE) {
                    System.err.println("Could not initialise Cloudwatch Logs for LogGroupName: " + logGroupName + " and LogStreamName: " + logStreamName);
                    e.printStackTrace();
                }
            }
        }
    }

    private void initCloudwatchDaemon() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    if (loggingEventsQueue.size() > 0) {
                        sendMessages();
                    }
                    Thread.currentThread().sleep(20L);
                } catch (InterruptedException e) {
                    if (DEBUG_MODE) {
                        e.printStackTrace();
                    }
                }
            }
        });
        t.setName("CloudwatchThread");
        t.setDaemon(true);
        t.start();
    }

    private void putLogEvents(List<InputLogEvent> logEvents, int retryCount) throws InterruptedException {
        try {
            PutLogEventsRequest putLogEventsRequest = new PutLogEventsRequest(
                    logGroupName,
                    logStreamName,
                    logEvents);
            putLogEventsRequest.setSequenceToken(lastSequenceToken.get());
            PutLogEventsResult result = awsLogsClient.putLogEvents(putLogEventsRequest);
            lastSequenceToken.set(result.getNextSequenceToken());
        } catch (AmazonServiceException exception) {
            boolean isSleep = false;
            if (exception instanceof InvalidSequenceTokenException) {
                String sequenceToken = ((InvalidSequenceTokenException) exception)
                        .getExpectedSequenceToken();
                lastSequenceToken.set(sequenceToken);
            } else if (exception instanceof DataAlreadyAcceptedException) {
                String sequenceToken = ((DataAlreadyAcceptedException) exception)
                        .getExpectedSequenceToken();
                lastSequenceToken.set(sequenceToken);
            } else {
                isSleep = true;
            }
            if (retryCount >= 1) {
                if (DEBUG_MODE) {
                    System.err.println("error retryCount:" + retryCount
                            + "  message:" + exception.getErrorMessage()
                            + "  class:" + exception.getClass().getName());
                    exception.printStackTrace();
                }
                if (isSleep) {
                    Thread.currentThread().sleep(retrySleepMSec);
                }
                putLogEvents(logEvents, retryCount - 1);
            } else {
                throw exception;
            }
        }
    }

    private void sendMessages() {
        synchronized (sendMessagesLock) {
            LogEvent polledLoggingEvent = null;
            final Layout layout = getLayout();
            List<InputLogEvent> inputLogEvents = new ArrayList<>();

            try {
                int totalSizeByte = 0;
                while (inputLogEvents.size() < messagesBatchSize && (polledLoggingEvent = loggingEventsQueue.peek()) != null) {
                    String message = layout == null ?
                            polledLoggingEvent.getMessage().getFormattedMessage():
                            new String(layout.toByteArray(polledLoggingEvent), StandardCharsets.UTF_8);
                    if (logsQuotasSizeCheck) {
                        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
                        if (messageBytes.length > EVENT_MAX_SIZE_BYTE - ADDITIONAL_MSG_BYTE_SIZE) {
                            if (DEBUG_MODE) {
                                System.err.println("The log message size exceeds CloudWatch Logs quotas event max size.");
                            }
                            loggingEventsQueue.poll();
                            continue;
                        }
                        totalSizeByte += messageBytes.length + ADDITIONAL_MSG_BYTE_SIZE;
                        if (totalSizeByte > BATCH_MAX_SIZE_BYTE) {
                            break;
                        }
                    }
                    InputLogEvent inputLogEvent = new InputLogEvent().withTimestamp(polledLoggingEvent.getTimeMillis());
                    inputLogEvent.setMessage(message);
                    inputLogEvents.add(inputLogEvent);
                    loggingEventsQueue.poll();
                }
                if (inputLogEvents.isEmpty()) {
                    return;
                }
                inputLogEvents = inputLogEvents.stream().sorted(comparing(InputLogEvent::getTimestamp)).collect(toList());
                putLogEvents(inputLogEvents, retryCount);
            } catch (Exception e) {
                if (DEBUG_MODE) {
					logger2.error(" error inserting cloudwatch:", e);
					e.printStackTrace();
                }
            }
        }
    }

    private void initializeCloudwatchResources() {

        DescribeLogGroupsRequest describeLogGroupsRequest = new DescribeLogGroupsRequest();
        describeLogGroupsRequest.setLogGroupNamePrefix(logGroupName);

        Optional logGroupOptional = awsLogsClient
                .describeLogGroups(describeLogGroupsRequest)
                .getLogGroups()
                .stream()
                .filter(logGroup -> logGroup.getLogGroupName().equals(logGroupName))
                .findFirst();

        if (!logGroupOptional.isPresent()) {
            CreateLogGroupRequest createLogGroupRequest = new CreateLogGroupRequest().withLogGroupName(logGroupName);
            awsLogsClient.createLogGroup(createLogGroupRequest);
        }

        DescribeLogStreamsRequest describeLogStreamsRequest = new DescribeLogStreamsRequest().withLogGroupName(logGroupName).withLogStreamNamePrefix(logStreamName);

        Optional logStreamOptional = awsLogsClient
                .describeLogStreams(describeLogStreamsRequest)
                .getLogStreams()
                .stream()
                .filter(logStream -> logStream.getLogStreamName().equals(logStreamName))
                .findFirst();
        if (!logStreamOptional.isPresent()) {
            CreateLogStreamRequest createLogStreamRequest = new CreateLogStreamRequest().withLogGroupName(logGroupName).withLogStreamName(logStreamName);
            CreateLogStreamResult o = awsLogsClient.createLogStream(createLogStreamRequest);
        }

    }

    private boolean isBlank(String string) {
        return null == string || string.trim().length() == 0;
    }

    protected String getSimpleStacktraceAsString(final Throwable thrown) {
        final StringBuilder stackTraceBuilder = new StringBuilder();
        for (StackTraceElement stackTraceElement : thrown.getStackTrace()) {
            new Formatter(stackTraceBuilder).format("%s.%s(%s:%d)%n",
                    stackTraceElement.getClassName(),
                    stackTraceElement.getMethodName(),
                    stackTraceElement.getFileName(),
                    stackTraceElement.getLineNumber());
        }
        return stackTraceBuilder.toString();
    }

    @Override
    public void start() {
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        while (loggingEventsQueue != null && !loggingEventsQueue.isEmpty()) {
            this.sendMessages();
        }
    }

    @Override
    public String toString() {
        return CloudwatchAppender.class.getSimpleName() + "{"
                + "name=" + getName() + " loggroupName=" + logGroupName
                + " logstreamName=" + logStreamName;

    }

    @PluginFactory
    @SuppressWarnings("unused")
    public static CloudwatchAppender createCloudWatchAppender(
            @PluginAttribute(value = "queueLength") Integer queueLength,
            @PluginElement("Layout") Layout layout,
            @PluginAttribute(value = "logGroupName") String logGroupName,
            @PluginAttribute(value = "logStreamName") String logStreamName,
            @PluginAttribute(value = "awsAccessKey") String awsAccessKey,
            @PluginAttribute(value = "awsSecretKey") String awsSecretKey,
            @PluginAttribute(value = "awsRegion") String awsRegion,
            @PluginAttribute(value = "name") String name,
            @PluginAttribute(value = "ignoreExceptions", defaultBoolean = false) Boolean ignoreExceptions,
            @PluginAttribute(value = "messagesBatchSize") Integer messagesBatchSize,
            @PluginAttribute(value = "endpoint") String endpoint,
            @PluginAttribute(value = "retryCount", defaultInt = 2) Integer retryCount,
            @PluginAttribute(value = "retrySleepMSec", defaultLong = 5000) Long retrySleepMSec,
            @PluginAttribute(value = "logsQuotasSizeCheck", defaultBoolean = false) Boolean logsQuotasSizeCheck
    ) {
        return new CloudwatchAppender(name, layout, null, ignoreExceptions, logGroupName, logStreamName,
                awsAccessKey, awsSecretKey, awsRegion, queueLength, messagesBatchSize, endpoint,
                retryCount, retrySleepMSec, logsQuotasSizeCheck);
    }
}