package com.mtlogic;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.http.HttpStatus;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mtlogic.x12.X12Message;
import com.mtlogic.x12.exception.InvalidX12MessageException;
import com.mtlogic.service.ASCBatchService;
import com.mtlogic.service.BatchService;
import com.mtlogic.service.DelimitedBatchService;
import com.mtlogic.service.EligibilityService;

@Path("/api")
public class Eligibility {
	final Logger logger = LoggerFactory.getLogger(Eligibility.class);
	
	public static final int CLIENT_270 = 1;
	public static final int MTLOGIC_270 = 2;
	public static final int PAYOR_271 = 3;
	public static final int MTLOGIC_271 = 4;
	public static final String UTF8_BOM = "\uFEFF";
	
	@Path("/form/eligibility")
	@POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.TEXT_PLAIN)
    public Response createAndTransmitEligibilityRequestFromFormData(@Context HttpHeaders headers, 
    		                      @FormParam("firstname") String firstName,
                                  @FormParam("lastname") String lastName,
                                  @FormParam("npi") String npi,
                                  @FormParam("dob") String dateOfBirth,
                                  @FormParam("payercode") String payerCode,
                                  @FormParam("memberid") String memberId,
                                  @FormParam("dos") String dateOfService,
                                  @FormParam("trace") String trace,
                                  @FormParam("payername") String payerName,
                                  @FormParam("dependentfirstname") String dependentFirstName,
                                  @FormParam("dependentlastname") String dependentLastName,
                                  @FormParam("servicetypecode") String serviceTypeCode,
                                  @FormParam("dependent") String dependentFlag)
	{
		Instant apiStart = Instant.now();
		logger.info(">>>ENTERED createAndTransmitEligibilityRequestFromFormData()");
		Response response = null;
		EligibilityService eligibilityService = null;
		int responseCode = HttpStatus.SC_ACCEPTED;
		String environmentCode = null;
		String token = null;
		String responseEncoding = null;
		X12Message responseX12Message = null;
		Duration targetDuration = null;
		Duration apiDuration = null;
		String clientId = null;
		String userName = null;
		String cachedResponse = null;
		Boolean authorized = Boolean.FALSE;
		if (serviceTypeCode == null || serviceTypeCode.isEmpty()){
			serviceTypeCode = "30";
		}
		Boolean isDependent = Boolean.FALSE;
		if (dependentFlag != null && (dependentFlag.startsWith("T") || dependentFlag.startsWith("t"))) {
			isDependent = Boolean.TRUE;
		}
		
		try {
			responseEncoding = (headers.getRequestHeader("ResponseEncoding")!=null)?headers.getRequestHeader("ResponseEncoding").get(0):"J";
			environmentCode = (headers.getRequestHeader("EnvironmentCode")!=null)?headers.getRequestHeader("environmentcode").get(0):"T";
			
			try {
				token = headers.getRequestHeader("apitoken").get(0);
				eligibilityService = new EligibilityService();
				String jsonString = eligibilityService.verifyToken(token);
				if (jsonString != null) {
					try {
						JSONObject jsonObject = new JSONObject(jsonString);
						authorized = jsonObject.getBoolean("verified");
						clientId = jsonObject.getString("clientid");
						userName = jsonObject.getString("username");
					} catch (Exception e) {
						logger.error("Could not parse JSONWebToken! - " + e.getMessage());
						e.printStackTrace();
						authorized = Boolean.FALSE;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				authorized = Boolean.FALSE;
			}
			if (!authorized) {
				response = Response.status(HttpStatus.SC_UNAUTHORIZED).entity("Access denied!").build();
			} else {
				eligibilityService.setOriginalEligibilityInquiryFromIndividualFields(npi, firstName, lastName, dateOfBirth, payerCode, payerName, memberId.toUpperCase(), dateOfService, trace, dependentFirstName, dependentLastName, serviceTypeCode, dependentFlag);
				eligibilityService.postPatientMetaData();
				eligibilityService.postMessage(CLIENT_270, eligibilityService.getEligibilityRequest().toString(), clientId, userName, npi, memberId, payerCode, dateOfService, dateOfBirth, serviceTypeCode, firstName, lastName, isDependent);
				
				cachedResponse = eligibilityService.retrieveMessageFromCache(npi, memberId, payerCode, dateOfBirth, dateOfService, serviceTypeCode, firstName, lastName, isDependent);
			}
		} catch (InvalidX12MessageException ixme) {
			logger.error("Could not parse incoming message! - " + ixme.getMessage());
			ixme.printStackTrace();
			response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity(ixme.getMessage()).build();
		} catch (Exception e) {
			logger.error("Message could not be processed: " + e.getMessage());
			e.printStackTrace();
			response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Message could not be processed: " + e.getMessage()).build();
		}
		
		if (response == null && (cachedResponse == null || cachedResponse.isEmpty())) {
			try {
				eligibilityService.replaceReceiverSender(eligibilityService.getEligibilityRequest(), EligibilityService.EMDEON, EligibilityService.THE_CONSULT);
				eligibilityService.updateMessageWithInternalPayerCode(eligibilityService.getEligibilityRequest());
				eligibilityService.postMessage(MTLOGIC_270, eligibilityService.getEligibilityRequest().toString(), clientId, userName, npi, memberId, payerCode, dateOfService, dateOfBirth, serviceTypeCode, firstName, lastName, isDependent);
				Instant emdeonStart = Instant.now();
				responseX12Message = eligibilityService.postInquiryToEmdeon(environmentCode, eligibilityService.getEligibilityRequest().toString());
				Instant emdeonEnd = Instant.now();
				targetDuration = Duration.between(emdeonStart,  emdeonEnd);
				System.out.println("The call to Change took: <<<" + targetDuration + ">>>");
				logger.info("The call to Change took: <<<" + targetDuration + ">>>");
				
				if (responseX12Message != null) {			
					eligibilityService.postMessage(PAYOR_271, responseX12Message.toString(), clientId, userName, npi, memberId, payerCode, dateOfService, dateOfBirth, serviceTypeCode, firstName, lastName, isDependent);
					eligibilityService.replaceReceiverSender(responseX12Message, eligibilityService.getOriginalSubmitter(), EligibilityService.THE_CONSULT);
					eligibilityService.postMessage(MTLOGIC_271, responseX12Message.toString(), clientId, userName, npi, memberId, payerCode, dateOfService, dateOfBirth, serviceTypeCode, firstName, lastName, isDependent);
				} else {
					logger.error("Could not parse response message!");
					response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Could not parse response message!").build();
				}
			} catch (Exception e) {
				logger.error("Could not connect to Emdeon: " + e.getMessage());
				e.printStackTrace();
				response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Could not connect to Emdeon : " + e.getMessage()).build();
			}
		
			if ("J".equals(responseEncoding)) {
			    response = Response.status(responseCode).entity(responseX12Message.toJSONString(Boolean.TRUE)).build();
			} else {
			    response = Response.status(responseCode).entity(responseX12Message.toString()).build();
			}
			logger.info("<<<EXITED createAndTransmitEligibilityRequestFromFormData()");
			Instant apiEnd = Instant.now();
			apiDuration = Duration.between(apiStart,  apiEnd);
			System.out.println("The total API time was: <<<" + apiDuration + ">>>");
			logger.info("The total API time was: <<<" + apiDuration + ">>>");
			eligibilityService.storeAPIStatistics("CHANGE", targetDuration, apiDuration);
		} else if (authorized) {
			try {
				responseX12Message = new X12Message(cachedResponse);
			} catch (InvalidX12MessageException e) {
				logger.error("Could not parse cached response!" + e.getLocalizedMessage());
				e.printStackTrace();
				response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Could not process cached response : " + e.getMessage()).build();
			} 
			if (response == null) {
				try {
					eligibilityService.postMessage(MTLOGIC_271, cachedResponse, clientId, userName, npi, memberId, payerCode, dateOfService, dateOfBirth, serviceTypeCode, firstName, lastName, isDependent);
				} catch (Exception e) {
					logger.error("Cached response message could not be persisted: " + e.getMessage());
					e.printStackTrace();
				}
				if ("J".equals(responseEncoding)) {
				    response = Response.status(responseCode).entity(responseX12Message.toJSONString(Boolean.TRUE)).build();
				} else {
				    response = Response.status(responseCode).entity(responseX12Message.toString()).build();
				}
			}
		}
		
		return response;
    }
	
	@Path("/json/eligibility")
	@POST
    @Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.TEXT_PLAIN)
    public Response createAndTransmitEligibilityRequestFromJsonData(@Context HttpHeaders headers, String jsonData) 
	{
		Instant apiStart = Instant.now();
		logger.info(">>>ENTERED createAndTransmitEligibilityRequestFromJsonData()");
		Response response = null;
		EligibilityService eligibilityService = null;
		String responseText = null;
		Duration targetDuration = null;
		Duration apiDuration = null;
		int responseCode = HttpStatus.SC_ACCEPTED;
		String environmentCode = null;
		String npi = null;
		String subscriberFirstName = null;
		String subscriberLastName = null;
		String dateOfBirth = null;
		String dateOfService = null;
		String payerCode = null;
		String provider = null;
		String memberId = null;
		String trace = null;
		String token = null;
		String clientId = null;
		String userName = null;
		String dependentFirstName = null;
		String dependentLastName = null;
		String serviceTypeCode = null;
		String dependentFlag = null;
		String patientFirstName = null;
		String patientLastName = null;
		Boolean isDependent = Boolean.FALSE;
		
		try {
			environmentCode = (headers.getRequestHeader("EnvironmentCode")!=null)?headers.getRequestHeader("EnvironmentCode").get(0):"T";
			boolean authorized = false;
			try {
				token = headers.getRequestHeader("APIToken").get(0);
				eligibilityService = new EligibilityService();
				String jsonString = eligibilityService.verifyToken(token);
				if (jsonString != null) {
					try {
						JSONObject jsonObject = new JSONObject(jsonString);
						authorized = jsonObject.getBoolean("verified");
						clientId = jsonObject.getString("clientid");
						userName = jsonObject.getString("username");
					} catch (Exception e) {
						logger.error("Could not parse JSONWebToken! - " + e.getMessage());
						e.printStackTrace();
						authorized = false;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				authorized = false;
			}
			if (!authorized) {
				response = Response.status(HttpStatus.SC_UNAUTHORIZED).entity("Access denied!").build();
			} else {
				final JSONObject obj = new JSONObject(jsonData);
				
				try {
					JSONObject requestsObject = obj.getJSONObject("requests");
					JSONObject subscriberObject = requestsObject.getJSONObject("subscriber");
					JSONObject dependentObject = requestsObject.optJSONObject("dependent");
					if (requestsObject.has("tracenumber")) {
						trace = requestsObject.getString("tracenumber");
					} else {
						trace = "";
					}
					npi = requestsObject.getString("npi");
					dateOfService = requestsObject.getString("dateofservice");
					payerCode = requestsObject.getString("payercode");
					dependentFlag = requestsObject.getString("dependent");
					if (dependentFlag != null && (dependentFlag.startsWith("T") || dependentFlag.startsWith("t"))) {
						isDependent = Boolean.TRUE;
					}
					dateOfBirth = subscriberObject.optString("dateofbirth");
					memberId = subscriberObject.getString("memberid");
					if (subscriberObject.has("firstname")) {
						subscriberFirstName = subscriberObject.getString("firstname");
					} else {
						subscriberFirstName = "";
					}
					if (subscriberObject.has("lastname")) {
						subscriberLastName = subscriberObject.getString("lastname");
					} else {
						subscriberLastName = "";
					}
					serviceTypeCode = requestsObject.optString("servicetypecode");
					if (serviceTypeCode == null || serviceTypeCode.isEmpty()) {
						serviceTypeCode = "30";
					}
					if (dependentObject != null) {
						if (dependentObject.has("firstname")) {
							dependentFirstName = dependentObject.getString("firstname");
						} else {
							subscriberFirstName = "";
						}
						if (dependentObject.has("lastname")) {
							dependentLastName = dependentObject.getString("lastname");
						} else {
							dependentLastName = "";
						}
						dateOfBirth = dependentObject.optString("dateofbirth");
					}
				}
				catch (Exception e) {
					logger.error("Input JSON could not be processed: " + e.getMessage());
					e.printStackTrace();
					response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Input JSON could not be processed: " + e.getMessage()).build();
				}
	
				eligibilityService.setOriginalEligibilityInquiryFromIndividualFields(npi, subscriberFirstName, subscriberLastName, dateOfBirth, payerCode, provider, memberId, dateOfService, trace, dependentFirstName, dependentLastName, serviceTypeCode, dependentFlag);
				patientFirstName = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getPatientFirstName();
				patientLastName = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getPatientLastName();
				eligibilityService.postPatientMetaData();
				eligibilityService.postMessage(CLIENT_270, eligibilityService.getEligibilityRequest().toString(), clientId, userName, npi, memberId, payerCode, dateOfService, dateOfBirth, serviceTypeCode, patientFirstName, patientLastName, isDependent);
				eligibilityService.replaceReceiverSender(eligibilityService.getEligibilityRequest(), EligibilityService.EMDEON, EligibilityService.THE_CONSULT);
				eligibilityService.updateMessageWithInternalPayerCode(eligibilityService.getEligibilityRequest());
				eligibilityService.postMessage(MTLOGIC_270, eligibilityService.getEligibilityRequest().toString(), clientId, userName, npi, memberId, payerCode, dateOfService, dateOfBirth, serviceTypeCode, patientFirstName, patientLastName, isDependent);
			}
		} catch (InvalidX12MessageException ixme) {
			logger.error("Could not parse incoming message! - " + ixme.getMessage());
			ixme.printStackTrace();
			response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity(ixme.getMessage()).build();
		} catch (Exception e) {
			logger.error("Message could not be processed: " + e.getMessage());
			e.printStackTrace();
			response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Message could not be processed: " + e.getMessage()).build();
		}
		
		if (response == null) {
			try {
				Instant emdeonStart = Instant.now();
				X12Message responseX12Message = eligibilityService.postInquiryToEmdeon(environmentCode, eligibilityService.getEligibilityRequest().toString());
				Instant emdeonEnd = Instant.now();
				targetDuration = Duration.between(emdeonStart,  emdeonEnd);
				System.out.println("The call to Change took: <<<" + targetDuration + ">>>");
				logger.info("The call to Change took: <<<" + targetDuration + ">>>");
				
				if (responseX12Message != null) {			
					eligibilityService.postMessage(PAYOR_271, responseX12Message.toString(), clientId, userName, npi, memberId, payerCode, dateOfService, dateOfBirth, serviceTypeCode, patientFirstName, patientLastName, isDependent);
					eligibilityService.replaceReceiverSender(responseX12Message, eligibilityService.getOriginalSubmitter(), EligibilityService.THE_CONSULT);
					eligibilityService.postMessage(MTLOGIC_271, responseX12Message.toString(), clientId, userName, npi, memberId, payerCode, dateOfService, dateOfBirth, serviceTypeCode, patientFirstName, patientLastName, isDependent);
					//responseText = responseX12Message.toJSONString(Boolean.TRUE);
					responseText = responseX12Message.toString();
				} else {
					logger.error("Could not parse response message!");
					response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Could not parse response message!").build();
				}
			} catch (Exception e) {
				logger.error("Could not connect to Emdeon: " + e.getMessage());
				e.printStackTrace();
				response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Could not connect to Emdeon : " + e.getMessage()).build();
			}
		
			response = Response.status(responseCode).entity(responseText).build();
			logger.info("<<<EXITED createAndTransmitEligibilityRequestFromJsonData()");
			Instant apiEnd = Instant.now();
			apiDuration = Duration.between(apiStart,  apiEnd);
			System.out.println("The total API time was: <<<" + apiDuration + ">>>");
			logger.info("The total API time was: <<<" + apiDuration + ">>>");
			eligibilityService.storeAPIStatistics("CHANGE", targetDuration, apiDuration);
		}
		
		return response;
    }
	
	@Path("/eligibility")
	@POST
	@Consumes(MediaType.TEXT_PLAIN)
	@Produces(MediaType.TEXT_PLAIN)
	public Response transmitEligibilityInquiryAsText(@Context HttpHeaders headers, String inputMessage) throws JSONException 
	{	
		Instant apiStart = Instant.now();
		logger.info(">>>ENTERED transmitEligibilityInquiryAsText()");
		Response response = null;
		String responseEncoding = null;
		String environmentCode = null;
		String token = null;
		EligibilityService eligibilityService = null;
		String responseText = null;
		int responseCode = HttpStatus.SC_ACCEPTED;
		Duration targetDuration = null;
		Duration apiDuration = null;
		String clientId = null;
		String userName = null;
		String npi = "";
		String subscriberIdentifier = "";
		String dateOfBirth = "";
		String dateOfService = "";
		String payorCode = "";
		String serviceTypeCode = null;
		String patientFirstName = null;
		String patientLastName = null;
		Boolean dependentFlag = false;
		
		try {
			responseEncoding = (headers.getRequestHeader("ResponseEncoding")!=null)?headers.getRequestHeader("ResponseEncoding").get(0):"J";
			environmentCode = (headers.getRequestHeader("EnvironmentCode")!=null)?headers.getRequestHeader("EnvironmentCode").get(0):"T";
			boolean authorized = false;
			try {
				token = headers.getRequestHeader("APIToken").get(0);
				eligibilityService = new EligibilityService();
				String jsonString = eligibilityService.verifyToken(token);
				if (jsonString != null) {
					try {
						JSONObject jsonObject = new JSONObject(jsonString);
						authorized = jsonObject.getBoolean("verified");
						clientId = jsonObject.getString("clientid");
						userName = jsonObject.getString("username");
					} catch (Exception e) {
						logger.error("Could not parse JSONWebToken! - " + e.getMessage());
						e.printStackTrace();
						authorized = false;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				authorized = false;
			}
			if (!authorized) {
				response = Response.status(HttpStatus.SC_UNAUTHORIZED).entity("Access denied!").build();
			} else {
				eligibilityService.setOriginalEligibilityInquiry(inputMessage);
				serviceTypeCode = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getServiceTypeCode();
				npi = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getInformationReceiveIdentificationNumber();
				subscriberIdentifier = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getSubscriberIdentifier();
				dateOfBirth = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getSubscriberDateOfBirth();
				dateOfService = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getPatientEligibilityDate();
				payorCode = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getPayorCode();
				patientFirstName = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getPatientFirstName();
				patientLastName = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getPatientLastName();
				dependentFlag = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).isDependent();
				eligibilityService.postPatientMetaData();
				
				eligibilityService.postMessage(CLIENT_270, eligibilityService.getEligibilityRequest().toString(), clientId, userName, npi, subscriberIdentifier, payorCode, dateOfService, dateOfBirth, serviceTypeCode, patientFirstName, patientLastName, dependentFlag);
				eligibilityService.replaceReceiverSender(eligibilityService.getEligibilityRequest(), EligibilityService.EMDEON, EligibilityService.THE_CONSULT);
				eligibilityService.updateMessageWithInternalPayerCode(eligibilityService.getEligibilityRequest());
				eligibilityService.postMessage(MTLOGIC_270, eligibilityService.getEligibilityRequest().toString(), clientId, userName, npi, subscriberIdentifier, payorCode, dateOfService, dateOfBirth, serviceTypeCode, patientFirstName, patientLastName, dependentFlag);
			}
		} catch (InvalidX12MessageException ixme) {
			logger.error("Could not parse incoming message! - " + ixme.getMessage());
			ixme.printStackTrace();
			response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity(ixme.getMessage()).build();
		} catch (Exception e) {
			logger.error("Message could not be processed: " + e.getMessage());
			e.printStackTrace();
			response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Message could not be processed: " + e.getMessage()).build();
		}
		
		if (response == null) {
			try {
				Instant emdeonStart = Instant.now();
				X12Message responseX12Message = eligibilityService.postInquiryToEmdeon(environmentCode, eligibilityService.getEligibilityRequest().toString());
				Instant emdeonEnd = Instant.now();
				targetDuration = Duration.between(emdeonStart,  emdeonEnd);
				System.out.println("The call to Change took: <<<" + targetDuration + ">>>");
				logger.info("The call to Change took: <<<" + targetDuration + ">>>");
				
				if (responseX12Message != null) {			
					eligibilityService.postMessage(PAYOR_271, responseX12Message.toString(), clientId, userName, npi, subscriberIdentifier, payorCode, dateOfService, dateOfBirth, serviceTypeCode, patientFirstName, patientLastName, dependentFlag);
					eligibilityService.replaceReceiverSender(responseX12Message, eligibilityService.getOriginalSubmitter(), EligibilityService.THE_CONSULT);
					eligibilityService.postMessage(MTLOGIC_271, responseX12Message.toString(), clientId, userName, npi, subscriberIdentifier, payorCode, dateOfService, dateOfBirth, serviceTypeCode, patientFirstName, patientLastName, dependentFlag);
					if ("J".equals(responseEncoding)) {
					    responseText = responseX12Message.toJSONString(Boolean.TRUE);
					    System.out.println(responseText);
					} else {
					    responseText = responseX12Message.toString();
					}
				} else {
					logger.error("Could not parse response message!");
					response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Could not parse response message!").build();
				}
			} catch (Exception e) {
				logger.error("Could not connect to Emdeon: " + e.getMessage());
				e.printStackTrace();
				response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Could not connect to Emdeon : " + e.getMessage()).build();
			}
		
			response = Response.status(responseCode).entity(responseText).build();
			logger.info("<<<EXITED transmitEligibilityInquiryAsText()");
			Instant apiEnd = Instant.now();
			apiDuration = Duration.between(apiStart,  apiEnd);
			System.out.println("The total API time was: <<<" + apiDuration + ">>>");
			logger.info("The total API time was: <<<" + apiDuration + ">>>");
			eligibilityService.storeAPIStatistics("CHANGE", targetDuration, apiDuration);
		}
		
		return response;
	}
	
	@Path("/json/eligibility-message")
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.TEXT_PLAIN)
	public Response transmitEligibilityInquiry(@Context HttpHeaders headers, String inputMessage) throws JSONException 
	{	
		logger.info(">>>ENTERED transmitEligibilityInquiry()");
		Response response = null;
		String environmentCode = null;
		EligibilityService eligibilityService = null;
		String responseText = null;
		int responseCode = HttpStatus.SC_ACCEPTED;
		String clientId = null;
		String userName = null;
		String npi = "";
		String subscriberIdentifier = "";
		String dateOfBirth = "";
		String dateOfService = "";
		String payorCode = "";
		String serviceTypeCode = null;
		String patientFirstName = null;
		String patientLastName = null;
		Boolean dependentFlag = false;
		
		try {
			environmentCode = (headers.getRequestHeader("EnvironmentCode")!=null)?headers.getRequestHeader("EnvironmentCode").get(0):"T";
			final JSONObject obj = new JSONObject(inputMessage);
			String message = obj.getString("message");
			eligibilityService = new EligibilityService();
			eligibilityService.setOriginalEligibilityInquiry(message);
			serviceTypeCode = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getServiceTypeCode();
			npi = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getInformationReceiveIdentificationNumber();
			subscriberIdentifier = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getSubscriberIdentifier();
			dateOfBirth = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getSubscriberDateOfBirth();
			dateOfService = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getPatientEligibilityDate();
			payorCode = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getPayorCode();
			patientFirstName = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getPatientFirstName();
			patientLastName = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).getPatientLastName();
			dependentFlag = eligibilityService.getEligibilityRequest().getInterchangeControlList().get(0).isDependent();
			eligibilityService.postPatientMetaData();
			eligibilityService.postMessage(CLIENT_270, eligibilityService.getEligibilityRequest().toString(), clientId, userName, npi, subscriberIdentifier, payorCode, dateOfService, dateOfBirth, serviceTypeCode, patientFirstName, patientLastName, dependentFlag);
			eligibilityService.replaceReceiverSender(eligibilityService.getEligibilityRequest(), EligibilityService.EMDEON, EligibilityService.THE_CONSULT);
			eligibilityService.updateMessageWithInternalPayerCode(eligibilityService.getEligibilityRequest());
			eligibilityService.postMessage(MTLOGIC_270, eligibilityService.getEligibilityRequest().toString(), clientId, userName, npi, subscriberIdentifier, payorCode, dateOfService, dateOfBirth, serviceTypeCode, patientFirstName, patientLastName, dependentFlag);
		} catch (InvalidX12MessageException ixme) {
			logger.error("Could not parse incoming message! - " + ixme.getMessage());
			ixme.printStackTrace();
			response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity(ixme.getMessage()).build();
		} catch (Exception e) {
			logger.error("Message could not be processed: " + e.getMessage());
			e.printStackTrace();
			response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Message could not be processed: " + e.getMessage()).build();
		}
		
		if (response == null) {
			try {
				X12Message responseX12Message = eligibilityService.postInquiryToEmdeon(environmentCode, eligibilityService.getEligibilityRequest().toString());
				if (responseX12Message == null || responseX12Message.hasConnectionError()) {
					responseX12Message = eligibilityService.postInquiryToEmdeon(environmentCode, eligibilityService.getEligibilityRequest().toString());
				}
				
				if (responseX12Message != null) {			
					eligibilityService.postMessage(PAYOR_271, responseX12Message.toString(), clientId, userName, npi, subscriberIdentifier, payorCode, dateOfService, dateOfBirth, serviceTypeCode, patientFirstName, patientLastName, dependentFlag);
					eligibilityService.replaceReceiverSender(responseX12Message, eligibilityService.getOriginalSubmitter(), EligibilityService.THE_CONSULT);
					eligibilityService.postMessage(MTLOGIC_271, responseX12Message.toString(), clientId, userName, npi, subscriberIdentifier, payorCode, dateOfService, dateOfBirth, serviceTypeCode, patientFirstName, patientLastName, dependentFlag);
					//responseText = responseX12Message.toJSONString(Boolean.TRUE);
					responseText = responseX12Message.toString();
				} else {
					logger.error("Could not parse response message!");
					response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Could not parse response message!").build();
				}
			} catch (Exception e) {
				logger.error("Could not connect to Emdeon: " + e.getMessage());
				e.printStackTrace();
				response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Could not connect to Emdeon : " + e.getMessage()).build();
			}
		}
		
		if (response == null) {
			response = Response.status(responseCode).entity(responseText).build();
		}
		logger.info("<<<EXITED transmitEligibilityInquiry()");
		return response;
	}
	
	@Path("/eligibility-mock")
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.TEXT_PLAIN)
	public Response mockEligibilityInquiry(@Context HttpHeaders headers, 
            @FormParam("firstname") String firstName,
            @FormParam("lastname") String lastName,
            @FormParam("npi") String npi,
            @FormParam("dob") String dateOfBirth,
            @FormParam("payercode") String payerCode,
            @FormParam("memberid") String memberId,
            @FormParam("dos") String dateOfService,
            @FormParam("trace") String trace,
            @FormParam("apitoken") String apikey,
            @FormParam("payername") String payerName) throws JSONException 
	{	
		logger.info(">>>ENTERED mockEligibilityInquiry()");
		Response response = null;
		String token = null;
		String clientId = null;
		String userName = null;
		EligibilityService eligibilityService = null;
		
		boolean authorized = false;
		try {
			token = headers.getRequestHeader("apitoken").get(0);
			eligibilityService = new EligibilityService();
			String jsonString = eligibilityService.verifyToken(token);
			if (jsonString != null) {
				try {
					JSONObject jsonObject = new JSONObject(jsonString);
					authorized = jsonObject.getBoolean("verified");
					clientId = jsonObject.getString("clientid");
					userName = jsonObject.getString("username");
				} catch (Exception e) {
					logger.error("Could not parse JSONWebToken! - " + e.getMessage());
					e.printStackTrace();
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			authorized = false;
		}
		if (!authorized) {
			response = Response.status(HttpStatus.SC_UNAUTHORIZED).entity("Access denied!").build();
		} else {
			//Theresa Clegg
			String testJSONResponse = "{\"ISA\":{\"authorization_information_qualifier\":\"00\",\"authorization_information\":\"          \",\"security_information_qualifier\":\"00\",\"security_information\":\"          \",\"interchange_sender_id_qualifier\":\"ZZ\",\"interchange_sender_id\":\"15657851       \",\"interchange_receiver_id_qualifier\":\"ZZ\",\"interchange_receiver_id\":\"15657851       \",\"interchange_date\":\"121017\",\"interchange_time\":\"1631\",\"repetition_separator\":\"^\",\"interchange_control_verison_number\":\"00501\",\"acknowledgement_requested\":\"818555262\",\"interchange_usage_indicator\":\"1\",\"component_element_separator\":\"P\",\"segment_element_separator\":\"|\"},\"GS\":{\"functional_identifier_code\":\"HB\",\"application_sender_code\":\"EMDEON\",\"application_receiver_code\":\"15657851\",\"date\":\"20170509\",\"time\":\"1129\",\"group_control_number\":\"818555262\",\"responsible_agency_code\":\"X\",\"version\":\"005010X279\"},\"ST\":{\"transaction_set_identifier_code\":\"271\",\"transaction_set_control_number\":\"818555262\",\"implementation_convention_reference\":\"005010X279\"},\"BHT_0\":{\"hierarchical_structure_code\":\"0022\",\"transaction_set_purpose_code\":\"11\",\"reference_identification\":\"10001234\",\"date\":\"20170509\",\"time\":\"1129\"},\"2000A_0\":{\"HL_0\":{\"hierarchical_id_number\":\"1\",\"hierarchical_parent_number\":\"\",\"hierarchical_level_code\":\"20-Information Source\",\"hierarchical_level_code_2\":\"1\"},\"2100A_0\":{\"NM1_0\":{\"entity_identifier_code\":\"PR-Payer\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"UNITEDHEALTHCARE\",\"name_first\":\"\",\"name_middle\":\"\",\"name_prefix\":\"\",\"name_suffix\":\"\",\"identification_code_qualifier\":\"PI-Payor Identification\",\"identification_code\":\"00112\"},\"PER_0\":{\"contact_function_code\":\"IC-Information Contact\",\"name\":\"\",\"communication_number_qualifier\":\"UR-Uniform Resource Locator (URL)\",\"commnication_number\":\"WWW.UNITEDHEALTHCAREONLINE.COM\"}}},\"2000B_0\":{\"HL_1\":{\"hierarchical_id_number\":\"2\",\"hierarchical_parent_number\":\"1\",\"hierarchical_level_code\":\"21-Information Receiver\",\"hierarchical_level_code_2\":\"1\"},\"2100B_0\":{\"NM1_1\":{\"entity_identifier_code\":\"1P-Provider\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"AHPROVIDER\",\"name_first\":\"\",\"name_middle\":\"\",\"name_prefix\":\"\",\"name_suffix\":\"\",\"identification_code_qualifier\":\"XX-Centers for Medicare and Medicaid Services National Provider Identifier\",\"identification_code\":\"1104153139\"}}},\"2000C_0\":{\"HL_2\":{\"hierarchical_id_number\":\"3\",\"hierarchical_parent_number\":\"2\",\"hierarchical_level_code\":\"22-Subscriber\",\"hierarchical_level_code_2\":\"0\"},\"TRN_0\":{\"trace_type_code\":\"2-Referenced Transaction Trace Numbers\",\"reference_identification\":\"11111111-00000000\",\"originating_company_identifier\":\"9877281234\"},\"TRN_1\":{\"trace_type_code\":\"1-Current Transaction Trace Numbers\",\"reference_identification\":\"818555262\",\"originating_company_identifier\":\"9EMDEON999\"},\"2100C_0\":{\"NM1_2\":{\"entity_identifier_code\":\"IL-Insured or Subscriber\",\"entity_type_qualifier\":\"1-Person\",\"name_last\":\"CLEGG\",\"name_first\":\"THERESA\",\"name_middle\":\"J\",\"name_prefix\":\"\",\"name_suffix\":\"\",\"identification_code_qualifier\":\"MI-Member Identification Number\",\"identification_code\":\"900994692\"},\"REF_0\":{\"reference_identification_qualifier\":\"6P-Group Number\",\"reference_identification\":\"168504\"},\"N3_0\":{\"address_information\":\"8112 N PALMYRA RD\"},\"N4_0\":{\"city_name\":\"CANFIELD\",\"state_code\":\"OH\",\"postal_code\":\"44406\"},\"DMG_0\":{\"date_time_period_format_qualifier\":\"D8-Date Expressed in Format CCYYMMDD\",\"date_time_period\":\"19680129\",\"gender_code\":\"F-Female\"},\"INS_0\":{\"response_code\":\"Y-Yes\",\"individual_relationship_code\":\"18-Self\",\"maintenance_type_code\":\"001-Change\",\"maintenance_reason_code\":\"25-Change in Identifying Data Elements\"},\"DTP_0\":{\"date_time_qualifier\":\"346-Plan Begin\",\"date_time_period_format_qualifier\":\"D8-Date Expressed in Format CCYYMMDD\",\"date_time_period\":\"20170101\"},\"2110C_0_0\":{\"EB_0\":{\"eligibility_or_benefit_information_code\":\"1-Active Coverage\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"CHOICE PLUS\"},\"LS_0\":{\"loop_identifier_code\":\"2120\"},\"2120C_0\":{\"NM1_3\":{\"entity_identifier_code\":\"PR-Payer\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"UNITEDHEALTHCARE\",\"name_first\":\"\",\"name_middle\":\"\",\"name_prefix\":\"\",\"name_suffix\":\"\",\"identification_code_qualifier\":\"PI-Payor Identification\",\"identification_code\":\"87726\"},\"N3_1\":{\"address_information\":\"P.O. BOX 30555\"},\"N4_1\":{\"city_name\":\"SALT LAKE CITY\",\"state_code\":\"UT\",\"postal_code\":\"841300555\"},\"PER_1\":{\"contact_function_code\":\"IC-Information Contact\",\"name\":\"\",\"communication_number_qualifier\":\"UR-Uniform Resource Locator (URL)\",\"commnication_number\":\"WWW.UNITEDHEALTHCAREONLINE.COM\"}},\"LE_0\":{\"loop_identifier_code\":\"2120\"}},\"2110C_1_0\":{\"EB_1\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"2600\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_2_0\":{\"EB_2\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"24-Year to Date\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_3_0\":{\"EB_3\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"24-Year to Date\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_4_0\":{\"EB_4\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"24-Year to Date\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_5_0\":{\"EB_5\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"24-Year to Date\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_6_0\":{\"EB_6\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"2600\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_7_0\":{\"EB_7\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"2600\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_8_0\":{\"EB_8\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"7800\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_9_0\":{\"EB_9\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"15600\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_10_0\":{\"EB_10\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"5200\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_11_0\":{\"EB_11\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"3900\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_12_0\":{\"EB_12\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"3900\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_13_0\":{\"EB_13\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"5200\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_14_0\":{\"EB_14\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"7800\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_15_0\":{\"EB_15\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"2600\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_16_0\":{\"EB_16\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"15600\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_17_0\":{\"EB_17\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"7800\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_18_0\":{\"EB_18\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"7800\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_19_0\":{\"EB_19\":{\"eligibility_or_benefit_information_code\":\"1-Active Coverage\",\"coverage_level_code\":\"\",\"service_type_code\":\"1-Medical Care^33-Chiropractic^47-Hospital^48-Hospital - Inpatient^50-Hospital - Outpatient^86-Emergency Services^98-Professional (Physician) Visit - Office^AL-Vision (Optometry)^MH-Mental Health^UC-Urgent Care\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_20_0\":{\"EB_20\":{\"eligibility_or_benefit_information_code\":\"A-Co-Insurance\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"50-Hospital - Outpatient^48-Hospital - Inpatient^33-Chiropractic^98-Professional (Physician) Visit - Office^UC-Urgent Care^86-Emergency Services\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"27-Visit\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\".2\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_21_0\":{\"EB_21\":{\"eligibility_or_benefit_information_code\":\"A-Co-Insurance\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"48-Hospital - Inpatient^33-Chiropractic^98-Professional (Physician) Visit - Office^UC-Urgent Care^86-Emergency Services^50-Hospital - Outpatient\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"27-Visit\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\".5\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_22_0\":{\"EB_22\":{\"eligibility_or_benefit_information_code\":\"B-Co-Payment\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"33-Chiropractic^48-Hospital - Inpatient^50-Hospital - Outpatient^86-Emergency Services^98-Professional (Physician) Visit - Office^UC-Urgent Care\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"27-Visit\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_23_0\":{\"EB_23\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"33-Chiropractic\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"999999.99\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"VS-Visits\",\"quantity\":\"20\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"},\"MSG_0\":{\"free_form_message_text\":\"LIMITATION COMBINED FOR IN AND OUT OF NETWORK\"}},\"2110C_24_0\":{\"EB_24\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"33-Chiropractic\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"999999.99\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"VS-Visits\",\"quantity\":\"20\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"},\"MSG_1\":{\"free_form_message_text\":\"LIMITATION COMBINED FOR IN AND OUT OF NETWORK\"}},\"2110C_25_0\":{\"EB_25\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"33-Chiropractic^48-Hospital - Inpatient^50-Hospital - Outpatient^86-Emergency Services^98-Professional (Physician) Visit - Office^UC-Urgent Care\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_26_0\":{\"EB_26\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"33-Chiropractic^48-Hospital - Inpatient^50-Hospital - Outpatient^86-Emergency Services^98-Professional (Physician) Visit - Office^UC-Urgent Care\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"},\"MSG_2\":{\"free_form_message_text\":\"ADDITIONAL COVERED PER OCCURRENCE\"}},\"2110C_27_0\":{\"EB_27\":{\"eligibility_or_benefit_information_code\":\"U-Contact Following Entity for Eligibility or Benefit Information\",\"coverage_level_code\":\"\",\"service_type_code\":\"AL-Vision (Optometry)\"},\"LS_1\":{\"loop_identifier_code\":\"2120\"},\"2120C_1\":{\"NM1_4\":{\"entity_identifier_code\":\"VN-Vendor\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"OPTUMHEALTH SPECIALTY BENEFITS VISION\"},\"PER_2\":{\"contact_function_code\":\"IC-Information Contact\",\"name\":\"\",\"communication_number_qualifier\":\"UR-Uniform Resource Locator (URL)\",\"commnication_number\":\"WWW.OPTUMHEALTHVISION.COM\"}},\"LE_1\":{\"loop_identifier_code\":\"2120\"}},\"2110C_28_0\":{\"EB_28\":{\"eligibility_or_benefit_information_code\":\"U-Contact Following Entity for Eligibility or Benefit Information\",\"coverage_level_code\":\"\",\"service_type_code\":\"35-Dental Care\"},\"LS_2\":{\"loop_identifier_code\":\"2120\"},\"2120C_2\":{\"NM1_5\":{\"entity_identifier_code\":\"VN-Vendor\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"UNITEDHEALTHCARE DENTAL\"},\"PER_3\":{\"contact_function_code\":\"IC-Information Contact\",\"name\":\"\",\"communication_number_qualifier\":\"UR-Uniform Resource Locator (URL)\",\"commnication_number\":\"WWW.DBP.COM\"}},\"LE_2\":{\"loop_identifier_code\":\"2120\"}},\"2110C_29_0\":{\"EB_29\":{\"eligibility_or_benefit_information_code\":\"U-Contact Following Entity for Eligibility or Benefit Information\",\"coverage_level_code\":\"\",\"service_type_code\":\"88-Pharmacy\"},\"LS_3\":{\"loop_identifier_code\":\"2120\"},\"2120C_3\":{\"NM1_6\":{\"entity_identifier_code\":\"VN-Vendor\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"PRESCRIPTION SOLUTIONS\"}},\"LE_3\":{\"loop_identifier_code\":\"2120\"}},\"2110C_30_0\":{\"EB_30\":{\"eligibility_or_benefit_information_code\":\"X-Health Care Facility\"},\"LS_4\":{\"loop_identifier_code\":\"2120\"},\"2120C_4\":{\"NM1_7\":{\"entity_identifier_code\":\"1P-Provider\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"AHPROVIDER\",\"name_first\":\"\",\"name_middle\":\"\",\"name_prefix\":\"\",\"name_suffix\":\"\",\"identification_code_qualifier\":\"XX-Centers for Medicare and Medicaid Services National Provider Identifier\",\"identification_code\":\"1104153139\"}},\"LE_4\":{\"loop_identifier_code\":\"2120\"}}}},\"GE\":{\"number_of_transaction_sets\":\"1\",\"group_control_number\":\"818555262\"},\"IEA\":{\"number_of_functional_groups\":\"1\",\"interchange_control_number\":\"818555262\"}}";
			//Joyce Buckingham
			//String testJSONResponse = "{\"ISA\":{\"authorization_information_qualifier\":\"00\",\"authorization_information\":\"          \",\"security_information_qualifier\":\"00\",\"security_information\":\"          \",\"interchange_sender_id_qualifier\":\"ZZ\",\"interchange_sender_id\":\"15657851       \",\"interchange_receiver_id_qualifier\":\"ZZ\",\"interchange_receiver_id\":\"15657851       \",\"interchange_date\":\"121017\",\"interchange_time\":\"1631\",\"repetition_separator\":\"^\",\"interchange_control_verison_number\":\"00501\",\"acknowledgement_requested\":\"810000245\",\"interchange_usage_indicator\":\"1\",\"component_element_separator\":\"P\",\"segment_element_separator\":\"|\"},\"GS\":{\"functional_identifier_code\":\"HB\",\"application_sender_code\":\"EMDEON\",\"application_receiver_code\":\"15657851\",\"date\":\"20170503\",\"time\":\"1631\",\"group_control_number\":\"810000245\",\"responsible_agency_code\":\"X\",\"version\":\"005010X279\"},\"ST\":{\"transaction_set_identifier_code\":\"271\",\"transaction_set_control_number\":\"810000245\",\"implementation_convention_reference\":\"005010X279\"},\"BHT_0\":{\"hierarchical_structure_code\":\"0022\",\"transaction_set_purpose_code\":\"11\",\"reference_identification\":\"10001234\",\"date\":\"20170503\",\"time\":\"1631\"},\"2000A_0\":{\"HL_0\":{\"hierarchical_id_number\":\"1\",\"hierarchical_parent_number\":\"\",\"hierarchical_level_code\":\"20-Information Source\",\"hierarchical_level_code_2\":\"1\"},\"2100A_0\":{\"NM1_0\":{\"entity_identifier_code\":\"PR-Payer\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"CMS\",\"name_first\":\"\",\"name_middle\":\"\",\"name_prefix\":\"\",\"name_suffix\":\"\",\"identification_code_qualifier\":\"PI-Payor Identification\",\"identification_code\":\"00431\"}}},\"2000B_0\":{\"HL_1\":{\"hierarchical_id_number\":\"2\",\"hierarchical_parent_number\":\"1\",\"hierarchical_level_code\":\"21-Information Receiver\",\"hierarchical_level_code_2\":\"1\"},\"2100B_0\":{\"NM1_1\":{\"entity_identifier_code\":\"1P-Provider\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"AHPROVIDER\",\"name_first\":\"\",\"name_middle\":\"\",\"name_prefix\":\"\",\"name_suffix\":\"\",\"identification_code_qualifier\":\"XX-Centers for Medicare and Medicaid Services National Provider Identifier\",\"identification_code\":\"1871558510\"}}},\"2000C_0\":{\"HL_2\":{\"hierarchical_id_number\":\"3\",\"hierarchical_parent_number\":\"2\",\"hierarchical_level_code\":\"22-Subscriber\",\"hierarchical_level_code_2\":\"0\"},\"TRN_0\":{\"trace_type_code\":\"2-Referenced Transaction Trace Numbers\",\"reference_identification\":\"93175-012547\",\"originating_company_identifier\":\"9877281234\"},\"TRN_1\":{\"trace_type_code\":\"1-Current Transaction Trace Numbers\",\"reference_identification\":\"810000245\",\"originating_company_identifier\":\"9EMDEON999\"},\"2100C_0\":{\"NM1_2\":{\"entity_identifier_code\":\"IL-Insured or Subscriber\",\"entity_type_qualifier\":\"1-Person\",\"name_last\":\"BUCKINGHAM\",\"name_first\":\"JOYCE\",\"name_middle\":\"A\",\"name_prefix\":\"\",\"name_suffix\":\"\",\"identification_code_qualifier\":\"MI-Member Identification Number\",\"identification_code\":\"284564562A\"},\"N3_0\":{\"address_information\":\"1441 SHAFTESBURY RD\"},\"N4_0\":{\"city_name\":\"DAYTON\",\"state_code\":\"OH\",\"postal_code\":\"454064239\"},\"DMG_0\":{\"date_time_period_format_qualifier\":\"D8-Date Expressed in Format CCYYMMDD\",\"date_time_period\":\"19550616\",\"gender_code\":\"F-Female\"},\"DTP_0\":{\"date_time_qualifier\":\"307-Eligibility\",\"date_time_period_format_qualifier\":\"D8-Date Expressed in Format CCYYMMDD\",\"date_time_period\":\"20170421\"},\"2110C_0_0\":{\"EB_0\":{\"eligibility_or_benefit_information_code\":\"I-Non-Covered\",\"coverage_level_code\":\"\",\"service_type_code\":\"41-Routine (Preventive) Dental^54-Long Term Care\"}},\"2110C_1_0\":{\"EB_1\":{\"eligibility_or_benefit_information_code\":\"1-Active Coverage\",\"coverage_level_code\":\"\",\"service_type_code\":\"88-Pharmacy\"}},\"2110C_2_0\":{\"EB_2\":{\"eligibility_or_benefit_information_code\":\"1-Active Coverage\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage^BV-Obstetrical/Gynecological^BU-Obstetrical^BT-Gynecological^AG-Skilled Nursing Care^A7-Psychiatric - Inpatient^A5-Psychiatric - Room and Board^83-Infertility^76-Dialysis^69-Maternity^49-Hospital - Room and Board^48-Hospital - Inpatient^45-Hospice^42-Home Health Care^10-Blood Charges\",\"insurance_type_code\":\"MA-Medicare Part A\"},\"DTP_1\":{\"date_time_qualifier\":\"291-Plan\",\"date_time_period_format_qualifier\":\"D8-Date Expressed in Format CCYYMMDD\",\"date_time_period\":\"19921101\"}},\"2110C_3_0\":{\"EB_3\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"26-Episode\",\"monetary_amount\":\"1316\"},\"DTP_2\":{\"date_time_qualifier\":\"291-Plan\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_4_0\":{\"EB_4\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"1316\"},\"DTP_3\":{\"date_time_qualifier\":\"291-Plan\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_5_0\":{\"EB_5\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"\",\"service_type_code\":\"45-Hospice^42-Home Health Care\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"26-Episode\",\"monetary_amount\":\"0\"},\"DTP_4\":{\"date_time_qualifier\":\"292-Benefit\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_6_0\":{\"EB_6\":{\"eligibility_or_benefit_information_code\":\"B-Co-Payment\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"7-Day\",\"monetary_amount\":\"0\"},\"HSD_0\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"DA-Days\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"30-Exceeded\",\"number_of_periods\":\"0\"},\"HSD_1\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"DA-Days\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"31-Not Exceeded\",\"number_of_periods\":\"60\"},\"HSD_2\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"26-Episode\",\"number_of_periods\":\"1\"},\"DTP_5\":{\"date_time_qualifier\":\"435-Admission\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_7_0\":{\"EB_7\":{\"eligibility_or_benefit_information_code\":\"B-Co-Payment\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"7-Day\",\"monetary_amount\":\"329\"},\"HSD_3\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"DA-Days\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"30-Exceeded\",\"number_of_periods\":\"60\"},\"HSD_4\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"DA-Days\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"31-Not Exceeded\",\"number_of_periods\":\"90\"},\"HSD_5\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"26-Episode\",\"number_of_periods\":\"1\"},\"DTP_6\":{\"date_time_qualifier\":\"435-Admission\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_8_0\":{\"EB_8\":{\"eligibility_or_benefit_information_code\":\"B-Co-Payment\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"7-Day\",\"monetary_amount\":\"0\"},\"HSD_6\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"DA-Days\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"number_of_periods\":\"60\"},\"HSD_7\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"26-Episode\",\"number_of_periods\":\"1\"},\"DTP_7\":{\"date_time_qualifier\":\"435-Admission\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_9_0\":{\"EB_9\":{\"eligibility_or_benefit_information_code\":\"B-Co-Payment\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"7-Day\",\"monetary_amount\":\"329\"},\"HSD_8\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"DA-Days\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"number_of_periods\":\"30\"},\"HSD_9\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"26-Episode\",\"number_of_periods\":\"1\"},\"DTP_8\":{\"date_time_qualifier\":\"435-Admission\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_10_0\":{\"EB_10\":{\"eligibility_or_benefit_information_code\":\"B-Co-Payment\",\"coverage_level_code\":\"\",\"service_type_code\":\"AG-Skilled Nursing Care\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"7-Day\",\"monetary_amount\":\"0\"},\"HSD_10\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"DA-Days\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"30-Exceeded\",\"number_of_periods\":\"0\"},\"HSD_11\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"DA-Days\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"31-Not Exceeded\",\"number_of_periods\":\"20\"},\"HSD_12\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"26-Episode\",\"number_of_periods\":\"1\"},\"DTP_9\":{\"date_time_qualifier\":\"435-Admission\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_11_0\":{\"EB_11\":{\"eligibility_or_benefit_information_code\":\"B-Co-Payment\",\"coverage_level_code\":\"\",\"service_type_code\":\"AG-Skilled Nursing Care\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"7-Day\",\"monetary_amount\":\"164.5\"},\"HSD_13\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"DA-Days\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"30-Exceeded\",\"number_of_periods\":\"20\"},\"HSD_14\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"DA-Days\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"31-Not Exceeded\",\"number_of_periods\":\"100\"},\"HSD_15\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"26-Episode\",\"number_of_periods\":\"1\"},\"DTP_10\":{\"date_time_qualifier\":\"435-Admission\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_12_0\":{\"EB_12\":{\"eligibility_or_benefit_information_code\":\"B-Co-Payment\",\"coverage_level_code\":\"\",\"service_type_code\":\"AG-Skilled Nursing Care\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"7-Day\",\"monetary_amount\":\"0\"},\"HSD_16\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"DA-Days\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"number_of_periods\":\"20\"},\"HSD_17\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"26-Episode\",\"number_of_periods\":\"1\"},\"DTP_11\":{\"date_time_qualifier\":\"435-Admission\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_13_0\":{\"EB_13\":{\"eligibility_or_benefit_information_code\":\"B-Co-Payment\",\"coverage_level_code\":\"\",\"service_type_code\":\"AG-Skilled Nursing Care\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"7-Day\",\"monetary_amount\":\"164.5\"},\"HSD_18\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"DA-Days\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"number_of_periods\":\"80\"},\"HSD_19\":{\"quantity_qualifier\":\"\",\"quantity\":\"\",\"unit_or_basis_of_measurement_code\":\"\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"26-Episode\",\"number_of_periods\":\"1\"},\"DTP_12\":{\"date_time_qualifier\":\"435-Admission\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_14_0\":{\"EB_14\":{\"eligibility_or_benefit_information_code\":\"K-Reserve\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"32-Lifetime\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"DY-Days\",\"quantity\":\"60\"}},\"2110C_15_0\":{\"EB_15\":{\"eligibility_or_benefit_information_code\":\"K-Reserve\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"33-Lifetime Remaining\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"DY-Days\",\"quantity\":\"60\"}},\"2110C_16_0\":{\"EB_16\":{\"eligibility_or_benefit_information_code\":\"K-Reserve\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"7-Day\",\"monetary_amount\":\"658\"},\"DTP_13\":{\"date_time_qualifier\":\"435-Admission\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_17_0\":{\"EB_17\":{\"eligibility_or_benefit_information_code\":\"D-Benefit Description\",\"coverage_level_code\":\"\",\"service_type_code\":\"45-Hospice\",\"insurance_type_code\":\"MA-Medicare Part A\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"26-Episode\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"99-Quantity Used\",\"quantity\":\"0\"}},\"2110C_18_0\":{\"EB_18\":{\"eligibility_or_benefit_information_code\":\"1-Active Coverage\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage^UC-Urgent Care^DM-Durable Medical Equipment^BV-Obstetrical/Gynecological^BU-Obstetrical^BT-Gynecological^BG-Cardiac Rehabilitation^BF-Pulmonary Rehabilitation^AL-Vision (Optometry)^AK-Drug Addiction^AJ-Alcoholism^AI-Substance Abuse^AF-Speech Therapy^AE-Physical Medicine^AD-Occupational Therapy^A8-Psychiatric - Outpatient^A6-Psychotherapy^A4-Psychiatric^98-Professional (Physician) Visit - Office^86-Emergency Services^83-Infertility^76-Dialysis^73-Diagnostic Medical^69-Maternity^67-Smoking Cessation^53-Hospital - Ambulatory Surgical^52-Hospital - Emergency Medical^51-Hospital - Emergency Accident^50-Hospital - Outpatient^42-Home Health Care^40-Oral Surgery^39-Prosthodontics^38-Orthodontics^37-Dental Accident^36-Dental Crowns^33-Chiropractic^3-Consultation^28-Adjunctive Dental Services^27-Maxillofacial Prosthetics^26-Endodontics^25-Restorative^24-Periodontics^23-Diagnostic Dental^2-Surgical^14-Renal Supplies in the Home^10-Blood Charges\",\"insurance_type_code\":\"MB-Medicare Part B\"},\"DTP_14\":{\"date_time_qualifier\":\"291-Plan\",\"date_time_period_format_qualifier\":\"D8-Date Expressed in Format CCYYMMDD\",\"date_time_period\":\"19921101\"}},\"2110C_19_0\":{\"EB_19\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"MB-Medicare Part B\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"183\"},\"DTP_15\":{\"date_time_qualifier\":\"291-Plan\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_20_0\":{\"EB_20\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"MB-Medicare Part B\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"0\"},\"DTP_16\":{\"date_time_qualifier\":\"291-Plan\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_21_0\":{\"EB_21\":{\"eligibility_or_benefit_information_code\":\"A-Co-Insurance\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"MB-Medicare Part B\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"27-Visit\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\".2\"},\"DTP_17\":{\"date_time_qualifier\":\"291-Plan\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_22_0\":{\"EB_22\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"\",\"service_type_code\":\"AJ-Alcoholism^67-Smoking Cessation^42-Home Health Care\",\"insurance_type_code\":\"MB-Medicare Part B\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"0\"},\"DTP_18\":{\"date_time_qualifier\":\"292-Benefit\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_23_0\":{\"EB_23\":{\"eligibility_or_benefit_information_code\":\"A-Co-Insurance\",\"coverage_level_code\":\"\",\"service_type_code\":\"AJ-Alcoholism^67-Smoking Cessation^42-Home Health Care\",\"insurance_type_code\":\"MB-Medicare Part B\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"27-Visit\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"0\"},\"DTP_19\":{\"date_time_qualifier\":\"292-Benefit\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_24_0\":{\"EB_24\":{\"eligibility_or_benefit_information_code\":\"D-Benefit Description\",\"coverage_level_code\":\"\",\"service_type_code\":\"AD-Occupational Therapy^AE-Physical Medicine\",\"insurance_type_code\":\"MB-Medicare Part B\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"\",\"monetary_amount\":\"0\"},\"DTP_20\":{\"date_time_qualifier\":\"292-Benefit\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"},\"MSG_0\":{\"free_form_message_text\":\"Used Amount\"}},\"2110C_25_0\":{\"EB_25\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"\",\"service_type_code\":\"BF-Pulmonary Rehabilitation\",\"insurance_type_code\":\"MB-Medicare Part B\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"CA-Covered - Actual\",\"quantity\":\"72\"},\"MSG_1\":{\"free_form_message_text\":\"Technical\"}},\"2110C_26_0\":{\"EB_26\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"\",\"service_type_code\":\"BF-Pulmonary Rehabilitation\",\"insurance_type_code\":\"MB-Medicare Part B\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"CA-Covered - Actual\",\"quantity\":\"72\"},\"MSG_2\":{\"free_form_message_text\":\"Professional\"}},\"2110C_27_0\":{\"EB_27\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"\",\"service_type_code\":\"BG-Cardiac Rehabilitation\",\"insurance_type_code\":\"MB-Medicare Part B\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"99-Quantity Used\",\"quantity\":\"0\"},\"MSG_3\":{\"free_form_message_text\":\"Technical\"}},\"2110C_28_0\":{\"EB_28\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"\",\"service_type_code\":\"BG-Cardiac Rehabilitation\",\"insurance_type_code\":\"MB-Medicare Part B\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"99-Quantity Used\",\"quantity\":\"0\"},\"MSG_4\":{\"free_form_message_text\":\"Professional\"}},\"2110C_29_0\":{\"EB_29\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"\",\"service_type_code\":\"BG-Cardiac Rehabilitation\",\"insurance_type_code\":\"MB-Medicare Part B\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"99-Quantity Used\",\"quantity\":\"0\"},\"MSG_5\":{\"free_form_message_text\":\"Intensive Cardiac Rehabilitation - Technical\"}},\"2110C_30_0\":{\"EB_30\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"\",\"service_type_code\":\"BG-Cardiac Rehabilitation\",\"insurance_type_code\":\"MB-Medicare Part B\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"99-Quantity Used\",\"quantity\":\"0\"},\"MSG_6\":{\"free_form_message_text\":\"Intensive Cardiac Rehabilitation - Professional\"}},\"2110C_31_0\":{\"EB_31\":{\"eligibility_or_benefit_information_code\":\"X-Health Care Facility\",\"coverage_level_code\":\"\",\"service_type_code\":\"\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"\",\"composite_medical_procedure_identifier\":\"HC|G0180\"},\"DTP_21\":{\"date_time_qualifier\":\"193-Period Start\",\"date_time_period_format_qualifier\":\"D8-Date Expressed in Format CCYYMMDD\",\"date_time_period\":\"20120829\"}},\"2110C_32_0\":{\"EB_32\":{\"eligibility_or_benefit_information_code\":\"E-Exclusions\",\"coverage_level_code\":\"\",\"service_type_code\":\"10-Blood Charges\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"DB-Deductible Blood Units\",\"quantity\":\"3\"},\"HSD_20\":{\"quantity_qualifier\":\"FL-Units\",\"quantity\":\"3\",\"unit_or_basis_of_measurement_code\":\"\",\"sample_selection_modulus\":\"\",\"time_period_qualifier\":\"29-Remaining\"},\"DTP_22\":{\"date_time_qualifier\":\"292-Benefit\",\"date_time_period_format_qualifier\":\"RD8-Range of Dates Expressed in Format CCYYMMDDCCYYMMDD\",\"date_time_period\":\"20170101-20171231\"}},\"2110C_33_0\":{\"EB_33\":{\"eligibility_or_benefit_information_code\":\"R-Other or Additional Payor\",\"coverage_level_code\":\"\",\"service_type_code\":\"88-Pharmacy\",\"insurance_type_code\":\"OT-Other\"},\"REF_0\":{\"reference_identification_qualifier\":\"18-Plan Number\",\"reference_identification\":\"S5601 028\"},\"DTP_23\":{\"date_time_qualifier\":\"292-Benefit\",\"date_time_period_format_qualifier\":\"D8-Date Expressed in Format CCYYMMDD\",\"date_time_period\":\"20100101\"},\"LS_0\":{\"loop_identifier_code\":\"2120\"},\"2120C_0\":{\"NM1_3\":{\"entity_identifier_code\":\"PR-Payer\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"SILVERSCRIPT INSURANCE COMPANY\"},\"N3_1\":{\"address_information\":\"445 Great Circle Road\"},\"N4_1\":{\"city_name\":\"Nashville\",\"state_code\":\"TN\",\"postal_code\":\"37228\"},\"PER_0\":{\"contact_function_code\":\"IC-Information Contact\",\"name\":\"\",\"communication_number_qualifier\":\"TE-Telephone\",\"commnication_number\":\"8665526106\",\"communication_number_qualifier_2\":\"UR-Uniform Resource Locator (URL)\",\"communication_number_2\":\"www.SilverScript.com\"}},\"LE_0\":{\"loop_identifier_code\":\"2120\"}}}},\"GE\":{\"number_of_transaction_sets\":\"1\",\"group_control_number\":\"810000245\"},\"IEA\":{\"number_of_functional_groups\":\"1\",\"interchange_control_number\":\"810000245\"}}";
			//String testJSONResponse = "{\"ISA\":{\"authorization_information_qualifier\":\"00\",\"authorization_information\":\"          \",\"security_information_qualifier\":\"00\",\"security_information\":\"          \",\"interchange_sender_id_qualifier\":\"ZZ\",\"interchange_sender_id\":\"15657851       \",\"interchange_receiver_id_qualifier\":\"ZZ\",\"interchange_receiver_id\":\"15657851       \",\"interchange_date\":\"121017\",\"interchange_time\":\"1631\",\"repetition_separator\":\"^\",\"interchange_control_verison_number\":\"00501\",\"acknowledgement_requested\":\"813136180\",\"interchange_usage_indicator\":\"1\",\"component_element_separator\":\"P\",\"segment_element_separator\":\"|\"},\"GS\":{\"functional_identifier_code\":\"HB\",\"application_sender_code\":\"EMDEON\",\"application_receiver_code\":\"15657851\",\"date\":\"20170505\",\"time\":\"1310\",\"group_control_number\":\"813136180\",\"responsible_agency_code\":\"X\",\"version\":\"005010X279\"},\"ST\":{\"transaction_set_identifier_code\":\"271\",\"transaction_set_control_number\":\"813136180\",\"implementation_convention_reference\":\"005010X279\"},\"BHT_0\":{\"hierarchical_structure_code\":\"0022\",\"transaction_set_purpose_code\":\"11\",\"reference_identification\":\"10001234\",\"date\":\"20170505\",\"time\":\"1310\"},\"2000A_0\":{\"HL_0\":{\"hierarchical_id_number\":\"1\",\"hierarchical_parent_number\":\"\",\"hierarchical_level_code\":\"20-Information Source\",\"hierarchical_level_code_2\":\"1\"},\"2100A_0\":{\"NM1_0\":{\"entity_identifier_code\":\"PR-Payer\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"UNITEDHEALTHCARE\",\"name_first\":\"\",\"name_middle\":\"\",\"name_prefix\":\"\",\"name_suffix\":\"\",\"identification_code_qualifier\":\"PI-Payor Identification\",\"identification_code\":\"00112\"}\"PER_0\":{\"contact_function_code\":\"IC-Information Contact\",\"name\":\"\",\"communication_number_qualifier\":\"UR-Uniform Resource Locator (URL)\",\"commnication_number\":\"WWW.UNITEDHEALTHCAREONLINE.COM\"}}},\"2000B_0\":{\"HL_1\":{\"hierarchical_id_number\":\"2\",\"hierarchical_parent_number\":\"1\",\"hierarchical_level_code\":\"21-Information Receiver\",\"hierarchical_level_code_2\":\"1\"},\"2100B_0\":{\"NM1_1\":{\"entity_identifier_code\":\"1P-Provider\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"AHPROVIDER\",\"name_first\":\"\",\"name_middle\":\"\",\"name_prefix\":\"\",\"name_suffix\":\"\",\"identification_code_qualifier\":\"XX-Centers for Medicare and Medicaid Services National Provider Identifier\",\"identification_code\":\"1104153139\"}}},\"2000C_0\":{\"HL_2\":{\"hierarchical_id_number\":\"3\",\"hierarchical_parent_number\":\"2\",\"hierarchical_level_code\":\"22-Subscriber\",\"hierarchical_level_code_2\":\"0\"},\"TRN_0\":{\"trace_type_code\":\"2-Referenced Transaction Trace Numbers\",\"reference_identification\":\"01010101-10101010\",\"originating_company_identifier\":\"9877281234\"},\"TRN_1\":{\"trace_type_code\":\"1-Current Transaction Trace Numbers\",\"reference_identification\":\"813136180\",\"originating_company_identifier\":\"9EMDEON999\"},\"2100C_0\":{\"NM1_2\":{\"entity_identifier_code\":\"IL-Insured or Subscriber\",\"entity_type_qualifier\":\"1-Person\",\"name_last\":\"CLEGG\",\"name_first\":\"THERESA\",\"name_middle\":\"J\",\"name_prefix\":\"\",\"name_suffix\":\"\",\"identification_code_qualifier\":\"MI-Member Identification Number\",\"identification_code\":\"900994692\"},\"REF_0\":{\"reference_identification_qualifier\":\"6P-Group Number\",\"reference_identification\":\"168504\"},\"N3_0\":{\"address_information\":\"8112 N PALMYRA RD\"},\"N4_0\":{\"city_name\":\"CANFIELD\",\"state_code\":\"OH\",\"postal_code\":\"44406\"},\"DMG_0\":{\"date_time_period_format_qualifier\":\"D8-Date Expressed in Format CCYYMMDD\",\"date_time_period\":\"19680129\",\"gender_code\":\"F-Female\"},\"INS_0\":{\"response_code\":\"Y-Yes\",\"individual_relationship_code\":\"18-Self\",\"maintenance_type_code\":\"001-Change\",\"maintenance_reason_code\":\"25-Change in Identifying Data Elements\"},\"DTP_0\":{\"date_time_qualifier\":\"346-Plan Begin\",\"date_time_period_format_qualifier\":\"D8-Date Expressed in Format CCYYMMDD\",\"date_time_period\":\"20170101\"},\"2110C_0_0\":{\"EB_0\":{\"eligibility_or_benefit_information_code\":\"1-Active Coverage\",\"coverage_level_code\":\"\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"CHOICE PLUS\"},\"LS_0\":{\"loop_identifier_code\":\"2120\"},\"2120C_0\":{\"NM1_3\":{\"entity_identifier_code\":\"PR-Payer\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"UNITEDHEALTHCARE\",\"name_first\":\"\",\"name_middle\":\"\",\"name_prefix\":\"\",\"name_suffix\":\"\",\"identification_code_qualifier\":\"PI-Payor Identification\",\"identification_code\":\"87726\"},\"N3_1\":{\"address_information\":\"P.O. BOX 30555\"},\"N4_1\":{\"city_name\":\"SALT LAKE CITY\",\"state_code\":\"UT\",\"postal_code\":\"841300555\"},\"PER_1\":{\"contact_function_code\":\"IC-Information Contact\",\"name\":\"\",\"communication_number_qualifier\":\"UR-Uniform Resource Locator (URL)\",\"commnication_number\":\"WWW.UNITEDHEALTHCAREONLINE.COM\"}},\"LE_0\":{\"loop_identifier_code\":\"2120\"}},\"EB_1\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"2600\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"},\"EB_2\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"24-Year to Date\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_3_0\":{\"EB_3\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"24-Year to Date\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_4_0\":{\"EB_4\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"24-Year to Date\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_5_0\":{\"EB_5\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"24-Year to Date\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_6_0\":{\"EB_6\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"2600\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_7_0\":{\"EB_7\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"2600\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_8_0\":{\"EB_8\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"7800\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_9_0\":{\"EB_9\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"15600\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_10_0\":{\"EB_10\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"5200\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_11_0\":{\"EB_11\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"3900\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_12_0\":{\"EB_12\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"3900\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_13_0\":{\"EB_13\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"5200\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_14_0\":{\"EB_14\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"7800\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_15_0\":{\"EB_15\":{\"eligibility_or_benefit_information_code\":\"C-Deductible\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"2600\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_16_0\":{\"EB_16\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"15600\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_17_0\":{\"EB_17\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"7800\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_18_0\":{\"EB_18\":{\"eligibility_or_benefit_information_code\":\"G-Out of Pocket (Stop Loss)\",\"coverage_level_code\":\"FAM-Family\",\"service_type_code\":\"30-Health Benefit Plan Coverage\",\"insurance_type_code\":\"C1-Commercial\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"7800\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_19_0\":{\"EB_19\":{\"eligibility_or_benefit_information_code\":\"1-Active Coverage\",\"coverage_level_code\":\"\",\"service_type_code\":\"1-Medical Care^33-Chiropractic^47-Hospital^48-Hospital - Inpatient^50-Hospital - Outpatient^86-Emergency Services^98-Professional (Physician) Visit - Office^AL-Vision (Optometry)^MH-Mental Health^UC-Urgent Care\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_20_0\":{\"EB_20\":{\"eligibility_or_benefit_information_code\":\"A-Co-Insurance\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"50-Hospital - Outpatient^48-Hospital - Inpatient^33-Chiropractic^98-Professional (Physician) Visit - Office^UC-Urgent Care^86-Emergency Services\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"27-Visit\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\".2\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"Y-Yes\"}},\"2110C_21_0\":{\"EB_21\":{\"eligibility_or_benefit_information_code\":\"A-Co-Insurance\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"48-Hospital - Inpatient^33-Chiropractic^98-Professional (Physician) Visit - Office^UC-Urgent Care^86-Emergency Services^50-Hospital - Outpatient\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"27-Visit\",\"monetary_amount\":\"\",\"percentage_as_decimal\":\".5\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"N-No\"}},\"2110C_22_0\":{\"EB_22\":{\"eligibility_or_benefit_information_code\":\"B-Co-Payment\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"33-Chiropractic^48-Hospital - Inpatient^50-Hospital - Outpatient^86-Emergency Services^98-Professional (Physician) Visit - Office^UC-Urgent Care\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"27-Visit\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_23_0\":{\"EB_23\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"33-Chiropractic\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"29-Remaining\",\"monetary_amount\":\"999999.99\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"VS-Visits\",\"quantity\":\"20\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"},\"MSG_0\":{\"free_form_message_text\":\"LIMITATION COMBINED FOR IN AND OUT OF NETWORK\"}},\"2110C_24_0\":{\"EB_24\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"33-Chiropractic\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"23-Calendar Year\",\"monetary_amount\":\"999999.99\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"VS-Visits\",\"quantity\":\"20\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"},\"MSG_1\":{\"free_form_message_text\":\"LIMITATION COMBINED FOR IN AND OUT OF NETWORK\"}},\"2110C_25_0\":{\"EB_25\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"33-Chiropractic^48-Hospital - Inpatient^50-Hospital - Outpatient^86-Emergency Services^98-Professional (Physician) Visit - Office^UC-Urgent Care\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"}},\"2110C_26_0\":{\"EB_26\":{\"eligibility_or_benefit_information_code\":\"F-Limitations\",\"coverage_level_code\":\"IND-Individual\",\"service_type_code\":\"33-Chiropractic^48-Hospital - Inpatient^50-Hospital - Outpatient^86-Emergency Services^98-Professional (Physician) Visit - Office^UC-Urgent Care\",\"insurance_type_code\":\"\",\"plan_coverage_description\":\"\",\"time_period_qualifier\":\"\",\"monetary_amount\":\"0\",\"percentage_as_decimal\":\"\",\"quantity_qualifier\":\"\",\"quantity\":\"\",\"response_code\":\"\",\"response_code_2\":\"W-Not Applicable\"},\"MSG_2\":{\"free_form_message_text\":\"ADDITIONAL COVERED PER OCCURRENCE\"}},\"2110C_27_0\":{\"EB_27\":{\"eligibility_or_benefit_information_code\":\"U-Contact Following Entity for Eligibility or Benefit Information\",\"coverage_level_code\":\"\",\"service_type_code\":\"AL-Vision (Optometry)\"},\"LS_1\":{\"loop_identifier_code\":\"2120\"},\"2120C_1\":{\"NM1_4\":{\"entity_identifier_code\":\"VN-Vendor\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"OPTUMHEALTH SPECIALTY BENEFITS VISION\"},\"PER_2\":{\"contact_function_code\":\"IC-Information Contact\",\"name\":\"\",\"communication_number_qualifier\":\"UR-Uniform Resource Locator (URL)\",\"commnication_number\":\"WWW.OPTUMHEALTHVISION.COM\"}},\"LE_1\":{\"loop_identifier_code\":\"2120\"}},\"EB_28\":{\"eligibility_or_benefit_information_code\":\"U-Contact Following Entity for Eligibility or Benefit Information\",\"coverage_level_code\":\"\",\"service_type_code\":\"35-Dental Care\"},\"NM1_5\":{\"entity_identifier_code\":\"VN-Vendor\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"UNITEDHEALTHCARE DENTAL\"},\"PER_3\":{\"contact_function_code\":\"IC-Information Contact\",\"name\":\"\",\"communication_number_qualifier\":\"UR-Uniform Resource Locator (URL)\",\"commnication_number\":\"WWW.DBP.COM\"}},\"LE_2\":{\"loop_identifier_code\":\"2120\"}},\"EB_29\":{\"eligibility_or_benefit_information_code\":\"U-Contact Following Entity for Eligibility or Benefit Information\",\"coverage_level_code\":\"\",\"service_type_code\":\"88-Pharmacy\"},\"NM1_6\":{\"entity_identifier_code\":\"VN-Vendor\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"PRESCRIPTION SOLUTIONS\"}},\"LE_3\":{\"loop_identifier_code\":\"2120\"}},\"EB_30\":{\"eligibility_or_benefit_information_code\":\"X-Health Care Facility\"},\"NM1_7\":{\"entity_identifier_code\":\"1P-Provider\",\"entity_type_qualifier\":\"2-Non-Person Entity\",\"name_last\":\"AHPROVIDER\",\"name_first\":\"\",\"name_middle\":\"\",\"name_prefix\":\"\",\"name_suffix\":\"\",\"identification_code_qualifier\":\"XX-Centers for Medicare and Medicaid Services National Provider Identifier\",\"identification_code\":\"1104153139\"}},\"LE_4\":{\"loop_identifier_code\":\"2120\"}}}},\"GE\":{\"number_of_transaction_sets\":\"1\",\"group_control_number\":\"813136180\"},\"IEA\":{\"number_of_functional_groups\":\"1\",\"interchange_control_number\":\"813136180\"}}";
			response = Response.status(200).entity(testJSONResponse).build();
		}

		logger.info("<<<EXITED mockEligibilityInquiry()");
		return response;
	}
	
	@Path("/eligibility/servicetype")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public Response retrieveServiceTypeCodes() throws JSONException 
	{	
		logger.info(">>>ENTERED retrieveServiceTypeCodes()");
		
		Response response = null;
		EligibilityService eligibilityService = null;
		int responseCode = 200;
		String responseMessage = null;
		
		try {	
			eligibilityService = new EligibilityService();
			responseMessage = eligibilityService.lookupServiceTypeCodes();
		} catch (Exception e) {
			logger.error("Message could not be processed: " + e.getMessage());
			e.printStackTrace();
			response = Response.status(422).entity("Message could not be processed: " + e.getMessage()).build();
		}
		
		if (response == null) {
			response = Response.status(responseCode).entity(responseMessage).build();
		}
		
		logger.info("<<<EXITED retrieveServiceTypeCodes()");
		return response;
	}
	
	@POST
	@Path("/batch/eligibility")
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Produces(MediaType.TEXT_PLAIN)
	public Response processBatchEligibilityRequest(@FormDataParam("file") InputStream uploadedInputStream,
	                                               @FormDataParam("file") FormDataContentDisposition fileDetails) throws Exception {
		logger.info(">>>ENTERED processBatchEligibilityRequest()");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(baos, "UTF8"));
		try (BufferedReader br = new BufferedReader(new InputStreamReader(uploadedInputStream, "UTF8"))) {
			bw.write("trackingNumber|dateOfService|status|reason|subscriberId|subscriberFirstName|subscriberLastName|subscriberAddress|subscriberCity|subscriberState|subscriberZip|relationship|dependentFirstName|dependentLastName|dependentAddress|dependentCity|dependentState|dependentZip|payer|primaryPayer|secondaryPayer|tertiaryPayer|otherPayer|coverageTypes\n");
			String line;
			
			line = br.readLine();
			if (line != null) {
				if (line.startsWith(UTF8_BOM)) {
		            line = line.substring(1);
		        }
				if (line.startsWith("ISA")) {
					ASCBatchService batchService = new ASCBatchService();
					List<StringBuffer> messageList = null;
					while ((line = br.readLine()) != null) {
						if (line.startsWith(UTF8_BOM)) {
				            line = line.substring(1);
				        }
						try {
							messageList = batchService.splitBatch(line);
							System.out.println("LINE=" + line);
						} catch (Exception e) {
							e.printStackTrace();
							System.out.println("Exception processing file!" + e.getMessage());
						}
					}
					
					List<String> responseList = new ArrayList<String>();
					String inputTrace = "";
					String inputSubscriberFirstName = "";
					String inputSubscriberLastName = "";
					String inputSubscriberIdentifier = "";
					for (int i=0; i<messageList.size(); i++) {
						try {
							X12Message x12Message = null;
							x12Message = new X12Message(messageList.get(i).toString());
							inputTrace = x12Message.getInterchangeControlList().get(0).getSubscriberTrace();
							inputSubscriberFirstName = x12Message.getInterchangeControlList().get(0).getSubscriberFirstName();
							inputSubscriberLastName = x12Message.getInterchangeControlList().get(0).getSubscriberLastName();
							inputSubscriberIdentifier = x12Message.getInterchangeControlList().get(0).getSubscriberIdentifier();
						} catch (InvalidX12MessageException e) {
							e.printStackTrace();
						}
						responseList.add(batchService.sendEligibilityRequest(messageList.get(i).toString()));
					}
					
					for (int i=0; i<responseList.size(); i++) {
						batchService.processJSONMessage(bw, responseList.get(i), inputTrace, inputSubscriberFirstName, inputSubscriberLastName, inputSubscriberIdentifier);
					}
					
				} else if (line.startsWith("trackingNumber|")) {
					DelimitedBatchService batchService = new DelimitedBatchService();
					while ((line = br.readLine()) != null) {
						if (line.startsWith(UTF8_BOM)) {
				            line = line.substring(1);
				        }
						if (!line.startsWith("trackingNumber")) {
							String jsonString = batchService.createEligibilityRequest(line);
							batchService.processJSONMessage(bw, jsonString, line);
						}
					}
				}
			} else {
				System.out.println("Could not read from intput file!");
			}
			br.close();
			bw.close();
		} catch (IOException e) {
			System.out.println("Exception reading from intput file : " + e.getMessage());
			e.printStackTrace();
		}
		logger.info("<<<EXITED processBatchEligibilityRequest()");
	    return Response.ok().entity(new String(baos.toByteArray(), "UTF8")).build();
	}
	
