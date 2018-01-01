package alarmpi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;



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
		final int BUFFER_SIZE = 10000; // size of command buffer
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
					log.fine("received HTTP request from client:\n" + message);
					
					Date today = new Date();
					String httpResponse = "HTTP/1.1 100 ERROR\r\n" + 
							"Date: "+today+"\r\n" + 
							"Server: AlarmPi\r\n" + 
							"\r\n";
					String httpMethod = message.substring(0, message.indexOf(' '));
					boolean processed = false;
					
					if(httpMethod.equals("OPTIONS")) {
						httpResponse = "HTTP/1.1 200 OK\r\n" + 
								"Date: "+today+"\r\n" + 
								"Server: AlarmPi\r\n" + 
								"Allow: GET,HEAD,POST,OPTIONS,TRACE\r\n" +
								"Access-Control-Allow-Origin: http://127.0.0.1:64448\r\n";
						
						processed = true;
					}
					if(httpMethod.equals("GET")) {
						httpResponse = "HTTP/1.1 200 OK\r\n" + 
								"Date: "+today+"\r\n" + 
								"Server: AlarmPi\r\n" + 
								"Access-Control-Allow-Origin: http://127.0.0.1:64448\r\n" +
								"\r\n" +
								buildJsonObject().toString()+"\r\n";
						
						processed = true;
					}
					if(httpMethod.equals("POST")) {
						httpResponse = "HTTP/1.1 200 OK\r\n" + 
								"Date: "+today+"\r\n" + 
								"Server: AlarmPi\r\n" + 
								"Access-Control-Allow-Origin: http://127.0.0.1:64448\r\n" +
								"\r\n";
						
						processed = true;
					}
					
					if(!processed) {
						
					}
					
					log.fine("sending HTTP response to client:\n" + httpResponse);
					
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
		
		// add list of all alarms
		JsonArrayBuilder alarmArrayBuilder = Json.createBuilderFactory(null).createArrayBuilder();
		for(Alarm alarm:Configuration.getConfiguration().getAlarmList()) {
			alarmArrayBuilder.add(alarm.getJasonObject());
		}
		
		// build final object
		builder.add("alarms", alarmArrayBuilder);
		JsonObject jsonObject = builder.build();
		
		log.fine("created JSON object:\n"+jsonObject.toString());
		
		return jsonObject;
	}

	
	//
	// private data members
	//
	private static final Logger log = Logger.getLogger( JsonRequestHandler.class.getName() );
	private final Socket               clientSocket;
	private final Controller           controller;
	private       PrintWriter          clientStream;
	final ExecutorService threadPool = Executors.newCachedThreadPool();
}
