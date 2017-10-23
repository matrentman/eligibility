# eligibility

This is a RESTful API which receives an ASCX12 270 document, parses it, validates it, replaces the payer code with the associated payer code for the clearing house handling the request, substitutes the correct values for the sender and receiver and then passes the request on to the payer. Once a response is received from the payer the sender and receiver are again swapped for the appropriate values before returning the ASCX12 271 document back to the requesting party.  
