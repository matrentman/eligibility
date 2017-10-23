package com.mtlogic.service;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.json.JSONException;

@Path("/test")
public class TestService {
	@Path("/echo")
	@POST
	@Consumes("text/plain")
	@Produces("text/plain")
	public Response test277ResponseService(String message) throws JSONException
	{	
		System.out.println(message);
		return Response.status(200).entity(message.replace("276", "277")).build();
	}
}
