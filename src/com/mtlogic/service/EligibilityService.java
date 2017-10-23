package com.mtlogic.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.AlgorithmParameters;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mtlogic.x12.X12Base;
import com.mtlogic.x12.X12Message;
import com.mtlogic.x12.X12Segment;
import com.mtlogic.x12.exception.InvalidX12MessageException;
import com.mtlogic.MultipartFormDataUtility;
import com.mtlogic.business.Credentials;
import com.mtlogic.business.CryptResult;

public class EligibilityService {
	final Logger logger = LoggerFactory.getLogger(EligibilityService.class);

	public static final String STOCK_270 = "ISA*00*          *00*          *ZZ*15657851       *ZZ*15657851       *121017*1631*^*00401*000004486*1*P*|~GS*HS*15657851*EMDEON*20121017*1631*4486*X*004010X092A1~ST*270*1234*005010X279~BHT*0022*13*10001234*20060501*1319~HL*1**20*1~NM1*PR*2*ABC COMPANY*****PI*842610001~HL*2*1*21*1~NM1*1P*2******SV*2000035~HL*3*2*22*0~TRN*1*93175-012547*9877281234~NM1*IL*1*SMITH*ROBERT****MI*11122333301~DMG*D8*19430519~DTP*291*D8*20060501~EQ*30~SE*13*1234~GE*1*4486~IEA*1*000004486~";
	public static final String STOCK_270_DEPENDENT = "ISA*00*          *00*          *ZZ*15657851       *ZZ*15657851       *121017*1631*^*00401*000004486*1*P*|~GS*HS*15657851*EMDEON*20121017*1631*4486*X*004010X092A1~ST*270*1234*005010X279~BHT*0022*13*10001235*20060501*1320~HL*1**20*1~NM1*PR*2*ABC COMPANY*****PI*842610001~HL*2*1*21*1~NM1*1P*1******SV*0202034~HL*3*2*22*1~NM1*IL*1******MI*11122333301~HL*4*3*23*0~TRN*1*93175-012547*9877281234~NM1*03*1*JOHN*DOE~DMG*D8*19781014~DTP*291*D8*20060501~EQ*30~SE*15*1234~GE*1*4486~IEA*1*000004486~";

	public static final String THE_CONSULT = "15657851       ";
	public static final String EMDEON = "EMDEON         ";

	public static final String USER_AGENT = "Mozilla/5.0";
	
	public static final String PROD_DOMAIN = "http://api.mtlogic.com";
	public static final String QA_DOMAIN = "http://qa-api.mtlogic.com";
	public static final String LOCAL_DOMAIN = "http://localhost:8080";
	public static final String LOCAL_DOMAIN_QA = "http://localhost:8081";
	
	public static final String DEPLOYED_AUTHENTICATION = "authentication-manager-0.0.1-SNAPSHOT";
	public static final String LOCAL_AUTHENTICATION = "authentication-manager";
	public static final String DEPLOYED_MESSAGE = "storemessage-0.0.1-SNAPSHOT";
	public static final String LOCAL_MESSAGE = "storemessage";
	public static final String DEPLOYED_PATIENT = "storepatientmetadata-0.0.1-SNAPSHOT";
	public static final String LOCAL_PATIENT = "storepatientmetadata";
	public static final String REST_API = "rest/api";
	
	public static final String MESSAGE_ENDPOINT = "message";
	public static final String MESSAGE_CACHE_ENDPOINT = "message/cache";
	public static final String PATIENT_ENDPOINT = "storePatientMetaData";
	public static final String TOKEN_VALIDATE_ENDPOINT = "token/validate";
	
	public static final Boolean LOCAL_DEV = Boolean.TRUE;
	public static final Boolean QA_BUILD = Boolean.TRUE;

	private String originalEligibilityInquiry;
	private String originalReceiver;
	private String originalSubmitter;
	private X12Message eligibilityRequest;
	private X12Message eligibilityResponse;
	private int patientIdentifier;
	private String dataSourceName;
	private DataSource dataSource;

	public EligibilityService() {
		super();
		if (QA_BUILD) {
			dataSourceName = "eligibility-qa";
		} else {
			dataSourceName = "eligibility-prod";
		}
		dataSource = determineDataSource();
	}

