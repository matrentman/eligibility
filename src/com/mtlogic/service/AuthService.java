package com.mtlogic.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthService {
	final Logger logger = LoggerFactory.getLogger(AuthService.class);

	public AuthService() {
		// TODO Auto-generated constructor stub
	}
	
	public String retrieveToken(String userName, String passWord) {
		logger.info(">>>ENTERED retrieveToken()");

		String token = null;
		try {
			URL url = new URL("http://localhost:8080/authentication-manager-0.0.1-SNAPSHOT/rest/api/token");
			if (EligibilityService.LOCAL_DEV) {
				url = new URL("http://localhost:8080/authentication-manager/rest/api/token");
			}
			
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/json");

			String input = "{ \"username\":\"" + userName + "\", \"password\":\"" + passWord + "\" }";

			OutputStream os = conn.getOutputStream();
			os.write(input.getBytes());
			os.flush();

			if (!String.valueOf(conn.getResponseCode()).startsWith("2")) {
				throw new RuntimeException("Failed : HTTP error code : " + conn.getResponseCode());
			}

			BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));

			String output = br.readLine();

			JSONObject jsonObject = new JSONObject(output); 
			
			token = jsonObject.getString("token");
			
			conn.disconnect();
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}

		logger.info("<<<EXITED retrieveToken()");
		return token;
	}
	
}
