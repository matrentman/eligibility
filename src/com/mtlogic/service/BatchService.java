package com.mtlogic.service;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BatchService {
	final Logger logger = LoggerFactory.getLogger(BatchService.class);

	private static String userName = "mtrentman";
	private static String password = "password";
	
	protected AuthService authService;
	protected String token;
	
	public BatchService() {
		authService = new AuthService();
		token = authService.retrieveToken(userName, password);
	}
	
	static public String getJsonString(JSONObject jsonObject, String fieldName) {
		String jsonString = null;
		try {
			 jsonString = jsonObject.getString(fieldName);
		} catch (JSONException e) {
			System.out.println("Could not retrieve fieldname: " + fieldName + " from segment");
		}
		return jsonString;
	}
	
}
