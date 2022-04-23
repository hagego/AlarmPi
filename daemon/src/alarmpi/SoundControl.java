package alarmpi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.pi4j.context.Context;
import com.pi4j.exception.Pi4JException;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;

import alarmpi.Alarm.Sound.Type;


/**
 * Controls the sound output of AlarmPi using mpd
 * implemented as wrapper around the mpd TCP interface
 * Each command opens and closes the telnet connection again
 * Implemented as singleton
 */
public class SoundControl {

	/**
	 * Must be called once before doing anything else with this class
	 * Sets the pi4j context
	 * @param pi4j PI4J context
	 */
	static void setPi4jContext(Context pi4j) {
		if(object==null) {
			object = new SoundControl(pi4j);
		}
		else {
			log.warning("setPi4jContext called multiple times");
		}
	}
	
	static SoundControl getSoundControl() {
		if(object==null) {
			log.severe("getSoundControl() called before setPi4jContext()");
		}
		
		return object;
	}
	
	/**
	 * Default constructor, requires a configuration object
	 * @param configuration configuration object
	 */
	private SoundControl(Context pi4j) {
		// initialize pi4j objects for GPIO handling
		// Sound power on uses BRCM 23 (WiringPI 04)
		if(Configuration.getConfiguration().getRunningOnRaspberry()) {
			log.info("running on Raspberry - initializing pi4j");
			
	        try {
	            var config = DigitalOutput.newConfigBuilder(pi4j)
	            	      .id("soundcontrol")
	            	      .name("soundcontrol")
	            	      .address(GPIO_SOUND_POWER)
	            	      .shutdown(DigitalState.LOW)
	            	      .initial(DigitalState.LOW)
	            	      .provider("pigpio-digital-output");
	            	      
	            gpioSoundPower = pi4j.create(config);
	        }
	        catch (Pi4JException e) {
	        	log.severe("Exception during initializaion of pi4j digital output");
	        	log.severe(e.getMessage());
	        }
	        
			log.info("running on Raspberry - initializing pi4j done.");
		}
		else {
			gpioSoundPower = null;
		}
				
		stop();
		off();
	}

	/**
	 * turns 5V audio power on
	 */
	synchronized void on() {
		log.fine("turning 5V audio supply ON");

		if(Configuration.getConfiguration().getRunningOnRaspberry() && gpioSoundPower!=null) {
			gpioSoundPower.high();
		}
		log.fine("GPIO for audio set to high");
	}
	
	/**
	 * turns 5V audio power off
	 */
	synchronized void off() {
		log.fine("turning 5V audio supply OFF");
		stop();
		if(Configuration.getConfiguration().getRunningOnRaspberry() && gpioSoundPower!=null) {
			gpioSoundPower.low();
		}
		log.fine("GPIO for audio set to low");
		
		activeVolume = 0;
		activeSound  = null;
	}
	
	/**
	 * stops the current audio output
	 */
	synchronized void stop() {
		log.fine("audio STOP");
		try {
			connect();
			try {
				sendCommand("stop");
				sendCommand("clear");
				
				activeSound  = null;
			} catch (IOException e) {
				disconnect();
				throw e;
			}
			disconnect();
		} catch (IOException e) {
			log.severe("Exception in stop: "+e.getMessage());
		}
	}
	
	/**
	 * updates the mpd database. Must be called after a new file or playlist
	 * was added
	 */
	synchronized void update() {
		try {
			connect();
			try {
				sendCommand("update");
			} catch (IOException e) {
				disconnect();
				throw e;
			}
			disconnect();
		} catch (IOException e) {
			log.severe("Exception during update: "+e.getMessage());
		}
	}
	
