package com.mtlogic.business;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Credentials {
	final Logger logger = LoggerFactory.getLogger(Credentials.class);
	private String url;
	private String user;
	private String password;
	
	public Credentials(String indicator) {
		retrieveCredentials(indicator);
	}
	
	private void retrieveCredentials(String indicator) {
		logger.info(">>>ENTERED retrieveCredentials()");
		Context envContext = null;
		Connection con = null;
		PreparedStatement preparedStatement = null;
		 
		String selectMessageSQL = "select value from public.lookup where topic = 'admin' and key = ?";
		
		try {
			envContext = new InitialContext();
			Context initContext  = (Context)envContext.lookup("java:/comp/env");
			DataSource ds = (DataSource)initContext.lookup("jdbc/eligibility");
			//DataSource ds = (DataSource)envContext.lookup("java:/comp/env/jdbc/claimstatus");
			con = ds.getConnection();
			
			preparedStatement = con.prepareStatement(selectMessageSQL);
			
			if ("P".equalsIgnoreCase(indicator)) {
				preparedStatement.setString(1, "emdeon-prod-url");
				
				ResultSet rs = preparedStatement.executeQuery();
				
				if (rs.next()) {
					this.url = rs.getString("value");
				}
				
				preparedStatement.setString(1, "emdeon-prod-user");
				
				rs = preparedStatement.executeQuery();
				
				if (rs.next()) {
					this.user = rs.getString("value");
				}
				
				preparedStatement.setString(1, "emdeon-prod-password");
				
				rs = preparedStatement.executeQuery();
				
				if (rs.next()) {
					this.password = rs.getString("value");
				}
			} else {
				preparedStatement.setString(1, "emdeon-test-url");
				
				ResultSet rs = preparedStatement.executeQuery();
				
				if (rs.next()) {
					this.url = rs.getString("value");
				}
				
				preparedStatement.setString(1, "emdeon-test-user");
				
				rs = preparedStatement.executeQuery();
				
				if (rs.next()) {
					this.user = rs.getString("value");
				}
				
				preparedStatement.setString(1, "emdeon-test-password");
				
				rs = preparedStatement.executeQuery();
				
				if (rs.next()) {
					this.password = rs.getString("value");
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
			logger.error("ERROR!!! : " + e.getMessage());
		} catch (NamingException e) {
			e.printStackTrace();
			logger.error("ERROR!!! : " + e.getMessage());
		} catch (Exception e) {
			e.printStackTrace();
			logger.error("ERROR!!! : " + e.getMessage());
		} finally {
		    try{preparedStatement.close();}catch(Exception e){};
		    try{con.close();}catch(Exception e){};
		}
		logger.info("<<<EXITED retrieveCredentials()");
	}
	
	public String getUrl() {
		return url;
	}
	public void setUrl(String url) {
		this.url = url;
	}
	public String getUser() {
		return user;
	}
	public void setUser(String user) {
		this.user = user;
	}
	public String getPassword() {
		return password;
	}
	public void setPassword(String password) {
		this.password = password;
	}
	
	
	
}
