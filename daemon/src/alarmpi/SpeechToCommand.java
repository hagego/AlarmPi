package alarmpi;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.lang.invoke.MethodHandles;
import java.util.logging.Logger;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.lexruntime.AmazonLexRuntime;
import com.amazonaws.services.lexruntime.AmazonLexRuntimeClient;
import com.amazonaws.services.lexruntime.AmazonLexRuntimeClientBuilder;
import com.amazonaws.services.lexruntime.model.PostContentRequest;
import com.amazonaws.services.lexruntime.model.PostContentResult;

import alarmpi.Configuration.Sound;

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
		
		builder = AmazonLexRuntimeClient.builder();
		builder.setRegion("us-east-1");
		runtime = builder.build();
		request = new PostContentRequest();
		
		provider = new DefaultAWSCredentialsProviderChain();
	}
	
	
	synchronized void captureCommand() {
		final String tmpAwsLexFilename = "/tmp/alarmpilex.wav";
		
		String device = Configuration.getConfiguration().getSpeechControlDevice();
		if(device==null) {
			log.severe("no recording device specified");;
			return;
		}
		
		log.fine("starting recording of AWS speech control to "+tmpAwsLexFilename);
		try {
	        ProcessBuilder pb = new ProcessBuilder("/usr/bin/arecord", "-f","S16_LE","-r","16000","-d","3","-vv","-D",device,tmpAwsLexFilename);
	        
	        // PC: /usr/bin/arecord -f S16_LE -r 16000 -d 3 -vv -D plughw:CARD=Device,DEV=0 /tmp/alarmpilex.wav
	        
	        final Process p=pb.start();
            p.waitFor();
            log.fine("arecord exit code: "+p.exitValue());
            
            if(p.exitValue()!=0) {
            	log.severe("arecord returns exit code "+p.exitValue());
            }
             
     		FileInputStream inputStream;
    		try {
    			log.finest("processing speech control recording");
    			
    			log.fine("reading file "+tmpAwsLexFilename);
    			inputStream = new FileInputStream(tmpAwsLexFilename);
    			
	    		request.setRequestCredentialsProvider(provider);
	    		request.setBotName("AlarmPiGerman");
	    		request.setBotAlias("AlarmPiGermanProd");
	    		request.setUserId("AlarmPi");
	    		request.setContentType("audio/l16; rate=16000; channels=1");
	    		request.setInputStream(inputStream);
	    		
	    		log.finest("sending AWS request");
	    		PostContentResult result = runtime.postContent(request);
	    		
	    		log.fine("result of AWS LEX request: intent="+result.getIntentName()+" slots="+result.getSlots());
	    		
	    		log.finest("transcript: "+result.getInputTranscript());
	    		log.finest("dialog state: "+result.getDialogState());
	    		log.finest("intent: "+result.getIntentName());
	    		log.finest("message: "+result.getMessage());
	    		log.finest("content: "+result.getContentType());
	    		log.finest("attributes: "+result.getSessionAttributes());
	    		log.finest("slots: "+result.getSlots());
	    		
	    		boolean found = false;
	    		if(result.getIntentName().equalsIgnoreCase("TurnOn")) {
	    			turnOn(result.getSlots());
	    			found = true;
	    		}
	    		if(found==false) {
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
	
	
	private void turnOn(String slots) {
		log.fine("deteced intent: turnOn, slots="+slots);
		
		if(slots.contains("Licht")) {
			log.fine("turning on lights");
			controller.getLightControlList().stream().filter(light -> light.getClass()!=LightControlPCA9685.class).forEach(light -> light.setBrightness(30));
		}
		if(slots.contains("Lichterkette")) {
			log.fine("turning on light chains");
			controller.getLightControlList().stream().filter(light -> light.getClass()!=LightControlPCA9685.class).forEach(light -> light.setBrightness(30));
		}
		if(slots.contains("Radio")) {
			log.fine("turning on radio");
			SoundControl soundControl = SoundControl.getSoundControl();
			soundControl.on();
			Sound sound = Configuration.getConfiguration().getSoundFromId(1);
			soundControl.playSound(sound, 50, false);
		}
		
	}
	
	private void feedbackError() {
		String filename = new TextToSpeech().createPermanentFile("Bei der Sprachsteuerung ist leider ein Fehler aufgetreten");
		SoundControl.getSoundControl().playFile(filename, FEEDBACK_VOLUME, false);		
	}
	
	// private members
	private static final int        FEEDBACK_VOLUME = 50;  // sound volume of feedback to the user
	private static final Logger     log             = Logger.getLogger( MethodHandles.lookup().lookupClass().getName() );
	private              Controller controller;
	
	private AmazonLexRuntimeClientBuilder builder;
	private AmazonLexRuntime              runtime;
	private PostContentRequest            request;
	private AWSCredentialsProvider        provider;
}