	/**
	 * gets the duration of a song
	 * @param filename fully qualified MPD filename (incl. subdirectory and extension)
	 * @return         duration in seconds or null
	 */
	synchronized Integer getSongDuration(String filename) {
		try {
			connect();
			try {
				String answer = sendCommand("lsinfo \""+filename+"\"");
				log.fine("lsinfo answer="+answer);
				
				Pattern p = Pattern.compile("Time:\\s*(\\d+)");
				Matcher m = p.matcher(answer);
				
				if(m.groupCount()==1 && m.find()) {
					Integer duration = Integer.parseInt(m.group(1));
					log.fine("duration ="+duration);
					
					disconnect();
					return duration;
				}
				
			} catch (IOException e) {
				disconnect();
				throw e;
			}
			disconnect();
		} catch (IOException e) {
			log.severe("Exception during getSongDuration: "+e.getMessage());
			return null;
		}
		
		return null;
	}
	
	/**
	 * plays a sound defined in the AlarmPi configuration file
	 * @param soundId  sound to play (index into sound list)
	 * @param volume   optional volume. If null, volume remains unchanged
	 * @param append   if true, the currently playing sounds gets not interrupted
	 *                 and the new sound will start after it finished
	 */
	synchronized void playSound(Alarm.Sound sound,Integer volume,boolean append) {
		log.fine("playSound: name="+sound.name+" type="+sound.type+" volume="+volume+" append="+append);
		switch(sound.type) {
		case STREAM:
			playRadioStream(sound.source,volume,append);
			break;
		case FILE:
			playFile(sound.source, volume, append);
			break;
		case EXTERNAL:
			if(volume==null) {
				volume = activeVolume;
			}
			stop();
			setVolume(volume);
			break;
		}
		
		activeSound = sound;
	}
	
	/**
	 * checks if mpd returns "state: play". If not, the specified sound gets started
	 * @param sound sound to play
	 */
	synchronized void checkSound(Alarm.Sound sound) {
		log.fine("checkSound: name="+sound.name+" type="+sound.type);
		
		try {
			connect();
			try {
				String answer = sendCommand("status");
				
				// check for "state: play"
				Pattern pattern = Pattern.compile("state:\\s*(\\w+)");
				Matcher matcher = pattern.matcher(answer);
				if(matcher.find() && !matcher.group(1).equals("play")) {
					log.warning("checkSound found state "+matcher.group(1)+" trying to restart sound");
					sendCommand("stop");
					sendCommand("clear");
					if(sound.type==Type.STREAM) {
						sendCommand("load "+sound.source);
						sendCommand("play");
					}
					else {
						sendCommand("add \""+sound.source+"\"");
						sendCommand("play");
					}
					
				}
			} catch (IOException e) {
				disconnect();
				throw e;
			}
			disconnect();
			
			activeSound = null;
		} catch (IOException e) {
			log.severe("Exception in checkSound");
			log.severe(e.getMessage());
		}
	}
	
	/**
	 * plays a local mp3 file
	 * @param filename filename of mp3 file
	 * @param volume   optional volume. If null, volume remains unchanged
	 * @param append   if true, the currently playing sounds gets not interrupted
	 *                 and the new sound will start after it finished
	 */
	synchronized void playFile(String filename,Integer volume,boolean append) {
		log.fine("playFile: file="+filename+" volume="+volume+" append="+append);
		
		if(filename==null || filename.isEmpty()) {
			log.severe("playFile called with null or empty filename");
			return;
		}
		
		try {
			connect();
			try {
				if(!append) {
					sendCommand("stop");
					sendCommand("clear");
				}
				sendCommand("add \""+filename+"\"");
				if(volume!=null) {
					activeVolume = volume;
					sendCommand("setvol "+Integer.toString(activeVolume));
				}
				sendCommand("play");
			} catch (IOException e) {
				disconnect();
				throw e;
			}
			disconnect();
			
			activeSound = null;
		} catch (IOException e) {
			log.severe("Exception in playFile, filename="+filename);
			log.severe(e.getMessage());
		}
	}
	
	/**
	 * sets the volume
	 * @param new volume in percent
	 */
	synchronized void setVolume(int volume) {
		try {
			connect();
			try {
				activeVolume = volume;
				sendCommand("setvol "+Integer.toString(activeVolume));
			} catch (IOException e) {
				disconnect();
				throw e;
			}
			disconnect();
		} catch (IOException e) {
			log.severe("Exception in setVolume: "+e.getMessage());
		}
	}
	
