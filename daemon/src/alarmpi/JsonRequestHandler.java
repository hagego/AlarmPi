package alarmpi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.lang.invoke.MethodHandles;
import java.net.Socket;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;



/**
 * handler class for TCP client connections
 */
public class JsonRequestHandler implements Runnable {

	public JsonRequestHandler(Controller controller,Socket socket) {
		this.controller    = controller;
		this.clientSocket  = socket;
		
	}

	@Override
	public void run() {
		final int BUFFER_SIZE = 10000; // size of read buffer
		log.info("client connected from "+clientSocket.getRemoteSocketAddress());
		

		try {
			boolean exit = false;
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
			char[] buffer = new char[BUFFER_SIZE];
			clientStream  = new PrintWriter(clientSocket.getOutputStream(), true);
			
			// read and process client commands until 'exit' command is received
			while (!exit) {
				int length = bufferedReader.read(buffer, 0, BUFFER_SIZE);
				if (length <= 0) {
					log.info("client connection terminated");
					exit = true;
				} else if (length == BUFFER_SIZE) {
					log.severe("message from client exceeds max. length of "
							+ BUFFER_SIZE + ". Ignoring...");
				} else {
					String message = new String(buffer, 0, length).trim();
					log.fine("received HTTP request from client");
					log.finest("message details: " + message);
					
					Date today = new Date();
					String httpResponse = "HTTP/1.1 100 ERROR\r\n" + 
							"Date: "+today+"\r\n" + 
							"Server: AlarmPi\r\n" + 
							"\r\n";
					
					int pos = message.indexOf(' ');
					if(pos>0) {
						String httpMethod = message.substring(0, message.indexOf(' '));
						String jsonString = java.net.URLDecoder.decode(message.substring(pos+2),"UTF-8");
						
						boolean processed = false;
						
						String origin = clientSocket.getLocalAddress().toString();//+":50261";//+clientSocket.getLocalPort();
						log.fine("origin="+origin);
						
						origin="127.0.0.1";
						String cors="*";
						// http:/"+origin
						
						if(httpMethod.equals("OPTIONS")) {
							httpResponse = "HTTP/1.1 200 OK\r\n" + 
									"Date: "+today+"\r\n" + 
									"Server: AlarmPi\r\n" + 
									"Allow: GET,HEAD,POST,OPTIONS,TRACE\r\n" +
									"Access-Control-Allow-Origin: "+cors+"\r\n";
							
							processed = true;
						}
						if(httpMethod.equals("GET")) {
							httpResponse = "HTTP/1.1 200 OK\r\n" + 
									"Date: "+today+"\r\n" + 
									"Server: AlarmPi\r\n" + 
									"Access-Control-Allow-Origin: "+cors+"\r\n" +
									"\r\n" +
									buildJsonObject().toString()+"\r\n";
							
							processed = true;
						}
						if(httpMethod.equals("POST")) {
							JsonObject jsonObject = buildJasonObjectFromString(jsonString);
							
							JsonArray jsonArray = jsonObject.getJsonArray("alarms");
							if(jsonArray!=null) {
								Alarm.parseAllFromJsonObject(jsonObject);
							}
							
							jsonArray = jsonObject.getJsonArray("lights");
							if(jsonArray!=null) {
								controller.parseLightStatusFromJsonObject(jsonObject);
							}
							
							jsonArray = jsonObject.getJsonArray("actions");
							if(jsonArray!=null) {
								jsonArray.stream().filter(action -> action.toString().equals("\"stopActiveAlarm\"")).forEach(action -> controller.stopActiveAlarm());
							}
							
							httpResponse = "HTTP/1.1 200 OK\r\n" + 
									"Date: "+today+"\r\n" + 
									"Server: AlarmPi\r\n" + 
									"Access-Control-Allow-Origin: "+cors+"\r\n" +
									"\r\n";
							
							processed = true;
						}
						
						if(!processed) {
							log.severe("Unable to process HTTP request. Method="+httpMethod);
						}
					}
					else {
						log.severe("Unable to process HTTP request. Message=="+message);
					}
					log.fine("sending HTTP response to client: "+clientSocket.getRemoteSocketAddress());
					log.finest("message details: "+httpResponse);
					
					clientStream.print(httpResponse);
					clientStream.flush();
					clientStream.close();
				}
			}
		} catch (IOException e) {
			log.info(e.getMessage());
		}
	}
	
	private JsonObject buildJsonObject() {
		JsonObjectBuilder builder = Json.createBuilderFactory(null).createObjectBuilder();
		
		// build final object
		builder.add("name", Configuration.getConfiguration().getName());
		builder.add("alarms", Configuration.getConfiguration().getAlarmsAsJsonArray());
		builder.add("sounds", Configuration.getConfiguration().getSoundsAsJsonArray());
		builder.add("soundStatus", controller.getSoundStatus());
		builder.add("lights", controller.getLightStatusAsJsonArray());
		JsonObject jsonObject = builder.build();
		
		String logString = jsonObject.toString();
		if(logString.length()>50) {
			logString = logString.substring(0,49);
		}
		log.fine("created JSON object: "+logString+"...");
		log.finest("full JSON Object: "+jsonObject.toString());
		
		return jsonObject;
	}
	
	private JsonObject buildJasonObjectFromString(String stringifedObject) {
		JsonReader reader = Json.createReaderFactory(null).createReader(new StringReader(stringifedObject));
		JsonObject jsonObject = reader.readObject();
		 
		String logString = jsonObject.toString();
		if(logString.length()>50) {
			logString = logString.substring(0,49);
		}
		
		log.fine("buildJasonObjectFromString: created JSON object: "+logString+"...");
		log.finest("full JSON object: "+jsonObject.toString());
		
		return jsonObject;
	}

	
	//
	// private data members
	//
	private static final Logger   log     = Logger.getLogger( MethodHandles.lookup().lookupClass().getName() );
	
	private final Socket               clientSocket;
	private final Controller           controller;
	private       PrintWriter          clientStream;
	final ExecutorService threadPool = Executors.newCachedThreadPool();
}
