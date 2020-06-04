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

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.RaspiPin;

import alarmpi.Configuration.Sound;


/**
 * Controls the sound output of AlarmPi using mpd
 * implemented as wrapper around the mpd TCP interface
 * Each command opens and closes the telnet connection again
 * Implemented as singleton
 */
public class SoundControl {

	/**
	 * returns the singleton object
	 * @return the singleton object
	 */
	static SoundControl getSoundControl() {
		if(object==null) {
			object = new SoundControl();
		}
		
		return object;
	}
	
	/**
	 * Default constructor, requires a configuration object
	 * @param configuration configuration object
	 */
	private SoundControl() {
		// create Pin object for GPIO to turn on/off audio power
		// WiringPi Pin GPIO4 = BRCM GPIO 23
		if(Configuration.getConfiguration().getRunningOnRaspberry()) {
			log.info("initializing GPIO to control 5V supply");
			GpioController gpioController = GpioFactory.getInstance();
			log.fine("GpioController instance retrieved");
			gpioPinAudioControl = gpioController.provisionDigitalOutputPin(RaspiPin.GPIO_04);
			log.fine("GPIO_04 privisioned as output");
		}
		
		stop();
		off();
	}

	/**
	 * turns 5V audio power on
	 */
	synchronized void on() {
		log.fine("turning 5V audio supply ON");

		if(Configuration.getConfiguration().getRunningOnRaspberry()) {
			gpioPinAudioControl.high();
		}
		log.fine("GPIO for audio set to high");
	}
	
	/**
	 * turns 5V audio power off
	 */
	synchronized void off() {
		log.fine("turning 5V audio supply OFF");
		stop();
		if(Configuration.getConfiguration().getRunningOnRaspberry()) {
			gpioPinAudioControl.low();
			// dummy wait - experiment to see if this prevents the crashes
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {}
		}
		log.fine("GPIO for audio set to low");
		
		activeVolume = 0;
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
	synchronized void playSound(Sound sound,Integer volume,boolean append) {
		log.fine("playSound: type="+sound.type+" volume="+volume+" append="+append);
		switch(sound.type) {
		case RADIO:
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
		case PLAYLIST:
			log.warning("SoundControl.playSound called for a playlist");
			break;
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
	
	
	// private methods
	
	/**
	 * plays an internet radio stream
	 * @param uri    radio stream URI
	 * @param volume optional volume in percent, null=no change
	 * @param append if true, the currently playing sounds gets not interrupted
	 *               and the new sound will start after it finished
	 */
	synchronized private void playRadioStream(String uri,Integer volume,boolean append) {
		log.fine("playRadeio: uri="+uri+" volume="+volume+" append="+append);
		
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
			
			answer += line;
		}
		
		if(!line.startsWith("OK")) {
			throw new IOException("mpd error during cmd "+cmd+" : "+answer);
		}
		
		return answer;
	}

	// private members
	private static final Logger  log    = Logger.getLogger( SoundControl.class.getName() );
	private static SoundControl  object = null;   // singleton object

	private GpioPinDigitalOutput gpioPinAudioControl;   // WiringPi Pin GPIOO5 = BRCM GPIO 24, used to enable Audio 5V power
	
	private Socket               socket;                // TCP socket
	private BufferedReader       reader;
	private PrintWriter          writer;
	
	private int                  activeVolume;          // caches the active volume, 0=off
}

