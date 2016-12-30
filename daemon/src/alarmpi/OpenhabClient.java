package alarmpi;


import java.io.*;
import java.net.*;
import java.util.logging.Logger;

/**
 * client to send commands to an openhab server
 */
public class OpenhabClient {

	/**
	 * Sends a command to the openhab server
	 * @param command command to send
	 * @param error   contains the error description in case of an error
	 * @return        success
	 */
	boolean sendCommand(final String command,String error) {
		Configuration configuration = Configuration.getConfiguration();
		error = new String();
		
		if(!configuration.getOpenhabAddress().isEmpty() && !configuration.getOpenhabPort().isEmpty()) {
			if(configuration.getOpenhabCommands().contains(command)) {
				try {
					log.info("sending command to openhab: "+command);
					Socket  clientSocket = new Socket(configuration.getOpenhabAddress(), Integer.parseInt(configuration.getOpenhabPort()));
					
					DataOutputStream outToServer = new DataOutputStream(clientSocket.getOutputStream());
					outToServer.writeBytes(command + '\n');
					clientSocket.close();
				} catch (IOException e) {
					log.severe(e.getMessage());
					error = e.getMessage();
					return false;
				}
				
				return true;
			}
			else {
				// command not defined in config file
				log.warning("openhab command "+command+" not defined in config file");
				return false;
			}
		}
		else {
			log.config("no openhab server specified");
			return false;
		}
	}
	
	/**
	 * Sends a query to the openhab server
	 * @param command command (query) to send
	 * @return        answer to the query
	 * @throws IOException 
	 */
	String sendQuery(final String command) throws NumberFormatException, UnknownHostException, IOException {
		Configuration configuration = Configuration.getConfiguration();
		
		if(!configuration.getOpenhabAddress().isEmpty() && !configuration.getOpenhabPort().isEmpty()) {
			if(configuration.getOpenhabCommands().contains(command)) {
				log.info("sending query to openhab: "+command);
				Socket  clientSocket = new Socket(configuration.getOpenhabAddress(), Integer.parseInt(configuration.getOpenhabPort()));
				
				DataOutputStream outToServer = new DataOutputStream(clientSocket.getOutputStream());
				BufferedReader  inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
				  
				outToServer.writeBytes(command + '\n');
				
				char buffer[] = new char[100];
				String answer = null;
				int length = inFromServer.read(buffer,0,100); 
				
				if(length>-1) {
					answer = String.valueOf(buffer, 0, length);
					log.info("received answer: "+answer);
				}
				else {
					log.warning("sendQuery received no answer");
				}
				clientSocket.close();
				
				return answer;
			}
			else {
				log.warning("openhab query "+command+" not defined in config file");
				return null;
			}
		}
		else {
			log.config("no openhab server specified in config file");
			return null;
		}
	}
	
	/**
	 * Queries the current temperature from openhab
	 * @return current temperature or null in case of problem
	 */
	Double getTemperature() {
		Double temperature = null;
		
		try {
			String response = sendQuery("temperature?");
			log.fine("openhabe query temperature? returns: "+response);
			if(response!=null) {
				String[] fields = response.split(" ");
				if(fields.length>1) {
					temperature = Double.parseDouble(fields[0]);
				}
				else {
					temperature = Double.parseDouble(response);
				}
			}
			else {
				log.warning("openhab query temperature? returns null");
			}
		} catch (IOException e) {
			log.severe("error during openhab temperature? query: "+e.getMessage());
		} catch (NumberFormatException e) {
			log.severe("invalid openhab response. Can't parse temperature");
		}
		
		
		return temperature;
	}
	
	//
	// private members
	//
	private static final Logger log = Logger.getLogger( OpenhabClient.class.getName() );
}
