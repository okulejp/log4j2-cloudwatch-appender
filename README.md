What?
====================
This is a custom appender for log4j2. When used with Java/Mule apps, This appender pushes all the application logs to specified CloudWatch.
This code is a fix and upgrade of the code found here : [https://www.javacodegeeks.com/2017/10/integrate-cloudwatch-logs-cloudhub-mule.html](https://www.javacodegeeks.com/2017/10/integrate-cloudwatch-logs-cloudhub-mule.html)
However, there was a small problem when moving the LogEvents from one que to CloudWatch. This is solved by making the LogEvent immutable:

```
loggingEventsQueue.offer(event.toImmutable());
```

Why?
====================

* When using MuleSoft CloudHub, you may want to store the logs somewhere other than in CloudHub.
* Send all log events to a specified CloudWatch instance 
* To make a backup copy for application logs.

How?
==========================
* Build this application using the following command.

```mvn clean install```

Use this dependency in your Java/Mule Applications

```
<dependency>
	<groupId>com.java.javacodegeeks.log4j2.cloudwatch</groupId>
	<artifactId>log4j2-cloudwatch-appender</artifactId>
	<version>1.0.0</version>
	<type>jar</type>
</dependency>
```

* Modify Application's log4j2.xml to add the below appender custom appender config.

```
<Appenders>
	<CLOUDW name="CloudW" logGroupName="<your CloudWatch group name>"
		logStreamName="<your Mule app name>-${sys:environment}"
		awsAccessKey="<your AWS access key>" 
		awsSecretKey="<your AWS secret key>"
		awsRegion="<your AWS region>" 
		endpoint="<your CloudWatch VPC Endpoint>" 
		messagesBatchSize="5"
		queueLength="100"
		retryCount="<your retry count when the error occurs (default 2)>"
		retrySleepMSec="<your sleep millisecond when the error occurs (default 5000)>"/>
		logsQuotasSizeCheck="<your logs quotas size check (default true)>"/>
		<PatternLayout
			pattern="%-5p %d [%t] %X{correlationId}%c: %m%n" /> 
	</CLOUDW>
</Appenders>
```
Add this java package in your top level log4j2 configuration element

```
<Configuration packages="com.java.javacodegeeks.log4j2.cloudwatch">
```

Add this custom appender to your Root logger in log4j2.xml.

```
<Root level="INFO">
    <AppenderRef ref="CloudW"/>
</Root>  
     
        (or)

<AsyncRoot level="INFO">
    <AppenderRef ref="CloudW" />
</AsyncRoot>
```

* Configure AWS credential

When the AWS credential by environment variables or configuration and credential file settings is defined, awsAccessKey, awsSecretKey, awsRegion is not required but when use endpoint, awsRegion is required.

* Implementation of log4j2-cloudwatch-appender against CloudWatch Logs quotas
  * If logsQuotasSizeCheck is true
    * When the log message size exceeds max event size 256KB, the log message is skipped. When the send total byte size of log messages exceeds batch max size 1MB, part of the log messages is sent and the rest is sent next regardless of messagesBatchSize.
  * If logsQuotasSizeCheck is false
    * When the log message size exceeds max event size 256KB, the exception occurred and no log output. When the send total byte size of log messages exceeds batch max size 1MB, the exception occurred and no log output.

* That's it!

When you run the project with this appender added with your AWS credentials, you should see your app log events flowing into the configured CloudWatch group/logStreamName.


## References
-  [CloudWatch Logs quotas](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/cloudwatch_limits_cwl.html).
-  [Amazon CloudWatch Logs removes Log Stream transaction quota and SequenceToken requirement](https://aws.amazon.com/about-aws/whats-new/2023/01/amazon-cloudwatch-logs-log-stream-transaction-quota-sequencetoken-requirement/?nc1=h_ls)
-  [Environment variables to configure the AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-envvars.html)
-  [Using the AWS SDK for Java (Working with AWS Credentials)](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html)
-  [AWS SDKs and Tools (AWS Region)](https://docs.aws.amazon.com/sdkref/latest/guide/feature-region.html)
