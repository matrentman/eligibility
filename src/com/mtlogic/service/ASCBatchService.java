package com.mtlogic.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpStatus;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ASCBatchService extends BatchService {
	final Logger logger = LoggerFactory.getLogger(ASCBatchService.class);
	
	public ASCBatchService() {
	}
	
	public List<StringBuffer> splitBatch(String line) {
		String previousLoop = null;
		StringBuffer beginEnvelope = new StringBuffer();
		StringBuffer endEnvelope = new StringBuffer();
		StringBuffer tempMessage = null;
		StringBuffer tempSubscriber = null;
		List<StringBuffer> messageList = new ArrayList<StringBuffer>();
		List<StringBuffer> subscriberList = new ArrayList<StringBuffer>();
		String seSegment = "";
		int messageIndex = 0;
		// replace message delimiters with standard delimiters
		String[] segments = line.split("~");
		for (int i = 0; i< segments.length; i++) {
			String[] elements = segments[i].split("\\*");
			if (elements[0].equalsIgnoreCase("ISA")
				|| elements[0].equalsIgnoreCase("GS")
				|| elements[0].equalsIgnoreCase("ST")
				|| elements[0].equalsIgnoreCase("BHT")) {
				beginEnvelope.append(segments[i]);
				beginEnvelope.append("~");
				continue;
			}
			if (elements[0].equalsIgnoreCase("IEA")
				|| elements[0].equalsIgnoreCase("GE")) {
				endEnvelope.append(segments[i]);
				endEnvelope.append("~");
				continue;
			}
			if (elements[0].equalsIgnoreCase("SE")) {
				if (tempMessage != null) {
					if (tempSubscriber.length() > 0) {
						subscriberList.add(tempSubscriber);
					}
					for (int j=0; j<subscriberList.size(); j++) {
						StringBuffer newMessage = new StringBuffer();
						newMessage.append(beginEnvelope);
						newMessage.append(tempMessage);
						newMessage.append(subscriberList.get(j));
						messageList.add(newMessage);
						messageIndex++;
					}
					tempMessage = new StringBuffer();
					tempSubscriber = new StringBuffer();
					subscriberList.clear();
				}
				seSegment = segments[i] + "~";
				continue;
			}
			if (elements[0].equalsIgnoreCase("HL") && elements[3].equals("20")) {
				if (tempMessage != null) {
					if (tempSubscriber.length() > 0) {
						subscriberList.add(tempSubscriber);
					}
					for (int j=0; j <subscriberList.size(); j++) {
						StringBuffer newMessage = new StringBuffer();
						newMessage.append(beginEnvelope);
						newMessage.append(tempMessage);
						newMessage.append(subscriberList.get(j));
						messageList.add(newMessage);
						messageIndex++;
					}
					tempMessage =  new StringBuffer();
					tempSubscriber = new StringBuffer();
					subscriberList.clear();
				}
				tempMessage =  new StringBuffer();
				tempMessage.append(setHLIndex(segments[i], 1));
				tempMessage.append("~");
				previousLoop = "PAYER";
				continue;
			}
			if (elements[0].equalsIgnoreCase("HL") && elements[3].equals("21")) {
				tempMessage.append(setHLIndex(segments[i], 2));
				tempMessage.append("~");
				previousLoop = "PROVIDER";
				continue;
			}
			if (elements[0].equalsIgnoreCase("HL") && elements[3].equals("22")) {
				if (previousLoop.equalsIgnoreCase("PROVIDER")) {
					tempSubscriber = new StringBuffer();
					StringBuffer hlSegment = new StringBuffer(setHLIndex(segments[i], 3));
					hlSegment.setCharAt(hlSegment.length()-1, '0');
					tempSubscriber.append(hlSegment);
					tempSubscriber.append("~");
					previousLoop = "SUBSCRIBER";
					continue;
				}
				if (previousLoop.equalsIgnoreCase("SUBSCRIBER")) {
					subscriberList.add(tempSubscriber);
					tempSubscriber = new StringBuffer();
					StringBuffer hlSegment = new StringBuffer(setHLIndex(segments[i], 3));
					hlSegment.setCharAt(hlSegment.length()-1, '0');
					tempSubscriber.append(hlSegment);
					tempSubscriber.append("~");
					previousLoop = "SUBSCRIBER";
					continue;
				}
			}
			if (previousLoop.equalsIgnoreCase("SUBSCRIBER")) {
				if (elements[0].equalsIgnoreCase("HL") && elements[3].equals("23")) {
					//Need to set Hierarchical child code to 0 for the subscriber HL segment
					tempSubscriber.setCharAt(10, '1');
					StringBuffer hlSegment = new StringBuffer(setHLIndex(segments[i], 4));
					hlSegment.setCharAt(hlSegment.length()-1, '0');
					tempSubscriber.append(hlSegment);
				} else if (elements[0].equalsIgnoreCase("EQ")) {
					//Change Service Type Code to 30
					String[] eqElements = segments[i].split("\\*");
					eqElements[1] = "30";
					StringBuffer eqSegment = new StringBuffer();
					for (int k=0; k<eqElements.length; k++) {
						eqSegment.append(eqElements[k]);
						if (k!=eqElements.length-1) {
							eqSegment.append("*");
						}
					}
					tempSubscriber.append(eqSegment);
				} else {
					tempSubscriber.append(segments[i]);
				}
				tempSubscriber.append("~");
			} else {
				tempMessage.append(segments[i]);
				tempMessage.append("~");
			}
		}
		
		for (int i=0; i<messageList.size(); i++) {
			//Set total # of segments in SE[1] then Append SE segment
			int size = messageList.get(i).substring(messageList.get(i).indexOf("~ST"), messageList.get(i).length()).split("~").length;
			String[] elements = seSegment.split("\\*");
			elements[1] = Integer.toString(size);
			seSegment = "SE*" + Integer.toString(size) + "*" + elements[2];
			messageList.get(i).append(seSegment);
			messageList.get(i).append(endEnvelope);
		}
		
		return messageList;
	}
	
	public static String setHLIndex(String segment, int index) {
		String[] elements = segment.split("\\*");
		String hlSegment = "";
		if (index == 1) {
			hlSegment = elements[0] + "*" + Integer.toString(index) + "**" + elements[3] + "*1";
		} else {
			hlSegment = elements[0] + "*" + Integer.toString(index) + "*" + Integer.toString(index-1) + "*" + elements[3] + "*1";
		}
		return hlSegment;
	}
	
	public void processJSONMessage(BufferedWriter bw, String jsonString, String inputTrace, String inputSubscriberFirstName, String inputSubscriberLastName, String inputSubscriberIdentifier) {
		JSONObject jsonObject = null;
		
		try {
			try {
				jsonObject = new JSONObject(jsonString);
				System.out.println(jsonString);
			} catch (JSONException e) {
				e.printStackTrace();
				System.out.println("Exception while parsing JSON 271 : " + e.getMessage());
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
					e.printStackTrace();
					System.out.println("Exception parsing error code : " + e.getMessage());
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
						e.printStackTrace();
						System.out.println("Exception parsing error code : " + e.getMessage());
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
						e.printStackTrace();
						System.out.println("Exception parsing error code : " + e.getMessage());
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
						e.printStackTrace();
						System.out.println("Exception parsing error code : " + e.getMessage());
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
						e.printStackTrace();
						System.out.println("Exception parsing error code : " + e.getMessage());
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
						e.printStackTrace();
						System.out.println("Exception parsing error code : " + e.getMessage());
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
						e.printStackTrace();
						System.out.println("Exception parsing error code : " + e.getMessage());
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
			String relationshipCode = "18-Self";
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
					    	if (relationship != null) {
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
					    	if (relationship != null) {
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
				e.printStackTrace();
				System.out.println("Exception parsing coverage window : " + e.getMessage());
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
				e.printStackTrace();
				System.out.println("Exception parsing health plan : " + e.getMessage());
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
				e.printStackTrace();
				System.out.println("Exception parsing coverage window : " + e.getMessage());
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
				e.printStackTrace();
				System.out.println("Exception parsing payer name : " + e.getMessage());
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
					    				otherPayerFlag = ebic.equals("R-Other or Additional Payor")?true:false;
					    				//Grab all of the actively covered service codes
					    				if (ebic.equals("1-Active Coverage")) {
				    						String[] serviceTypeArray = serviceTypeCode.split("\\^");
				    						StringBuffer sb = new StringBuffer();
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
						    					if (eic.equals("PR-Payer") || eic.equals("PRP-Primary Payer") || eic.equals("SEP-Secondary Payer") || eic.equals("TTP-Tertiary Payer") ) {
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
						    						if (eic != null && eic.equalsIgnoreCase("PRP-Primary Payer")) {
						    							primaryPayerName = getJsonString(segment, "name_last").replace(",", " ");
						    							primaryPayerId = getJsonString(segment, "identification_code");
						    						}
						    						if (eic != null && eic.equalsIgnoreCase("PR-Payer")) {
						    							otherPayerName = getJsonString(segment, "name_last").replace(",", " ");
						    							otherPayerId = getJsonString(segment, "identification_code");
						    						}
						    						if (eic != null && eic.equalsIgnoreCase("SEP-Secondary Payer")) {
						    							secondaryPayerName = getJsonString(segment, "name_last").replace(",", " ");
						    							secondaryPayerId = getJsonString(segment, "identification_code");
						    						}
						    						if (eic != null && eic.equalsIgnoreCase("TTP-Tertiary Payer")) {
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
				e.printStackTrace();
				System.out.println("Exception parsing primary payer name : " + e.getMessage());
			}
			
			StringBuffer subscriberServices = new StringBuffer();
			Iterator it = serviceMap.entrySet().iterator();
		    while (it.hasNext()) {
		        Map.Entry pair = (Map.Entry)it.next();
		        subscriberServices.append(pair.getKey());
		        subscriberServices.append(":");
		        it.remove(); // avoids a ConcurrentModificationException
		    }
			
			String content = null;
			if (errorCode != null) {
				content = ((traceIdentifier==null || traceIdentifier.isEmpty())?inputTrace:traceIdentifier) + ", " + "REJECTED" + ", " + ((errorCode==null)?"":errorCode) + ", " + ((subscriberIdentifier==null || subscriberIdentifier.isEmpty())?inputSubscriberIdentifier:subscriberIdentifier) + ", " + ((subscriberFirstName==null || subscriberFirstName.isEmpty())?inputSubscriberFirstName:subscriberFirstName) + ", " + ((subscriberLastName==null || subscriberLastName.isEmpty())?inputSubscriberLastName:subscriberLastName) + ", " + subscriberAddress + ", " + subscriberCity + ", " + subscriberState + ", " + subscriberPostalCode + ", " + relationshipCode + ", " + dependentFirstName + ", " + dependentLastName + ", " + dependentAddress + ", " + dependentCity + ", " + dependentState + ", " + dependentPostalCode + ", " + payerName + ", " + primaryPayerName + ", " + secondaryPayerName + ", " + tertiaryPayerName + ", " + otherPayerName + ", " + subscriberServices.toString() + "\n";
			} else {
				content = ((traceIdentifier==null || traceIdentifier.isEmpty())?inputTrace:traceIdentifier) + ", " + "ACCEPTED" + ", " + ((errorCode==null)?"":errorCode) + ", " + ((subscriberIdentifier==null || subscriberIdentifier.isEmpty())?inputSubscriberIdentifier:subscriberIdentifier) + ", " + ((subscriberFirstName==null || subscriberFirstName.isEmpty())?inputSubscriberFirstName:subscriberFirstName) + ", " + ((subscriberLastName==null || subscriberLastName.isEmpty())?inputSubscriberLastName:subscriberLastName) + ", " + subscriberAddress + ", " + subscriberCity + ", " + subscriberState + ", " + subscriberPostalCode + ", " + relationshipCode + ", " + dependentFirstName + ", " + dependentLastName + ", " + dependentAddress + ", " + dependentCity + ", " + dependentState + ", " + dependentPostalCode + ", " + payerName + ", " + primaryPayerName + ", " + secondaryPayerName + ", " + tertiaryPayerName + ", " + otherPayerName + ", " + subscriberServices.toString() + "\n";
			}
			bw.write(content);
			bw.flush();


		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Exception writing to output file : " + e.getMessage());
		}

	}
	
	public String sendEligibilityRequest(String eligibilityRequest) {

		String responseString = null;
		try {
			URL url = new URL("http://localhost:8080/eligibility/rest/api/eligibility");
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "text/plain");
			conn.setRequestProperty("apitoken", token);
			conn.setRequestProperty("EnvironmentCode", "P");
			conn.setRequestProperty("ResponseEncoding", "J");

			String input = eligibilityRequest;
			
			OutputStream os = conn.getOutputStream();
			os.write(input.getBytes());
			os.flush();

			if (conn.getResponseCode() != HttpStatus.SC_ACCEPTED) {
				System.out.println("Failed : HTTP error code : " + conn.getResponseCode());
			}

			BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
			responseString = br.readLine();

			conn.disconnect();
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return responseString;
	}

}