	/**
	 * returns the active volume
	 * @return active volume in percent or 0 if off
	 */
	synchronized int getVolume() {
		log.fine("returning active volume: "+activeVolume);
		return activeVolume;
	}
	
	/**
	 * @return the active sound or null if no sound is played
	 */
	synchronized Alarm.Sound getActiveSound() {
		return activeSound;
	}
	
	
	// private methods
	
	/**
	 * plays an internet radio stream
	 * @param uri    radio stream URI
	 * @param volume optional volume in percent, null=no change
	 * @param append if true, the currently playing sounds gets not interrupted
	 *               and the new sound will start after it finished
	 */
	synchronized private void playRadioStream(String uri,Integer volume,boolean append) {
		log.fine("playRadio: uri="+uri+" volume="+volume+" append="+append);
		
		try {
			connect();
			try {
				if(!append) {
					sendCommand("stop");
					sendCommand("clear");
				}
				sendCommand("load "+uri);
				sendCommand("play");
				if(volume!=null) {
					activeVolume = volume;
					sendCommand("setvol "+Integer.toString(activeVolume));
				}
			} catch (IOException e) {
				disconnect();
				throw e;
			}
			disconnect();
		} catch (IOException e) {
			log.severe("Exception in playRadio, URI="+uri);
			log.severe(e.getMessage());
		}
	}
	

	
	/**
	 * creates a telnet connection to mpd daemon
	 * @throws UnknownHostException
	 * @throws IOException
	 */
	synchronized private void connect() throws UnknownHostException, IOException {
		socket = new Socket(Configuration.getConfiguration().getMpdAddress(),Configuration.getConfiguration().getMpdPort());
		socket.setKeepAlive(true);
		reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
		writer = new PrintWriter(socket.getOutputStream(),true);
		
		// read one line - expect to return "OK MPD <version>"
		int c=0;
		String answer = new String();
		while ((c = reader.read()) != -1 && c!=10) {
			answer += (char)c;
		}
		log.finest("MPD connect returns "+answer);
		if(!answer.startsWith("OK")) {
			throw new IOException("mpd error during connect: "+answer);
		}
	}
	
	/**
	 * closes telnet connection again
	 * @return
	 * @throws IOException
	 */
	synchronized private void disconnect() throws IOException {
		if(socket!=null) {
			socket.close();
		}
		socket = null;
		
		log.finest("MPD disconnected");
	}
	
	/**
	 * sends a single command to mpd
	 * @param cmd command to send
	 * @throws IOException
	 */
	synchronized private String sendCommand(String cmd) throws IOException {
		writer.print(cmd+"\n");
		writer.flush();

		// expect OK or ACK to indicate end of answer
		int c = 0;
		String answer = new String();
		String line   = new String();
		while(!line.startsWith("OK") && !line.startsWith("ACK")) {
			line = new String();
			// read single line
			while ((c = reader.read()) != -1 && c!=10) {
				line += (char)c;
			}
			log.fine("sendCommand "+cmd+" returns line "+line);
			
			answer += line+"\n";
		}
		
		if(!line.startsWith("OK")) {
			throw new IOException("mpd error during cmd "+cmd+" : "+answer);
		}
		
		return answer;
	}

	// private members
	private static final Logger  log    = Logger.getLogger( SoundControl.class.getName() );
	
	private static SoundControl  object = null;   // singleton object

	private DigitalOutput        gpioSoundPower = null; // pi4j digital output pin to control sound power
	private final static int     GPIO_SOUND_POWER = 23; // GPIO number for sound power control
	
	private Socket               socket;                // TCP socket
	private BufferedReader       reader;
	private PrintWriter          writer;

	private Alarm.Sound          activeSound;           // stores the currently active sound, or null
	private int                  activeVolume;          // caches the active volume, 0=off
}

