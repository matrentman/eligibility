package com.mtlogic.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DelimitedBatchService extends BatchService {
	final Logger logger = LoggerFactory.getLogger(DelimitedBatchService.class);
	
	public DelimitedBatchService() {
	}

	public String createEligibilityRequest(String line) {
		logger.info(">>>ENTERED createEligibilityRequest()");
		
		String[] lineData = line.split("\\|");
		String trackingNumber = lineData[0];
		String npi = lineData[1];
		String payerCode = lineData[2];
		String payerName = lineData[3];
		String subscriberId = lineData[4];
		String subscriberFirstName = lineData[5];
		String subscriberLastName = lineData[6];
		String subscriberDateOfBirth = lineData[7];
		String dependentFirstName = lineData[8];
		String dependentLastName = lineData[9];
		String dependentDateOfBirth = lineData[10];
		String dateOfService = lineData[11];

		String url = "http://localhost:8080/eligibility-0.0.1-SNAPSHOT/rest/api/form/eligibility";
		if (EligibilityService.LOCAL_DEV) {
			url = "http://localhost:8080/eligibility/rest/api/form/eligibility";
		}
		
		HttpClient client = HttpClientBuilder.create().build();
		HttpPost post = new HttpPost(url);

		post.setHeader("User-Agent", EligibilityService.USER_AGENT);
		post.setHeader("Content-Type", "application/x-www-form-urlencoded");
		post.setHeader("EnvironmentCode", "P");
		post.setHeader("apitoken", token);

//!!!Temporary KLUDGE for expired NPI!!!
if (npi.equals("1366586422")) {
	npi = "1346326105";
}
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("npi", npi));
		urlParameters.add(new BasicNameValuePair("payercode", payerCode));
		urlParameters.add(new BasicNameValuePair("payername", payerName));
		urlParameters.add(new BasicNameValuePair("memberid", subscriberId));
		urlParameters.add(new BasicNameValuePair("firstname", subscriberFirstName.trim()));
		urlParameters.add(new BasicNameValuePair("lastname", subscriberLastName.trim()));
		urlParameters.add(new BasicNameValuePair("dependentfirstname", dependentFirstName.trim()));
		urlParameters.add(new BasicNameValuePair("dependentlastname", dependentLastName.trim()));
		urlParameters.add(new BasicNameValuePair("dob", (subscriberDateOfBirth!=null && !subscriberDateOfBirth.isEmpty())?subscriberDateOfBirth:dependentDateOfBirth));
		urlParameters.add(new BasicNameValuePair("dos", dateOfService));
		urlParameters.add(new BasicNameValuePair("trace", trackingNumber));

		try {
			post.setEntity(new UrlEncodedFormEntity(urlParameters));
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			System.out.println("ERROR!!! : " + e.getMessage());
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("ERROR!!! : " + e.getMessage());
		}

		HttpResponse response;
		StringBuilder result = new StringBuilder();
		try {
			response = client.execute(post);
			
			System.out.println("Response Code : "
	                + response.getStatusLine().getStatusCode());

			BufferedReader rd = new BufferedReader(
			        new InputStreamReader(response.getEntity().getContent()));
		
			String reponseLine = "";
			while ((reponseLine = rd.readLine()) != null) {
				result.append(reponseLine);
			}
		} catch (IOException e) {
			System.out.println("ERROR!!! : " + e.getMessage());
			e.printStackTrace();
		}
		
		logger.info("<<<EXITED createEligibilityRequest()");
		return result.toString();
	}
	
	public void processJSONMessage(BufferedWriter bw, String jsonString, String line) {
		logger.info(">>>ENTERED processJSONMessage()");
		JSONObject jsonObject = null;
		
		String[] lineData = line.split("\\|");
		String inputTrackingNumber = lineData[0];
		String inputNpi = lineData[1];
		String inputPayerCode = lineData[2];
		String inputPayerName = lineData[3];
		String inputSubscriberId = lineData[4];
		String inputSubscriberFirstName = lineData[5];
		String inputSubscriberLastName = lineData[6];
		String inputSubscriberDateOfBirth = lineData[7];
		String inputDependentFirstName = lineData[8];
		String inputDependentLastName = lineData[9];
		String inputDependentDateOfBirth = lineData[10];
		String inputDateOfService = lineData[11];
		
		try {
			try {
				jsonObject = new JSONObject(jsonString);
				System.out.println("JSON - " + jsonString);
			} catch (JSONException e) {
				System.out.println("Exception while parsing JSON 271 : " + e.getMessage());
				e.printStackTrace();
			}
			
			String errorCode = null;
			if (jsonString.contains("AAA")) {
				try {
					JSONObject loop2000C = jsonObject.optJSONObject("2000C_0");
					if (loop2000C != null) {
						JSONObject loop2100C = loop2000C.optJSONObject("2100C_0");
						if (loop2100C != null) {
							JSONObject aaaSegment = loop2100C.optJSONObject("AAA_0");
							if (aaaSegment != null) {
								errorCode = getJsonString(aaaSegment, "reject_reason_code");
							}
						}
					}
				} catch (Exception e) {
					System.out.println("Exception parsing error code : " + e.getMessage());
					e.printStackTrace();
				}
				if (errorCode == null) {
					try {
						JSONObject loop2000A = jsonObject.optJSONObject("2000A_0");
						if (loop2000A != null) {
							JSONObject aaaSegment = loop2000A.optJSONObject("AAA_0");
							if (aaaSegment != null) {
								errorCode = getJsonString(aaaSegment, "reject_reason_code");
							}
						}
					} catch (Exception e) {
						System.out.println("Exception parsing error code : " + e.getMessage());
						e.printStackTrace();
					}
				}
				if (errorCode == null) {
					try {
						JSONObject loop2000A = jsonObject.optJSONObject("2000A_0");
						if (loop2000A != null) {
							JSONObject loop2100A = loop2000A.optJSONObject("2100A_0");
							if (loop2100A != null) {
								JSONObject aaaSegment = loop2100A.optJSONObject("AAA_0");
								if (aaaSegment != null) {
									errorCode = getJsonString(aaaSegment, "reject_reason_code");
								}
							}
						}
					} catch (Exception e) {
						System.out.println("Exception parsing error code : " + e.getMessage());
						e.printStackTrace();
					}
				}
				if (errorCode == null) {
					try {
						JSONObject loop2000B = jsonObject.optJSONObject("2000B_0");
						if (loop2000B != null) {
							JSONObject loop2100B = loop2000B.optJSONObject("2100B_0");
							if (loop2100B != null) {
								JSONObject aaaSegment = loop2100B.optJSONObject("AAA_0");
								if (aaaSegment != null) {
									errorCode = getJsonString(aaaSegment, "reject_reason_code");
								}
							}
						}
						
						errorCode = getJsonString(jsonObject.getJSONObject("2000B_0").getJSONObject("2100B_0").getJSONObject("AAA_0"), "reject_reason_code");
					} catch (Exception e) {
						System.out.println("Exception parsing error code : " + e.getMessage());
						e.printStackTrace();
					}
				}
				if (errorCode == null) {
					try {
						JSONObject loop2000C = jsonObject.optJSONObject("2000C_0");
						if (loop2000C != null) {
							JSONObject loop2100C = loop2000C.optJSONObject("2100C_0");
							if (loop2100C != null) {
								Iterator iter = loop2100C.keys();
								while( iter.hasNext() ) {
								    String key = (String)iter.next();
								    if ( key.startsWith("2110C_") ) {
								    	JSONObject loop2110C = loop2100C.getJSONObject(key);
								    	JSONObject aaaSegment = loop2110C.optJSONObject("AAA_0");
										if (aaaSegment != null) {
											errorCode = getJsonString(aaaSegment, "reject_reason_code");
										}
								    }
								}
							}
						}						
					} catch (Exception e) {
						System.out.println("Exception parsing error code : " + e.getMessage());
						e.printStackTrace();
					}
				}
				if (errorCode == null) {
					try {
						JSONObject loop2000D = jsonObject.optJSONObject("2000D_0");
						if (loop2000D != null) {
							JSONObject loop2100D = loop2000D.optJSONObject("2100D_0");
							if (loop2100D != null) {
								JSONObject aaaSegment = loop2100D.optJSONObject("AAA_0");
								if (aaaSegment != null) {
									errorCode = getJsonString(aaaSegment, "reject_reason_code");
								}
							}
						}
					} catch (Exception e) {
						System.out.println("Exception parsing error code : " + e.getMessage());
						e.printStackTrace();
					}
				}
				if (errorCode == null) {
					try {
						JSONObject loop2000D = jsonObject.optJSONObject("2000D_0");
						if (loop2000D != null) {
							JSONObject loop2100D = loop2000D.optJSONObject("2100D_0");
							if (loop2100D != null) {
								Iterator iter = loop2100D.keys();
								while( iter.hasNext() ) {
								    String key = (String)iter.next();
								    if ( key.startsWith("2110D_") ) {
								    	JSONObject loop2110D = loop2100D.getJSONObject(key);
								    	JSONObject aaaSegment = loop2110D.optJSONObject("AAA_0");
										if (aaaSegment != null) {
											errorCode = getJsonString(aaaSegment, "reject_reason_code");
										}
								    }
								}
							}
						}
					} catch (Exception e) {
						System.out.println("Exception parsing error code : " + e.getMessage());
						e.printStackTrace();
					}
				}
			}
			if ("71".equals(errorCode)) {
				errorCode = "Patient Birth Date Does Not Match That for the Patient on the Database";
			}
			if ("72".equals(errorCode)) {
				errorCode = "Invalid/Missing Subscriber/Insured ID";
			}
			if ("73".equals(errorCode)) {
				errorCode = "Invalid/Missing Subscriber/Insured Name";
			}
			
			String dependentFirstName = "";
			String dependentLastName = "";
			String dependentAddress = "";
			String dependentCity = "";
			String dependentState = "";
			String dependentPostalCode = "";
			String subscriberFirstName = "";
			String subscriberLastName = "";
			String subscriberAddress = "";
			String subscriberCity = "";
			String subscriberState = "";
			String subscriberPostalCode = "";
			String subscriberIdentifier = "";
			String traceIdentifier = "";
			String relationshipCode = null;
			boolean hasDependent = false;
			try {
				JSONObject loop2000D = jsonObject.optJSONObject("2000D_0");
				if (loop2000D != null) {
					hasDependent = true;
					JSONObject trnSegment = loop2000D.optJSONObject("TRN_0");
					if (trnSegment != null) {
						traceIdentifier = getJsonString(trnSegment, "reference_identification");
					}
					JSONObject loop2100D = loop2000D.optJSONObject("2100D_0");
					Iterator iter = loop2100D.keys();
					while( iter.hasNext() ) {
					    String key = (String)iter.next();
					    if ( key.startsWith("INS_") ) {
					    	JSONObject insSegment = loop2100D.getJSONObject(key);
					    	String relationship = getJsonString(insSegment, "individual_relationship_code");
					    	if (relationshipCode == null && relationship != null) {
					    		relationshipCode = relationship;
					    	}
					    }
					    if (key.startsWith("NM1_")) {
					    	JSONObject nm1Segment = loop2100D.getJSONObject(key);
					    	dependentFirstName = getJsonString(nm1Segment, "name_first");
							dependentLastName = getJsonString(nm1Segment, "name_last");
					    }
					    if (key.startsWith("N3_")) {
					    	JSONObject n3Segment = loop2100D.getJSONObject(key);
					    	dependentAddress = getJsonString(n3Segment, "address_information");
					    }
					    if (key.startsWith("N4_")) {
					    	JSONObject n4Segment = loop2100D.getJSONObject(key);
					    	dependentCity = getJsonString(n4Segment, "city_name");
							dependentState = getJsonString(n4Segment, "state_code");
							dependentPostalCode = getJsonString(n4Segment, "postal_code");
					    }
					}
				}
				
				JSONObject loop2000C = jsonObject.optJSONObject("2000C_0");
				if (loop2000C != null) {
					JSONObject trnSegment = loop2000C.optJSONObject("TRN_0");
					if (trnSegment != null) {
						traceIdentifier = getJsonString(trnSegment, "reference_identification");
					}
					JSONObject loop2100C = loop2000C.optJSONObject("2100C_0");
					Iterator iter = loop2100C.keys();
					while( iter.hasNext() ) {
					    String key = (String)iter.next();
					    if ( key.startsWith("INS_") ) {
					    	JSONObject insSegment = loop2100C.getJSONObject(key);
					    	String relationship = getJsonString(insSegment, "individual_relationship_code");
					    	if (relationshipCode == null && relationship != null) {
					    		relationshipCode = relationship;
					    	}
					    }
					    if (key.startsWith("NM1_")) {
					    	JSONObject nm1Segment = loop2100C.getJSONObject(key);
					    	subscriberFirstName = getJsonString(nm1Segment, "name_first");
							subscriberLastName = getJsonString(nm1Segment, "name_last");
							subscriberIdentifier = getJsonString(nm1Segment, "identification_code");
					    }
					    if (key.startsWith("N3_")) {
					    	JSONObject n3Segment = loop2100C.getJSONObject(key);
					    	subscriberAddress = getJsonString(n3Segment, "address_information");
					    }
					    if (key.startsWith("N4_")) {
					    	JSONObject n4Segment = loop2100C.getJSONObject(key);
					    	subscriberCity = getJsonString(n4Segment, "city_name");
							subscriberState = getJsonString(n4Segment, "state_code");
							subscriberPostalCode = getJsonString(n4Segment, "postal_code");
					    }
					}
				}
			} catch (Exception e) {
				System.out.println("Exception parsing coverage window : " + e.getMessage());
				e.printStackTrace();
			}
			
			String healthPlan = "";
			try {
				//Not sure we always get have this or how to reliably obtain it
				//would need to iterate over all EB segments and look for code 30 in service_type_code where plan_coverage_description is not null
				//Will need to loop through each EB segment here
//					int index2110 = 0;
//					int indexEB = 0;
//					boolean isValid = true;
//					JSONObject loop2000C = jsonObject.optJSONObject("2000C_0");
//					if (loop2000C != null) {
//						JSONObject loop2100C = loop2000C.optJSONObject("2100C_0");
//						if (loop2100C != null) {
//							while(isValid) {
//								String searchName = "2110C_" + Integer.toString(index2110) + "_0";
//								JSONObject loop = loop2100C.optJSONObject(searchName);
//								if (loop != null) {
//									index2110++;
//									searchName = "EB_" + Integer.toString(indexEB);
//									JSONObject segment = loop.optJSONObject(searchName);
//									if (segment != null) {
//										indexEB++;
//										if (segment.optString("service_type_code").equals("30")) {
//											if (!segment.getString("plan_coverage_description").isEmpty()) {
//												healthPlan = segment.getString("plan_coverage_description");
//												isValid = false;
//											}
//										}
//									} else {
//										isValid = false;
//									}
//								} else {
//									isValid = false;
//								}
//							}
//						}
//					}
				
			} catch (Exception e) {
				System.out.println("Exception parsing health plan : " + e.getMessage());
				e.printStackTrace();
			}
			String dateQualifier = "";
			String coverageWindow = "";
			try {
				JSONObject loop2000C = null;;
				JSONObject loop2100C = null;
				if (hasDependent) {
					loop2000C = jsonObject.optJSONObject("2000D_0");
					if (loop2000C != null) {
						loop2100C = loop2000C.optJSONObject("2100D_0");
					}
				} else {
					loop2000C = jsonObject.optJSONObject("2000C_0");
					if (loop2000C != null) {
						loop2100C = loop2000C.optJSONObject("2100C_0");
					}
				}
				
				if (loop2100C != null) {
					JSONObject segment = loop2100C.optJSONObject("DTP_0");
					if (segment != null) {
						dateQualifier = getJsonString(segment, "date_time_qualifier");
						coverageWindow = getJsonString(segment, "date_time_period");
					} 
				}
			} catch (Exception e) {
				System.out.println("Exception parsing coverage window : " + e.getMessage());
				e.printStackTrace();
			}
			String payerName = "";
			String payerId = "";
			try {
				JSONObject loop2000A = jsonObject.getJSONObject("2000A_0");
				JSONObject loop = loop2000A.getJSONObject("2100A_0");
				JSONObject segment = loop.getJSONObject("NM1_0");
				payerName = getJsonString(segment, "name_last").replace(",", " ");
				payerId = getJsonString(segment, "identification_code");
			} catch (Exception e) {
				System.out.println("Exception parsing payer name : " + e.getMessage());
				e.printStackTrace();
			}
			String primaryPayerName = "";
			String primaryPayerId = "";
			String otherPayerName = "";
			String otherPayerId = "";
			String secondaryPayerName = "";
			String secondaryPayerId = "";
			String tertiaryPayerName = "";
			String tertiaryPayerId = "";
			String serviceTypeCodes = "";
			Map<String, String> serviceMap = new HashMap<String, String>();
			try {
				int index2110 = 0;
				int index2120 = 0;
				boolean isValid = true;
				JSONObject loop2000 = null; 
				JSONObject loop2100 = null;
				if (hasDependent) {
					loop2000 = jsonObject.optJSONObject("2000D_0");
					if (loop2000 != null) {
						loop2100 = loop2000.optJSONObject("2100D_0");
					}
				} else {
					loop2000 = jsonObject.optJSONObject("2000C_0");
					if (loop2000 != null) {
						loop2100 = loop2000.optJSONObject("2100C_0");
					}
				}
				if (loop2100 != null) {
					boolean otherPayerFlag = false;
						
					Iterator loop2100Iter = loop2100.keys();
					while( loop2100Iter.hasNext() ) {
					    String loop2100Key = (String)loop2100Iter.next();
					    if ( loop2100Key.startsWith("2110") ) {				
					    	String serviceTypeCode = null;
					    	JSONObject loop2110 = loop2100.getJSONObject(loop2100Key);
					    	if (loop2110 != null) {
					    		//retrieve the EB segment
					    		Iterator loop2110Iter = loop2110.keys();
					    		while( loop2110Iter.hasNext() ) {
					    			String loop2110Key = (String)loop2110Iter.next();
					    			if ( loop2110Key.startsWith("EB") ) {
					    				JSONObject ebSegment = loop2110.getJSONObject(loop2110Key);
					    				
					    				serviceTypeCode = getJsonString(ebSegment, "service_type_code");
						    	
					    				String ebic = getJsonString(ebSegment, "eligibility_or_benefit_information_code");
					    				otherPayerFlag = ("R-Other or Additional Payor".equalsIgnoreCase(ebic))?true:false;
					    				//Grab all of the actively covered service codes
					    				if ("1-Active Coverage".equals(ebic)) {
				    						String[] serviceTypeArray = serviceTypeCode.split("\\^");
				    						StringBuilder sb = new StringBuilder();
				    						for (int i=0; i<serviceTypeArray.length;i++) {
				    							if (!serviceMap.containsKey(serviceTypeArray[i])) {
				    								serviceMap.put(serviceTypeArray[i], serviceTypeArray[i]);
				    							}
				    							sb.append(serviceTypeArray[i].split("-")[0]);
				    							if (i<serviceTypeArray.length-1) {
				    								sb.append(":");
				    							}
				    						}
				    						serviceTypeCodes = sb.toString();
					    				}
					    			}
					    			if ( loop2110Key.startsWith("2120") ) {
					    				primaryPayerName = "";
					    				otherPayerName = "";
					    				secondaryPayerName = "";
					    				tertiaryPayerName = "";
					    				JSONObject loop2120 = loop2110.getJSONObject(loop2110Key);
					    			
					    				Iterator loop2120Iter = loop2120.keys();
						    			while( loop2120Iter.hasNext() ) {
						    				String loop2120Key = (String)loop2120Iter.next();
						    				if ( loop2120Key.startsWith("NM1") ) {
						    					JSONObject segment = loop2120.getJSONObject(loop2120Key);
						    					String eic = getJsonString(segment, "entity_identifier_code");
						    					//if eic = PR, PRP, SEP or TTP
						    					// then get the serviceTypecode from the related EB segment
						    					String typeCode = null;
						    					JSONObject ebSegment = null;
						    					if ("PR-Payer".equals(eic) || "PRP-Primary Payer".equals(eic) || "SEP-Secondary Payer".equals(eic) || "TTP-Tertiary Payer".equals(eic)) {
						    						Iterator tempIter = loop2110.keys();
									    			while( tempIter.hasNext() ) {
									    				String tempKey = (String)tempIter.next();
									    				if ( tempKey.startsWith("EB") ) {
									    					ebSegment = loop2110.getJSONObject(tempKey);
									    					typeCode = getJsonString(ebSegment, "service_type_code");
									    				}
									    			}
						    					}
						    					
						    					if (typeCode != null && (typeCode.contains("30-Health Benefit Plan Coverage") || typeCode.isEmpty())) {
						    						if ("PRP-Primary Payer".equalsIgnoreCase(eic)) {
						    							primaryPayerName = getJsonString(segment, "name_last").replace(",", " ");
						    							primaryPayerId = getJsonString(segment, "identification_code");
						    						}
						    						if ("PR-Payer".equalsIgnoreCase(eic)) {
						    							otherPayerName = getJsonString(segment, "name_last").replace(",", " ");
						    							otherPayerId = getJsonString(segment, "identification_code");
						    						}
						    						if ("SEP-Secondary Payer".equalsIgnoreCase(eic)) {
						    							secondaryPayerName = getJsonString(segment, "name_last").replace(",", " ");
						    							secondaryPayerId = getJsonString(segment, "identification_code");
						    						}
						    						if ("TTP-Tertiary Payer".equalsIgnoreCase(eic)) {
						    							tertiaryPayerName = getJsonString(segment, "name_last").replace(",", " ");
						    							tertiaryPayerId = getJsonString(segment, "identification_code");
						    						}
						    					}
						    				}
						    			}	
					    			}
					    		}
					    	}
					    }
					}
				}
			} catch (Exception e) {
				System.out.println("Exception parsing primary payer name : " + e.getMessage());
				e.printStackTrace();
			}
			
			StringBuilder subscriberServices = new StringBuilder();
			Iterator it = serviceMap.entrySet().iterator();
		    while (it.hasNext()) {
		        Map.Entry pair = (Map.Entry)it.next();
		        subscriberServices.append(pair.getKey());
		        subscriberServices.append(":");
		        it.remove(); // avoids a ConcurrentModificationException
		    }
			
			String content = null;
			if (errorCode != null) {
				content = ((traceIdentifier==null || traceIdentifier.isEmpty())?inputTrackingNumber:traceIdentifier) + "|" + "REJECTED" + "|" + ((errorCode==null)?"":errorCode) + "|" + ((subscriberIdentifier==null || subscriberIdentifier.isEmpty())?inputSubscriberId:subscriberIdentifier) + "|" + ((subscriberFirstName==null || subscriberFirstName.isEmpty())?inputSubscriberFirstName:subscriberFirstName) + "|" + ((subscriberLastName==null || subscriberLastName.isEmpty())?inputSubscriberLastName:subscriberLastName) + "|" + subscriberAddress + "|" + subscriberCity + "|" + subscriberState + "|" + subscriberPostalCode + "|" + relationshipCode + "|" + dependentFirstName + "|" + dependentLastName + "|" + dependentAddress + "|" + dependentCity + "|" + dependentState + "|" + dependentPostalCode + "|" + payerName + "|" + primaryPayerName + "|" + secondaryPayerName + "|" + tertiaryPayerName + "|" + otherPayerName + "|" + subscriberServices.toString() + "\n";
			} else {
				content = ((traceIdentifier==null || traceIdentifier.isEmpty())?inputTrackingNumber:traceIdentifier) + "|" + "ACCEPTED" + "|" + ((errorCode==null)?"":errorCode) + "|" + ((subscriberIdentifier==null || subscriberIdentifier.isEmpty())?inputSubscriberId:subscriberIdentifier) + "|" + ((subscriberFirstName==null || subscriberFirstName.isEmpty())?inputSubscriberFirstName:subscriberFirstName) + "|" + ((subscriberLastName==null || subscriberLastName.isEmpty())?inputSubscriberLastName:subscriberLastName) + "|" + subscriberAddress + "|" + subscriberCity + "|" + subscriberState + "|" + subscriberPostalCode + "|" + relationshipCode + "|" + dependentFirstName + "|" + dependentLastName + "|" + dependentAddress + "|" + dependentCity + "|" + dependentState + "|" + dependentPostalCode + "|" + payerName + "|" + primaryPayerName + "|" + secondaryPayerName + "|" + tertiaryPayerName + "|" + otherPayerName + "|" + subscriberServices.toString() + "\n";
			}
			bw.write(content);
			bw.flush();

		} catch (IOException e) {
			System.out.println("Exception writing to output file : " + e.getMessage());
			e.printStackTrace();
		}
		
		logger.info("<<<EXITED processJSONMessage()");
	}
	
}
