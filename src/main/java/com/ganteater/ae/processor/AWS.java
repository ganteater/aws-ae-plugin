package com.ganteater.ae.processor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.sling.commons.json.JSONArray;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.JSONObject;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.GetQueueAttributesResult;
import com.ganteater.ae.CommandException;
import com.ganteater.ae.processor.annotation.CommandExamples;
import com.ganteater.ae.util.xml.easyparser.Node;

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.GetQueryResultsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.GetQueryResultsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResultField;
import software.amazon.awssdk.services.cloudwatchlogs.model.StartQueryRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.StartQueryRequest.Builder;
import software.amazon.awssdk.services.cloudwatchlogs.model.StartQueryResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class AWS extends BaseProcessor {

	private Node initNode;

	public AWS(Processor aParent) {
		super(aParent);
	}

	@Override
	public void init(Processor aParent, Node action) throws CommandException {
		super.init(aParent, action);
		initNode = action;
	}

	@CommandExamples({
			"<LogsInsights name='type:property' logGroupName='type:string' region='type:string' startTime='type:ms' endTime='type:ms'> ... query ...</LogsInsights>" })
	public void runCommandLogsInsights(Node action) throws Exception {

		String name = attr(action, "name");
		String profile = attr(action, "profile");
		String regionAttr = attr(action, "region");
		Region region = (Region) Region.class.getDeclaredField(regionAttr).get(null);

		Long startTime = Long.parseLong(attr(action, "startTime"));
		Long endTime = Long.parseLong(attr(action, "endTime"));

		String[] logGroupName = StringUtils.split(attr(action, "logGroupName"), ",");
		String query = replaceProperties(action.getInnerText(), true);

		ProfileCredentialsProvider cprovider = ProfileCredentialsProvider.create(profile);
		CloudWatchLogsClient cloudWatchLogsClient = CloudWatchLogsClient.builder().region(region)
				.credentialsProvider(cprovider).build();

		Builder builder = StartQueryRequest.builder();

		StartQueryRequest startRequest = builder.logGroupNames(logGroupName).startTime(startTime).endTime(endTime)
				.queryString(query).build();

		StartQueryResponse startQuery = cloudWatchLogsClient.startQuery(startRequest);
		String queryId = startQuery.queryId();

		GetQueryResultsResponse getQueryResultsResponse = null;
		GetQueryResultsRequest request = GetQueryResultsRequest.builder().queryId(queryId).build();

		String status = null;
		do {
			getQueryResultsResponse = cloudWatchLogsClient.getQueryResults(request);
			status = getQueryResultsResponse.status().toString();
		} while (!"Complete".equals(status));

		List<List<ResultField>> results = getQueryResultsResponse.results();

		List<String> result = new ArrayList<>();
		for (List<ResultField> list : results) {
			JSONArray record = new JSONArray();
			list.forEach((rec) -> {
				JSONObject jsonObject = new JSONObject();
				try {
					jsonObject.put("field", rec.field());
					jsonObject.put("value", rec.value());
				} catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				record.put(jsonObject);
			});
			result.add(record.toString());
		}

		setVariableValue(name, result);
	}

	@CommandExamples({ "<InvokeLambda name='type:property' lambda='type:string' region='type:string' payload='type:string' />" })
	public void runCommandInvokeLambda(Node action) throws Exception {
		// https://docs.aws.amazon.com/console/singlesignon/user-portal/aws-accounts/command-line/get-credentials/option2

		String name = attr(action, "name");
		String profile = attr(action, "profile");
		String regionAttr = attr(action, "region");
		Regions region = (Regions) Regions.class.getDeclaredField(regionAttr).get(null);

		String functionName = attr(action, "lambda");

		String payload = attr(action, "payload");
		InvokeRequest invokeRequest = new InvokeRequest().withFunctionName(functionName).withPayload(payload);
		InvokeResult invokeResult = null;

		ProfileCredentialsProvider cprovider = ProfileCredentialsProvider.create(profile);
		AwsSessionCredentials resolveCredentials = (AwsSessionCredentials) cprovider.resolveCredentials();
		BasicSessionCredentials credentials = new BasicSessionCredentials(resolveCredentials.accessKeyId(),
				resolveCredentials.secretAccessKey(), resolveCredentials.sessionToken());
		AWSCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(credentials);
		AWSLambda awsLambda = AWSLambdaClientBuilder.standard().withCredentials(credentialsProvider).withRegion(region)
				.build();

		invokeResult = awsLambda.invoke(invokeRequest);
		JSONObject result = new JSONObject(invokeResult.toString());
		String ans = new String(invokeResult.getPayload().array(), StandardCharsets.UTF_8);
		result.put("Payload", ans);

		setVariableValue(name, result);
	}

	@CommandExamples({ "<S3DownloadFile name='type:property' bucket='type:string' key='type:string' />" })
	public void runCommandS3DownloadFile(Node action) throws Exception {
		String name = attr(action, "name");
		String profile = attr(action, "profile");

		String bucket = attr(action, "bucket");
		String key = attr(action, "key");
		String regionAttr = attr(action, "region");
		Region region = (Region) Region.class.getDeclaredField(regionAttr).get(null);

		ProfileCredentialsProvider cprovider = ProfileCredentialsProvider.create(profile);
		S3Client client = S3Client.builder().credentialsProvider(cprovider).region(region).build();

		GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
		String result;
		try {
			ResponseInputStream<GetObjectResponse> response = client.getObject(request);

			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

			byte[] buffer = new byte[4096];
			int bytesRead = -1;

			while ((bytesRead = response.read(buffer)) != -1) {
				outputStream.write(buffer, 0, bytesRead);
			}

			response.close();
			outputStream.close();

			result = outputStream.toString();
		} catch (NoSuchKeyException e) {
			result = null;
		}
		setVariableValue(name, result);
	}

	@CommandExamples({ "<S3UploadFile name='type:property' bucket='type:string' key='type:string' />",
			"<S3UploadFile name='type:property' bucket='type:string' key='type:string' profile='type:string' region='type:string' />" })
	public void runCommandS3UploadFile(Node action) throws Exception {
		String name = attr(action, "name");
		String profile = attr(action, "profile");

		String bucket = attr(action, "bucket");
		String key = attr(action, "key");
		String regionAttr = attr(action, "region");
		Region region = (Region) Region.class.getDeclaredField(regionAttr).get(null);

		ProfileCredentialsProvider cprovider = ProfileCredentialsProvider.create(profile);
		S3Client client = S3Client.builder().credentialsProvider(cprovider).region(region).build();

		PutObjectRequest request = PutObjectRequest.builder().bucket(bucket).key(key).build();
		byte[] bytes = getVariableString(name).getBytes();
		RequestBody fromFile = RequestBody.fromBytes(bytes);
		client.putObject(request, fromFile);
	}

	@Override
	public String attr(Node action, String name) {
		String value = super.attr(action, name);
		if (value == null) {
			value = super.attr(initNode, name);
		}
		return value;
	}

	@CommandExamples({ "<SQSSize name='type:property' queue='type:string' attribute='type:string' region='type:string' profile='type:string'/>" })
	public void runCommandSQSSize(Node action) throws Exception {
		String name = attr(action, "name");
		String attribute = attr(action, "attribute");
		String queue = attr(action, "queue");

		AmazonSQS sqs = getSqs(action);

		ArrayList<String> attributeNames = new ArrayList<>();
		attributeNames.add(attribute);
		GetQueueAttributesResult queueAttributes = sqs.getQueueAttributes(queue, attributeNames);
		String numberOfMessages = queueAttributes.getAttributes().get(attribute);
		setVariableValue(name, ObjectUtils.toString(numberOfMessages));
	}

	private AmazonSQS getSqs(Node action) {
		Regions region = getRegion(action);
		AWSCredentialsProvider credentialsProvider = getCredentiols(action);

		AmazonSQS sqs = AmazonSQSClientBuilder.standard().withCredentials(credentialsProvider).withRegion(region)
				.build();
		return sqs;
	}

	private Regions getRegion(Node action) {
		String regionAttr = attr(action, "region");
		Regions region = null;
		if (regionAttr != null) {
			try {
				region = (Regions) Regions.class.getDeclaredField(regionAttr).get(null);
			} catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
				throw new IllegalArgumentException(e);
			}
		}
		return region;
	}

	private AWSCredentialsProvider getCredentiols(Node action) {
		String profile = attr(action, "profile");

		ProfileCredentialsProvider cprovider = ProfileCredentialsProvider.create(profile);
		AwsSessionCredentials resolveCredentials = (AwsSessionCredentials) cprovider.resolveCredentials();
		BasicSessionCredentials credentials = new BasicSessionCredentials(resolveCredentials.accessKeyId(),
				resolveCredentials.secretAccessKey(), resolveCredentials.sessionToken());
		AWSCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(credentials);
		return credentialsProvider;
	}

}