	public String getPayerCode(X12Message message) {
		String payerCode = null;
		X12Segment loop2100a = message.getInterchangeControlList().get(0).getFunctionalGroupEnvelopes().get(0)
				.getTransactionSetEnvelopes().get(0).getSegments().get(2);
		payerCode = loop2100a.getElements()[9];
		return payerCode;
	}

	public void setPayerCode(X12Message message, String payerCode) {
		X12Segment loop2100a = message.getInterchangeControlList().get(0).getFunctionalGroupEnvelopes().get(0)
				.getTransactionSetEnvelopes().get(0).getSegments().get(2);
		loop2100a.getElements()[9] = payerCode;
	}

	public void replaceReceiverSender(X12Message message, String receiver, String submitter) {
		logger.info(">>>ENTERED replaceReceiverSender()");
		message.getInterchangeControlList().get(0).setReceiver(receiver);
		message.getInterchangeControlList().get(0).setSubmitter(submitter);
		logger.info("<<<EXITED replaceReceiverSender()");
	}

	public void updateMessageWithInternalPayerCode(X12Message message) {
		logger.info(">>>ENTERED updateMessageWithInternalPayerCode()");
		String payerCode = getPayerCode(message);
		String mtlogicPayerCode = lookupInternalPayerCodeAWS(payerCode);
		if (mtlogicPayerCode != null && !mtlogicPayerCode.isEmpty()) {
			setPayerCode(message, mtlogicPayerCode);
		}
		logger.info("<<<EXITED updateMessageWithInternalPayerCode()");
	}

	public String lookupInternalPayerCode(String payerCode) {
		logger.info(">>>ENTERED lookupInternalPayerCode()");

		String mtlogicPayerCode = null;

		try {
			URL url = new URL(
					"http://192.0.0.71/ClaimStatusServices_Test/api/ClaimStatus/GetOutboundPayerCodeFromInternalPayerCode/"
							+ payerCode);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");
			conn.setRequestProperty("Accept", "text/plain");

			if (conn.getResponseCode() != 200) {
				throw new RuntimeException("Failed : HTTP error code : " + conn.getResponseCode());
			}

			BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));

			String output;
			while ((output = br.readLine()) != null) {
				mtlogicPayerCode = output.replaceAll("\"", "");
			}