//	@POST
//	@Path("/batch/delimited/eligibility")
//	@Consumes(MediaType.MULTIPART_FORM_DATA)
//	@Produces(MediaType.TEXT_PLAIN)
//	public Response processDelimitedBatchEligibilityRequest(@FormDataParam("file") InputStream uploadedInputStream,
//	                                               @FormDataParam("file") FormDataContentDisposition fileDetails) throws Exception {
//
//		ByteArrayOutputStream baos = new ByteArrayOutputStream();
//		BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(baos, "UTF8"));
//		try (BufferedReader br = new BufferedReader(new InputStreamReader(uploadedInputStream, "UTF8"))) {
//			BatchService batchService = new BatchService();
//			bw.write("trackingNumber|dateOfService|status|reason|subscriberId|subscriberFirstName|subscriberLastName|subscriberAddress|subscriberCity|subscriberState|subscriberZip|relationship|dependentFirstName|dependentLastName|dependentAddress|dependentCity|dependentState|dependentZip|payer|primaryPayer|secondaryPayer|tertiaryPayer|otherPayer|coverageTypes\n");
//			String line;
//
//			while ((line = br.readLine()) != null) {
//				if (line.startsWith(UTF8_BOM)) {
//		            line = line.substring(1);
//		        }
//				if (!line.startsWith("trackingNumber")) {
//					String jsonString = batchService.createEligibilityRequest(line);
//					batchService.processJSONMessage(bw, jsonString, line);
//				}
//			}
//
//			br.close();
//			bw.close();
//		} catch (IOException e) {
//			System.out.println("Exception reading from intput file : " + e.getMessage());
//			e.printStackTrace();
//		}
//	    return Response.ok().entity(new String(baos.toByteArray(), "UTF8")).build();
//	}
	
	@Path("/change/eligibility")
	@POST
	@Consumes(MediaType.TEXT_PLAIN)
	@Produces(MediaType.TEXT_PLAIN)
	public Response sendEligibilityToChangeAsText(@Context HttpHeaders headers, String inputMessage) throws JSONException 
	{	
		Instant apiStart = Instant.now();
		logger.info(">>>ENTERED sendEligibilityToChangeAsText()");
		Response response = null;
		String environmentCode = null;
		String responseText = null;
		int responseCode = HttpStatus.SC_ACCEPTED;
		Duration targetDuration = null;
		Duration apiDuration = null;
		EligibilityService eligibilityService = new EligibilityService();

		try {
			environmentCode = (headers.getRequestHeader("EnvironmentCode")!=null)?headers.getRequestHeader("EnvironmentCode").get(0):"T";
			
			Instant emdeonStart = Instant.now();
			responseText = eligibilityService.postInquiryStraightThruToEmdeon(environmentCode, inputMessage);
			Instant emdeonEnd = Instant.now();
			targetDuration = Duration.between(emdeonStart,  emdeonEnd);
			System.out.println("The call to Change took: <<<" + targetDuration + ">>>");
			logger.info("The call to Change took: <<<" + targetDuration + ">>>");
		} catch (Exception e) {
			logger.error("Could not connect to Emdeon: " + e.getMessage());
			e.printStackTrace();
			response = Response.status(HttpStatus.SC_UNPROCESSABLE_ENTITY).entity("Could not connect to Emdeon : " + e.getMessage()).build();
		}
	
		response = Response.status(responseCode).entity(responseText).build();
		logger.info("<<<EXITED sendEligibilityToChangeAsText()");
		Instant apiEnd = Instant.now();
		apiDuration = Duration.between(apiStart,  apiEnd);
		System.out.println("The total API time was: <<<" + apiDuration + ">>>");
		logger.info("The total API time was: <<<" + apiDuration + ">>>");
		
		return response;
	}
	
}
