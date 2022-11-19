package alarmpi;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lexruntimev2.*;
import software.amazon.awssdk.services.lexruntimev2.model.RecognizeUtteranceRequest;
import software.amazon.awssdk.services.lexruntimev2.model.RecognizeUtteranceResponse;

import java.util.*;


/**
 * Uses Amazon AWS Lex service to capture a voice command
 * and turns it into a command
 */
public class SpeechToCommand {
	
	/**
	 * constructor
	 */
	public SpeechToCommand(Controller controller) {
		this.controller = controller;
		
        lexRuntimeClient = LexRuntimeV2Client.builder()
                .region(awsRegion)
                .build();
        
	}
	
	/**
	 * decompresses a gzip compressed byte array into an UTF-8 String
	 * @param bytes data to decompress
	 * @return      decompressed data as string
	 * @throws Exception
	 */
	private String decompress(byte[] bytes) throws Exception {
	    
	    GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(bytes));
	    BufferedReader bf = new BufferedReader(new InputStreamReader(gis, "UTF-8"));
	    String outStr = "";
	    String line;
	    while ((line=bf.readLine())!=null) {
	        outStr += line;
	    }
	    return outStr;
	}
	
	/**
	 * records 3s of audio and performs speech connection thru Amazon Lex
	 * @return intent as Json object as received from LEX
	 */
	synchronized JsonObject captureCommand() {
		final Path pathRecordingFile = Path.of("/tmp","alarmpilex.wav");
		final Path pathResponseFile  = Path.of("/tmp","response");
		
		JsonObject jsonIntent = null;
		
		String device = Configuration.getConfiguration().getSpeechControlDevice();
		if(device==null) {
			log.severe("no recording device specified");
			return null;
		}
		
		log.fine("starting recording of AWS speech control to "+pathRecordingFile.toString());
		try {
	        ProcessBuilder pb = new ProcessBuilder("/usr/bin/arecord", "-f","S16_LE","-r","16000","-d","3","-vv","-D",device,pathRecordingFile.toString());
	        // PC: /usr/bin/arecord -f S16_LE -r 16000 -d 3 -vv -D plughw:CARD=Device,DEV=0 /tmp/alarmpilex.wav
	        
	        
	        final Process p=pb.start();
            p.waitFor();
            log.fine("arecord exit code: "+p.exitValue());
            
            if(p.exitValue()!=0) {
            	log.severe("arecord returns exit code "+p.exitValue());
            	feedbackError();
            	
            	return null;
            }
            
	        // remove old response file (if any)
	        Path response = Path.of("/tmp","response");
	        response.toFile().delete();
	        
	        // send audio to Amazon Lex
            RecognizeUtteranceRequest recognizeUtteranceRequest = RecognizeUtteranceRequest.builder()
            		.botAliasId("NCWO5BMP1G")
                    .botId("DQMDVGSSDL")
                    .localeId("de_DE")
                    .sessionId(UUID.randomUUID().toString())
                    .requestContentType("audio/x-l16")
                    .build();
            RecognizeUtteranceResponse recognizeUtteranceResponse = lexRuntimeClient.recognizeUtterance(recognizeUtteranceRequest, pathRecordingFile, pathResponseFile);
            
            byte[] decodedBytes = Base64.getDecoder().decode(recognizeUtteranceResponse.inputTranscript());
            String decompressedObjectAsString = decompress(decodedBytes);
            log.fine("transcript="+decompressedObjectAsString);
            
            decodedBytes = Base64.getDecoder().decode(recognizeUtteranceResponse.interpretations());
            decompressedObjectAsString = decompress(decodedBytes);
            log.fine("interpretations: "+decompressedObjectAsString);
            
            // process JSON return data
            StringReader stringReader = new StringReader(decompressedObjectAsString);
            JsonArray interpretations = Json.createReaderFactory(null).createReader(stringReader).readArray();

            double highestScore = 0.0;
            
            for (JsonValue jsonValue : interpretations) {
				JsonObject interpretation = jsonValue.asJsonObject();
				JsonObject jsonNluConfidence= interpretation.getJsonObject("nluConfidence");
				if(jsonNluConfidence!=null) {
					Double score = jsonNluConfidence.getJsonNumber("score").doubleValue();
					JsonObject jsonIntentLocal = interpretation.getJsonObject("intent");
					String intentName = jsonIntentLocal.getString("name", null);
					
					log.fine("found intent: score="+score+" name="+intentName);
					if(score>highestScore) {
						highestScore = score;
						jsonIntent   = interpretation.getJsonObject("intent");
					}
				}
			}
	      } catch (Exception ex) {
	    	  log.severe("Exception during recording of AWS speech control command: "+ex.getMessage());
	    	  feedbackError();
	    }
		
		return jsonIntent;
	}
	
	/**
	 * process a speech command
	 * @param jsonIntent intent in AWS Lex JSON format
	 */
	synchronized void processCommand(JsonObject jsonIntent) {
		
		String intentName = jsonIntent.getString("name", null);

		// check for intent: set alarm time for tomorrow
		if(intentName.equals("SetAlarm")) {
			log.fine("processing intent SetAlarm");
			String interpretedValue = getInterpretedValue(jsonIntent, "alarmTime");
			if(interpretedValue!=null) {
				log.fine("set alarm: interpreted alarm time="+interpretedValue);
				
				try {
					LocalTime time = LocalTime.parse(interpretedValue+":00");
					Alarm.setAlarmTomorrow(time);
					
					String text = "Der nächste Alarm ist morgen um "+time.getHour()+" Uhr ";
					if(time.getMinute()!=0) {
						text += time.getMinute();
					}
					String filename = new TextToSpeech().createTempFile(text, "nextAlarmTomorrow.mp3");
					SoundControl.getSoundControl().on();
					SoundControl.getSoundControl().playFile(filename, FEEDBACK_VOLUME, false);
				}
				catch(DateTimeParseException e) {
					log.warning("Unable to parse alarm time: "+interpretedValue);
					feedbackError();
					
					return;
				}
				
				return;
			}
		}
		
		// check for intent: turn on something
		if(intentName.equals("TurnOn")) {
			log.fine("processing intent TurnOn");
			String interpretedValue = getInterpretedValue(jsonIntent, "item");
			if(interpretedValue!=null) {
				log.fine("turn on: interpreted item="+interpretedValue);
				
				if(interpretedValue.compareToIgnoreCase("radio")==0) {
					if(Configuration.getConfiguration().getSpeechControlSound()>0) {
			    		log.fine("turning radio on");
			    		SoundControl soundControl = SoundControl.getSoundControl();
						soundControl.on();
						Alarm.Sound sound = Configuration.getConfiguration().getSoundList().get(Configuration.getConfiguration().getSpeechControlSound()-1);
						soundControl.playSound(sound, 50, false);
						
						return;
					}
				}
				if(interpretedValue.compareToIgnoreCase("licht")==0) {
					log.fine("setting lights on");
					controller.lightsOn();
					
					return;
				}
			}
		}
		
		// check for intent: turn off something
		if(intentName.equals("TurnOff")) {
			log.fine("processing intent TurnOff");
			String interpretedValue = getInterpretedValue(jsonIntent, "item");
			if(interpretedValue!=null) {
				log.fine("turn off: interpreted item="+interpretedValue);
				
				if(interpretedValue.compareToIgnoreCase("radio")==0) {
					log.fine("turning radio off");
					SoundControl.getSoundControl().stop();
					
					return;
				}
				
				if(interpretedValue.compareToIgnoreCase("licht")==0) {
					log.fine("setting lights off");
					controller.lightsOff();
					
					return;
				}
				
				if(interpretedValue.compareToIgnoreCase("wecker")==0) {
					log.fine("setting alarms for tomorrow off");
					Alarm.skipAllAlarmsTomorrow();
					
					String filename = new TextToSpeech().createPermanentFile("Alle Alarme für morgen sind ausgeschaltet");
					SoundControl.getSoundControl().on();
					SoundControl.getSoundControl().playFile(filename, FEEDBACK_VOLUME, false);
					
					return;
				}
			}
		}
		
		// if we are here, something went wrong
		log.warning("unknown intent: "+intentName);
		feedbackError();
	}
	
	/**
	 * extarcts the interpreted value for a given slot from an AWS Intent
	 * @param jsonIntent  AWS Lex intent in JSON format
	 * @param slot        name of slot for which the value should be retrieved
	 * @return            value for the slot
	 */
	private String getInterpretedValue(JsonObject jsonIntent,String slot) {
		try {
			JsonObject jsonSlots = jsonIntent.getJsonObject("slots");
			if(jsonSlots!=null) {
				JsonObject jsonSlot = jsonSlots.getJsonObject(slot);
				if(jsonSlot!=null) {
					JsonObject jsonValue=jsonSlot.getJsonObject("value");
					if(jsonValue!=null) {
						String interpretedValue = jsonValue.getString("interpretedValue");
						if(interpretedValue!=null) {
							log.fine("interpreted value for slot "+slot+": "+interpretedValue);
							return interpretedValue;
						}
					}
				}
			}
		}
		catch(NullPointerException e) {
			log.warning("getInterprestedValue for slot "+slot+" failed");
		}
		
		return null;
	}
	
	
	private void feedbackError() {
		String filename = new TextToSpeech().createPermanentFile("Bei der Sprachsteuerung ist leider ein Fehler aufgetreten");
		SoundControl.getSoundControl().on();
		SoundControl.getSoundControl().playFile(filename, FEEDBACK_VOLUME, false);		
	}
	
	// private members
	private static final int        FEEDBACK_VOLUME = 50;  // sound volume of feedback to the user
	private static final Logger     log             = Logger.getLogger( MethodHandles.lookup().lookupClass().getName() );
	private              Controller controller;
	
	private Region     		   awsRegion = Region.EU_CENTRAL_1;
	private LexRuntimeV2Client lexRuntimeClient;
}