			conn.disconnect();
		} catch (MalformedURLException mfe) {
			mfe.printStackTrace();
			logger.error("ERROR!!! : " + mfe.getMessage());
		} catch (IOException ioe) {
			ioe.printStackTrace();
			logger.error("ERROR!!! : " + ioe.getMessage());
		} catch (Exception e) {
			e.printStackTrace();
			logger.error("ERROR!!! : " + e.getMessage());
		}

		logger.info("<<<EXITED lookupInternalPayerCode()");
		return mtlogicPayerCode;
	}

	public String lookupInternalPayerCodeAWS(String payerCode) {
		logger.info(">>>ENTERED lookupInternalPayerCodeAWS(" + payerCode + ")");
		Connection con = null;
		ResultSet rs = null;
		PreparedStatement preparedStatement = null;

		String selectMessageSQL = "select cscode from public.payercodexref where pcode = ?";

		String mtlogicPayerCode = null;
		try {
			con = dataSource.getConnection();

			preparedStatement = con.prepareStatement(selectMessageSQL);
			preparedStatement.setString(1, payerCode);

			rs = preparedStatement.executeQuery();

			if (rs.next()) {
				mtlogicPayerCode = rs.getString("cscode");
			} else {

			}
		} catch (SQLException e) {
			e.printStackTrace();
			logger.error("ERROR!!! : " + e.getMessage());
		} catch (Exception e) {
			e.printStackTrace();
			logger.error("ERROR!!! : " + e.getMessage());
		} finally {
			try {rs.close();} catch (Exception e) {};
			try {preparedStatement.close();} catch (Exception e) {};
			try {con.close();} catch (Exception e) {};
		}

		logger.info("<<<EXITED lookupInternalPayerCodeAWS(" + mtlogicPayerCode + ")");
		return mtlogicPayerCode;
	}

	public boolean checkPayerCodeExists(String payerCode) {
		logger.info(">>>ENTERED checkPayerCodeExists(" + payerCode + ")");
		Connection con = null;
		PreparedStatement preparedStatement = null;
		ResultSet rs = null;
		boolean isValid = false;

		String selectMessageSQL = "select * from public.payer_code where code = ?";

		try {
			con = dataSource.getConnection();

			preparedStatement = con.prepareStatement(selectMessageSQL);
			preparedStatement.setString(1, payerCode);

			rs = preparedStatement.executeQuery();

			if (rs.next()) {
				isValid = true;
			}
		} catch (SQLException e) {
			e.printStackTrace();
			logger.error("ERROR!!! : " + e.getMessage());
		} catch (Exception e) {
			e.printStackTrace();
			logger.error("ERROR!!! : " + e.getMessage());
		} finally {
			try {rs.close();} catch (Exception e) {};
			try {preparedStatement.close();} catch (Exception e) {};
			try {con.close();} catch (Exception e) {};
		}

		logger.info("<<<EXITED checkPayerCodeExists(" + isValid + ")");
		return isValid;
	}

	// public String postInquiryToEmdeon(String environmentCode, String
	// eligibilityInquiry) {
	public X12Message postInquiryToEmdeon(String environmentCode, String eligibilityInquiry) {
		logger.info(">>>ENTERED postInquiryToEmdeon()");
		String charset = "UTF-8";
		Credentials credentials = null;

		try {
			credentials = new Credentials(environmentCode);
			MultipartFormDataUtility multipart = new MultipartFormDataUtility(credentials.getUrl(), charset);

			String encodedMessage = Base64.getEncoder().encodeToString(eligibilityInquiry.getBytes());

			multipart.addHeaderField("User-Agent", "Mozilla/5.0");

			multipart.addFormField("wsUserID", credentials.getUser());
			multipart.addFormField("wsPassword", credentials.getPassword());

			multipart.addFormField("wsMessageType", "X12");
			multipart.addFormField("wsEncodedRequest", encodedMessage);

			List<String> response = multipart.finish();
			String x12ResponseString = response.get(0);
			if (x12ResponseString.contains("AHPROVIDER")) {
				x12ResponseString.replaceAll("AHPROVIDER", "");
			}

			try {
				this.setEligibilityResponse(new X12Message(x12ResponseString));
			} catch (InvalidX12MessageException e) {
				e.printStackTrace();
			}
		} catch (IOException ex) {
			System.err.println(ex);
			ex.printStackTrace();
		}

		logger.info("<<<EXITED postInquiryToEmdeon()");
		return this.getEligibilityResponse();
	}

	public String postInquiryToEchoService(String eligibilityInquiry) {
		logger.info(">>>ENTERED postInquiryToEchoService()");

		StringBuilder sb = new StringBuilder();
		try {
			URL url = new URL("http://claimstatus.us-east-1.elasticbeanstalk.com/api/test/echo");
			if (LOCAL_DEV) {
				url = new URL("http://localhost:8080/Claims/api/test/echo");
			} 
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "text/plain");

			OutputStream os = conn.getOutputStream();
			os.write(eligibilityInquiry.getBytes());
			os.flush();

			if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
				throw new RuntimeException("Failed : HTTP error code : " + conn.getResponseCode());
			}

			BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));

			String output;
			while ((output = br.readLine()) != null) {
				sb.append(output);
			}

			conn.disconnect();

		} catch (MalformedURLException mfe) {
			mfe.printStackTrace();
			logger.error("ERROR!!! : " + mfe.getMessage());
		} catch (IOException ioe) {
			ioe.printStackTrace();
			logger.error("ERROR!!! : " + ioe.getMessage());
		} catch (Exception e) {
			e.printStackTrace();
			logger.error("ERROR!!! : " + e.getMessage());
		}

		logger.info("<<<EXITED postInquiryToEchoService()");
		return sb.toString();
	}

	public X12Message getEligibilityRequest() {
		return eligibilityRequest;
	}

	public void setEligibilityRequest(X12Message message) {
		this.eligibilityRequest = message;
	}

	public String getOriginalEligibilityInquiry() {
		return originalEligibilityInquiry;
	}

	public void setOriginalEligibilityInquiry(String originalEligibilityInquiry) throws InvalidX12MessageException {
		this.originalEligibilityInquiry = originalEligibilityInquiry;
		eligibilityRequest = new X12Message(originalEligibilityInquiry);
		eligibilityRequest.validate();
		originalReceiver = eligibilityRequest.getInterchangeControlList().get(0).getReceiver();
		originalSubmitter = eligibilityRequest.getInterchangeControlList().get(0).getSubmitter();
	}

	public void setOriginalEligibilityInquiryFromIndividualFields(String npi, String subscriberFirstName, String subscriberLastName,
			String dateOfBirth, String payerCode, String payerName, String memberId, String dateOfService, String trace, 
			String dependentFirstName, String dependentLastName, String serviceTypeCode, String dependentFlag)
			throws InvalidX12MessageException {
		//if (dependentLastName == null || dependentLastName.isEmpty()) {
		if ("true".equalsIgnoreCase(dependentFlag)) {
			this.originalEligibilityInquiry = STOCK_270_DEPENDENT;
		} else {
			this.originalEligibilityInquiry = STOCK_270;
		}
		eligibilityRequest = new X12Message(originalEligibilityInquiry);
		// set specific data here
		eligibilityRequest.getInterchangeControlList().get(0).getIsaHeader().setVersion(X12Base.ANSI_5010);
		eligibilityRequest.getInterchangeControlList().get(0)
				.setImplementationConventionReference(X12Base.ANSI_5010_IMPLEMENTAION_REFERENCE);
		eligibilityRequest.getInterchangeControlList().get(0).setDate(getCurrentDate());
		eligibilityRequest.getInterchangeControlList().get(0).setTime(getCurrentTime());
		//if (dependentLastName == null || dependentLastName.isEmpty()) {
		if ("true".equalsIgnoreCase(dependentFlag)) {
			//eligibilityRequest.getInterchangeControlList().get(0).setSubscriberFirstName((subscriberFirstName == null) ? "" : subscriberFirstName);
			//eligibilityRequest.getInterchangeControlList().get(0).setSubscriberLastName((subscriberLastName == null) ? "" : subscriberLastName);
			eligibilityRequest.getInterchangeControlList().get(0).setDependentFirstName((subscriberFirstName == null) ? "" : subscriberFirstName);
			eligibilityRequest.getInterchangeControlList().get(0).setDependentLastName((subscriberLastName == null) ? "" : subscriberLastName);
		} else {
			//eligibilityRequest.getInterchangeControlList().get(0).setDependentFirstName((dependentFirstName == null) ? "" : dependentFirstName);
			//eligibilityRequest.getInterchangeControlList().get(0).setDependentLastName((dependentLastName == null) ? "" : dependentLastName);
			eligibilityRequest.getInterchangeControlList().get(0).setSubscriberFirstName((subscriberFirstName == null) ? "" : subscriberFirstName);
			eligibilityRequest.getInterchangeControlList().get(0).setSubscriberLastName((subscriberLastName == null) ? "" : subscriberLastName);
		}
		eligibilityRequest.getInterchangeControlList().get(0).setPatientDateOfBirth((dateOfBirth == null) ? "" : dateOfBirth);
		eligibilityRequest.getInterchangeControlList().get(0).setServiceTypeCode(serviceTypeCode);
		
		// translate eligibility payer code from payer code here
		// for wns test...
		// String ePayerCode = null;
		// if (payerCode.equalsIgnoreCase("NJMCR")) {
		// payerCode = "00431";
		// payerName = "Medicare (Part A & B)";
		// } else if (payerCode.equalsIgnoreCase("NJMCD")) {
		// payerCode = "AID19";
		// payerName = "New Jersey Medicaid";
		// } else if (payerCode.equalsIgnoreCase("SKNY0")) {
		// payerCode = "AID18";
		// payerName = "New York Medicaid";
		// } else if (payerCode.equalsIgnoreCase("NCMCD")) {
		// payerCode = "AID21";
		// payerName = "North Carolina Medicaid";
		// } else if (payerCode.equalsIgnoreCase("PAMCD")) {
		// payerCode = "AID29";
		// payerName = "Pennsylvania Medicaid";
		// }
		
		eligibilityRequest.getInterchangeControlList().get(0)
				.setInformationSourceName((payerName == null) ? "" : payerName);
		eligibilityRequest.getInterchangeControlList().get(0).setInformationSourceIdenitiferCode(payerCode);
		eligibilityRequest.getInterchangeControlList().get(0).setInformationReceiverLastName("AHPROVIDER");
		eligibilityRequest.getInterchangeControlList().get(0)
				.setPatientEligibilityDate((dateOfService == null) ? getCurrentDate() : dateOfService);
		eligibilityRequest.getInterchangeControlList().get(0).setSubscriberIdentifier(memberId);
		// If medicaid then set to "XX" - look at Perl script logic to see how
		// this is determined
		eligibilityRequest.getInterchangeControlList().get(0).setInformationReceiverIdentificationCodeQualifier("XX");
		// eligibilityRequest.getInterchangeControlList().get(0).setInformationReceiverIdentificationCodeQualifier("SV");
		eligibilityRequest.getInterchangeControlList().get(0).setInformationReceiverIdentificationNumber(npi);
		if (trace != null) {
			eligibilityRequest.getInterchangeControlList().get(0).setSubscriberTrace(trace);
		}
		eligibilityRequest.validate();
		originalReceiver = eligibilityRequest.getInterchangeControlList().get(0).getReceiver();
		originalSubmitter = eligibilityRequest.getInterchangeControlList().get(0).getSubmitter();
	}

	public void postMessage(int messageType, String message, String clientId, String userName, String npi,
			String subscriberId, String payerCode, String dateOfService, String dateOfBirth, 
			String serviceTypeCode, String firstName, String lastName, Boolean dependentFlag) {
		logger.info(">>>ENTERED postMessage()");

		try {
			URL url = new URL("http://localhost:8080/storemessage-0.0.1-SNAPSHOT/rest/api/message");
			if (LOCAL_DEV) {
				url = new URL("http://localhost:8080/storemessage/rest/api/message");
			}
			
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/json");

			String input = "{ \"dataSource\":\"" + dataSourceName + "\", \"messageType\":\"" + messageType
					+ "\", \"message\":\"" + message + "\", \"patientId\":\"" + patientIdentifier
					+ "\", \"clientId\":\"" + clientId + "\", \"userName\":\"" + userName + "\", \"npi\":\"" + npi
					+ "\", \"subscriberId\":\"" + subscriberId + "\", \"payorCode\":\"" + payerCode
					+ "\", \"dateOfBirth\":\"" + dateOfBirth + "\", \"dateOfService\":\"" + dateOfService
					+ "\", \"firstName\":\"" + firstName + "\", \"lastName\":\"" + lastName  + "\", \"dependent\":\"" + dependentFlag.toString()
					+ "\", \"serviceTypeCode\":\"" + serviceTypeCode + "\" }";

			OutputStream os = conn.getOutputStream();
			os.write(input.getBytes());
			os.flush();

			if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
				throw new RuntimeException("Failed : HTTP error code : " + conn.getResponseCode());
			}

			conn.disconnect();
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		logger.info("<<<EXITED postMessage()");
	}

	public void postPatientMetaData() {
		logger.info(">>>ENTERED postPatientMetaData()");

		try {
			URL url = new URL("http://localhost:8080/storepatientmetadata-0.0.1-SNAPSHOT/rest/api/storePatientMetaData");
			if (LOCAL_DEV) {
				url = new URL("http://localhost:8080/storepatientmetadata/rest/api/storePatientMetaData");
			}
			
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/json");

			String subscriberId = this.eligibilityRequest.getInterchangeControlList().get(0).getSubscriberIdentifier();
			String patientFirstName = this.eligibilityRequest.getInterchangeControlList().get(0).getPatientFirstName();
			String patientLastName = this.eligibilityRequest.getInterchangeControlList().get(0).getPatientLastName();
			String patientBirthDate = this.eligibilityRequest.getInterchangeControlList().get(0).getPatientBirthDate();
			String identificationCodeQualifier = this.eligibilityRequest.getInterchangeControlList().get(0)
					.getSubscriberIdentificationCodeQualifier();
			Boolean dependent = this.eligibilityRequest.getInterchangeControlList().get(0).isDependent();

			String input = "{ \"dataSource\":\"" + dataSourceName + "\", \"subscriberId\":\"" + subscriberId
					+ "\", \"firstName\":\"" + patientFirstName + "\", \"lastName\":\"" + patientLastName
					+ "\", \"dateOfBirth\":\"" + patientBirthDate + "\", \"qualifier\":\"" + identificationCodeQualifier
					+ "\", \"dependent\":\"" + dependent + "\" }";

			OutputStream os = conn.getOutputStream();
			os.write(input.getBytes());
			os.flush();

			if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
				throw new RuntimeException("Failed : HTTP error code : " + conn.getResponseCode());
			}

			BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));

			String output;
			while ((output = br.readLine()) != null) {
				patientIdentifier = Integer.parseInt(output);
			}
			conn.disconnect();
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		logger.info("<<<EXITED postPatientMetaData()");
	}

	public String retrieveMessageFromCache(String npi, String subscriberId, String payerCode, String dateOfBirth,
			String dateOfService, String serviceTypeCode, String firstName, String lastName, Boolean dependentFlag) {
		logger.info(">>>ENTERED retrieveMessageFromCache()");

		String url = "http://localhost:8080/storemessage-0.0.1-SNAPSHOT/rest/api/message/cache";
		if (LOCAL_DEV) {
			url = "http://localhost:8080/storemessage/rest/api/message/cache";
		}
		
		String input = "{ \"dataSource\":\"" + dataSourceName + "\", \"npi\":\"" + npi + "\", \"subscriberId\":\""
				+ subscriberId + "\", \"payorCode\":\"" + payerCode + "\", \"dateOfBirth\":\"" + dateOfBirth
				+ "\", \"firstName\":\"" + firstName + "\", \"lastName\":\"" + lastName  + "\", \"dependent\":\"" + dependentFlag.toString()
				+ "\", \"dateOfService\":\"" + dateOfService + "\", \"serviceTypeCode\":\"" + serviceTypeCode + "\" }";
		String responseMessage = null;

		try {

			Client client = ClientBuilder.newClient();
			WebTarget webTarget = client.target(url);
			webTarget = webTarget.queryParam("payload", URLEncoder.encode(input, "UTF-8"));
			Response response = webTarget.request().get();

			responseMessage = response.readEntity(String.class);

		} catch (Exception e) {
			e.printStackTrace();
		}

		logger.info("<<<EXITED retrieveMessageFromCache()");
		return responseMessage;
	}
	
	public String lookupServiceTypeCodes() {
		logger.info(">>>ENTERED lookupServiceTypeCodes()");
		Connection con = null;
		PreparedStatement preparedStatement = null;
		ResultSet rs = null;
		
		String selectMessageSQL = "SELECT key, value FROM public.lookup WHERE topic = '270' AND key like 'EQ01%' ORDER BY value";
		StringBuilder jsonStringBuilder = new StringBuilder();
		String key = "";
		String value = "";
		
		try {
			con = dataSource.getConnection();
						
			preparedStatement = con.prepareStatement(selectMessageSQL);
			
			rs = preparedStatement.executeQuery();

			jsonStringBuilder.append("{\"servicetypecodes\":[");
			while (rs.next()) {
				key = rs.getString("key");
				value = rs.getString("value");
				jsonStringBuilder.append("{\"servicetypecode\":\"");
				jsonStringBuilder.append(key.substring(4, key.length()));
				jsonStringBuilder.append("\",\"servicename\":\"");
				jsonStringBuilder.append(value);
				if (rs.isLast()) {
					jsonStringBuilder.append("\"}");
				} else {
					jsonStringBuilder.append("\"},");
				}
			}
			jsonStringBuilder.append("]}");
		} catch (SQLException e) {
			e.printStackTrace();
			logger.error("A SQLException occurred!!! : " + e.getMessage());
		} catch (Exception e) {
			e.printStackTrace();
			logger.error("ERROR!!! : " + e.getMessage());
		} finally {
			try{rs.close();}catch(Exception e){};
		    try{preparedStatement.close();}catch(Exception e){};
		    try{con.close();}catch(Exception e){};
		}
		logger.info("<<<EXITED lookupServiceTypeCodes()");
		//return jsonObject.toString();
		return jsonStringBuilder.toString();
	}

	public String verifyToken(String token) {
		logger.info(">>>ENTERED verifyToken()");

		String responseString = null;
		try {
			URL url = new URL("http://localhost:8080/authentication-manager-0.0.1-SNAPSHOT/rest/api/token/validate");
			if (LOCAL_DEV) {
				url = new URL("http://localhost:8080/authentication-manager/rest/api/token/validate");
			}
			
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/json");

			String input = "{ \"token\":\"" + token + "\" }";

			OutputStream os = conn.getOutputStream();
			os.write(input.getBytes());
			os.flush();

			if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
				throw new RuntimeException("Failed : HTTP error code : " + conn.getResponseCode());
			}

			BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
			responseString = br.readLine();

			conn.disconnect();
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		logger.info("<<<EXITED verifyToken()");
		return responseString;
	}

	public CryptResult encrypt(String plainText, SecretKey secret) throws Exception {
		logger.info(">>>ENTERED encrypt()");
		CryptResult cryptResult = new CryptResult();

		try {
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.ENCRYPT_MODE, secret);
			AlgorithmParameters params = cipher.getParameters();
			byte[] iv = params.getParameterSpec(IvParameterSpec.class).getIV();
			cryptResult.setIv(iv);
			byte[] cipherText = cipher.doFinal(plainText.getBytes("UTF-8"));

			Base64.Encoder encoder = Base64.getEncoder();
			cryptResult.setEncryptedText(encoder.encodeToString(cipherText));
		} catch (Exception e) {
			e.printStackTrace();
			throw new Exception(e);
		}

		logger.info("<<<EXITED encrypt()");
		return cryptResult;
	}

	public String decrypt(String encryptedText, SecretKey secret, byte[] iv) throws Exception {
		logger.info(">>>ENTERED decrypt()");
		String plainText = "";

		try {
			Base64.Decoder decoder = Base64.getDecoder();
			byte[] encryptedBytes = decoder.decode(encryptedText);

			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.DECRYPT_MODE, secret, new IvParameterSpec(iv));
			plainText = new String(cipher.doFinal(encryptedBytes), "UTF-8");
		} catch (Exception e) {
			e.printStackTrace();
			throw new Exception(e);
		}

		logger.info("<<<EXITED decrypt()");
		return plainText;
	}

	public SecretKey generateSecret(char[] password, byte[] salt)
			throws NoSuchAlgorithmException, InvalidKeySpecException {
		logger.info(">>>ENTERED generateSecret()");
		// hack for JCE Unlimited Strength due to bug in JDK
		// (https://bugs.openjdk.java.net/browse/JDK-8149417)
		Field field;
		try {
			field = Class.forName("javax.crypto.JceSecurity").getDeclaredField("isRestricted");

			field.setAccessible(true);

			Field modifiersField = Field.class.getDeclaredField("modifiers");
			modifiersField.setAccessible(true);
			modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

			field.set(null, false);
		} catch (NoSuchFieldException | SecurityException | ClassNotFoundException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}

		SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
		KeySpec spec = new PBEKeySpec(password, salt, 65536, 256);
		SecretKey tmp = factory.generateSecret(spec);
		SecretKey secret = new SecretKeySpec(tmp.getEncoded(), "AES");

		logger.info("<<<EXITED generateSecret()");
		return secret;
	}

	public void storeAPIStatistics(String targetName, Duration targetDuration, Duration apiDuration) {
		logger.info(">>>ENTERED storeAPIStatistics()");

		Connection con = null;
		PreparedStatement preparedStatement = null;
		String insertSQL = "insert into public.api_statistics (target_name, target_duration, api_duration, api_minus_target_duration, created_at) values(?, ?, ?, ?, ?)";

		try {
			con = dataSource.getConnection();
			preparedStatement = con.prepareStatement(insertSQL);
			preparedStatement.setString(1, targetName);
			preparedStatement.setString(2, targetDuration.toString());
			preparedStatement.setString(3, apiDuration.toString());
			preparedStatement.setString(4, (apiDuration.minus(targetDuration)).toString());
			long time = System.currentTimeMillis();
			Timestamp timestamp = new java.sql.Timestamp(time);
			preparedStatement.setTimestamp(5, timestamp);

			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
			logger.error("ERROR!!! : " + e.getMessage());
		} catch (Exception e) {
			e.printStackTrace();
			logger.error("ERROR!!! : " + e.getMessage());
		} finally {
			try {preparedStatement.close();} catch (Exception e) {};
			try {con.close();} catch (Exception e) {};
		}

		logger.info("<<<EXITED storeAPIStatistics()");
	}
	
	public String getOriginalReceiver() {
		return originalReceiver;
	}

	public void setOriginalReceiver(String originalReceiver) {
		this.originalReceiver = originalReceiver;
	}

	public String getOriginalSubmitter() {
		return originalSubmitter;
	}

	public void setOriginalSubmitter(String originalSubmitter) {
		this.originalSubmitter = originalSubmitter;
	}

	public X12Message getEligibilityResponse() {
		return eligibilityResponse;
	}

	public void setEligibilityResponse(X12Message eligibilityResponse) {
		this.eligibilityResponse = eligibilityResponse;
	}

	public void createEligibilityResponse(String eligibilityResponse) throws InvalidX12MessageException {
		this.eligibilityResponse = new X12Message(eligibilityResponse);
		this.eligibilityResponse.validate();
	}

	public int getPatientIdentifier() {
		return patientIdentifier;
	}

	public void setPatientIdentifier(int patientIdentifier) {
		this.patientIdentifier = patientIdentifier;
	}

	private String getCurrentDate() {
		Date date = new Date();
		return new SimpleDateFormat("yyyyMMdd").format(date);
	}

	private String getCurrentTime() {
		Date date = new Date();
		return new SimpleDateFormat("HHmm").format(date);
	}
	
	private DataSource determineDataSource() {
		DataSource ds = null;
		try {
			Context envContext = new InitialContext();
			Context initContext  = (Context)envContext.lookup("java:/comp/env");
			if (QA_BUILD) {
			    ds = (DataSource)initContext.lookup("jdbc/eligibility-qa");
			} else {
				ds = (DataSource)initContext.lookup("jdbc/eligibility-prod");
			}
		} catch (NamingException e) {
			e.printStackTrace();
			logger.error("ERROR!!! Could not locate datasource!: " + e.getMessage());
		}
		return ds;
	}
	
	public String postInquiryStraightThruToEmdeon(String environmentCode, String eligibilityInquiry) {
		logger.info(">>>ENTERED postInquiryToEmdeon()");
		String charset = "UTF-8";
		Credentials credentials = null;
		String x12ResponseString = null;

		try {
			credentials = new Credentials(environmentCode);
			MultipartFormDataUtility multipart = new MultipartFormDataUtility(credentials.getUrl(), charset);

			String encodedMessage = Base64.getEncoder().encodeToString(eligibilityInquiry.getBytes());

			multipart.addHeaderField("User-Agent", "Mozilla/5.0");

			multipart.addFormField("wsUserID", credentials.getUser());
			multipart.addFormField("wsPassword", credentials.getPassword());

			multipart.addFormField("wsMessageType", "X12");
			multipart.addFormField("wsEncodedRequest", encodedMessage);

			List<String> response = multipart.finish();
			x12ResponseString = response.get(0);
			if (x12ResponseString.contains("AHPROVIDER")) {
				x12ResponseString.replaceAll("AHPROVIDER", "");
			}

//			try {
//				this.setEligibilityResponse(new X12Message(x12ResponseString));
//			} catch (InvalidX12MessageException e) {
//				e.printStackTrace();
//			}
		} catch (IOException ex) {
			System.err.println(ex);
			ex.printStackTrace();
		}

		logger.info("<<<EXITED postInquiryToEmdeon()");
		return x12ResponseString;
	}
}
