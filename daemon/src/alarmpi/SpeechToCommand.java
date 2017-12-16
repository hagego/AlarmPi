package alarmpi;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.logging.Logger;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.lexruntime.AmazonLexRuntime;
import com.amazonaws.services.lexruntime.AmazonLexRuntimeClient;
import com.amazonaws.services.lexruntime.AmazonLexRuntimeClientBuilder;
import com.amazonaws.services.lexruntime.model.PostContentRequest;
import com.amazonaws.services.lexruntime.model.PostContentResult;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * Uses Amazon AWS Lex service to capture a voice command
 * and turns it into a command
 */
public class SpeechToCommand {
	
	void captureCommand() {
		final String tmpAwsLexFilename = "/tmp/alarmpiawslex.wav";
		
		log.fine("starting recording of AWS speech control to "+tmpAwsLexFilename);
		try {
	        ProcessBuilder pb = new ProcessBuilder("/usr/bin/arecord", "-f","S16_LE","-r","16000","-d","5","-vv","-D","plughw:1",tmpAwsLexFilename);
	        final Process p=pb.start();
            p.waitFor();
            log.fine("arecord exit code: "+p.exitValue());
             
     		FileInputStream inputStream;
    		try {
    			log.finest("processing speech control recording");
    			
    			AmazonLexRuntimeClientBuilder builder = AmazonLexRuntimeClient.builder();
    			builder.setRegion("us-east-1");
    			AmazonLexRuntime runtime = builder.build();
    			PostContentRequest request = new PostContentRequest();
    			
    			inputStream = new FileInputStream(tmpAwsLexFilename);
    			
	    		AWSCredentialsProvider provider = new DefaultAWSCredentialsProviderChain();
	    		request.setRequestCredentialsProvider(provider);
	    		request.setBotName("AlarmPi");
	    		request.setBotAlias("AlarmPiProd");
	    		request.setUserId("AlarmPi");
	    		request.setContentType("audio/l16; rate=16000; channels=1");
	    		request.setInputStream(inputStream);
	    		
	    		log.finest("sending AWS request");
	    		PostContentResult result = runtime.postContent(request);
	    		
	    		log.finest("transcript: "+result.getInputTranscript());
	    		log.finest("dialog state: "+result.getDialogState());
	    		log.finest("intent: "+result.getIntentName());
	    		log.finest("message: "+result.getMessage());
	    		log.finest("content: "+result.getContentType());
	    		log.finest("attributes: "+result.getSessionAttributes());
	    		log.finest("slots: "+result.getSlots());
	    		
	    		if(result.getIntentName().equalsIgnoreCase("SetNextAlarm")) {
	    			processSetAlarm(result.getSlots());
	    		}
	    		else if(result.getIntentName().equalsIgnoreCase("DimLight")) {
	    			dimLight(result.getSlots());
	    		}
	    		else if(result.getIntentName().equalsIgnoreCase("TurnLightOn")) {
	    			turnLightOn(result.getSlots());
	    		}
	    		else {
	    			log.warning("Ein Sprachbefehl wurde nicht verstanden: "+result.getInputTranscript());
	    			String filename = new TextToSpeech().createPermanentFile("Ich habe dich leider nicht verstanden.");
	    			SoundControl.getSoundControl().playFile(filename, FEEDBACK_VOLUME, false);
	    		}
    		
    		} catch (FileNotFoundException e) {
    			log.severe("Unable to find AWS recording file: "+e.getMessage());
    			feedbackError();
    		}
	      } catch (Exception ex) {
	    	  log.severe("Exception during recording of AWS speech control command: "+ex.getMessage());
	    	  feedbackError();
	    }
	}
	
	private void processSetAlarm(String slots) {
		log.fine("deteced intent: SetNextAlarm. Slots:"+slots);
		
		try {
			JsonFactory factory = new JsonFactory();
			JsonParser parser;
			parser = factory.createParser(slots);
			
			JsonToken token = parser.nextToken();
			log.finest(token.toString()); // START_OBJECT
			log.finest("name="+parser.getCurrentName());
			log.finest("value1="+parser.getValueAsString());
			
			token = parser.nextToken();
			log.finest(token.toString()); // FIELD_NAME
			log.finest("name="+parser.getCurrentName());
			log.finest("value1="+parser.getValueAsString());
			
			token = parser.nextToken();
			log.finest(token.toString()); // should be VALUE_STRING
			log.finest("name="+parser.getCurrentName());
			log.finest("value1="+parser.getValueAsString());
			
			if(token==JsonToken.VALUE_STRING && parser.getCurrentName().compareToIgnoreCase("alarmTime")==0) {
				String alarmTime = parser.getValueAsString();
				log.fine("alarm time: "+alarmTime);
				return;
			}
			log.severe("Error during processing of AWS speech control command: ");
			feedbackError();
		} catch (IOException e) {
			log.severe("Exception during processing of AWS speech control command: "+e.getMessage());
			feedbackError();
		}
	}
	
	private void dimLight(String slots) {
		log.fine("deteced intent: dimLight. Slots:"+slots);
		
		try {
			JsonFactory factory = new JsonFactory();
			JsonParser parser;
			parser = factory.createParser(slots);
			
			JsonToken token = parser.nextToken();
			log.finest(token.toString()); // START_OBJECT
			log.finest("name="+parser.getCurrentName());
			log.finest("value1="+parser.getValueAsString());
			
			token = parser.nextToken();
			log.finest(token.toString()); // FIELD_NAME
			log.finest("name="+parser.getCurrentName());
			log.finest("value1="+parser.getValueAsString());
			
			token = parser.nextToken();
			log.finest(token.toString()); // should be VALUE_STRING
			log.finest("name="+parser.getCurrentName());
			log.finest("value1="+parser.getValueAsString());
			
			if(token==JsonToken.VALUE_STRING && parser.getCurrentName().compareToIgnoreCase("brightness")==0) {
				String brightness = parser.getValueAsString();
				log.fine("brightness: "+brightness);
				return;
			}
			log.severe("Error during processing of AWS speech control command: ");
			feedbackError();
		} catch (IOException e) {
			log.severe("Exception during processing of AWS speech control command: "+e.getMessage());
			feedbackError();
		}
	}
	
	private void turnLightOn(String slots) {
		log.fine("deteced intent: turnLightOn. Slots:"+slots);
	}
	
	private void feedbackError() {
		String filename = new TextToSpeech().createPermanentFile("Bei der Sprachsteuerung ist leider ein Fehler aufgetreten");
		SoundControl.getSoundControl().playFile(filename, FEEDBACK_VOLUME, false);		
	}
	
	// private members
	private static final int      FEEDBACK_VOLUME = 50;  // sound volume of feedback to the user
	private static final Logger   log             = Logger.getLogger( SpeechToCommand.class.getName() );
}
