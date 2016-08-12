package alarmpi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.logging.Logger;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.RaspiPin;


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
			gpioPinAudioControl = gpioController.provisionDigitalOutputPin(RaspiPin.GPIO_04);
		}
		
		stop();
		off();
	}

	/**
	 * turns 5V audio power on
	 */
	void on() {
		log.fine("turning 5V audio supply ON");
		stop();
		if(Configuration.getConfiguration().getRunningOnRaspberry()) {
			gpioPinAudioControl.high();
		}
	}
	
	/**
	 * turns 5V audio power off
	 */
	void off() {
		log.fine("turning 5V audio supply OFF");
		stop();
		if(Configuration.getConfiguration().getRunningOnRaspberry()) {
			gpioPinAudioControl.low();
		}
		
		activeVolume = 0;
	}
	
	/**
	 * stops the current audio output
	 */
	void stop() {
		try {
			connect();
			try {
				activeSound  = null;
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
	void update() {
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
	 * plays a sound defined in the AlarmPi configuration file
	 * @param soundId  sound to play (index into sound list)
	 * @param volume   optional volume. If null, volume remains unchanged
	 * @param append   if true, the currently playing sounds gets not interrupted
	 *                 and the new sound will start after it finished
	 */
	void playSound(int soundId,Integer volume,boolean append) {
		// get sound from configuration
		activeSound = soundId;
		Configuration.Sound sound=Configuration.getConfiguration().getSoundList().get(activeSound);

		log.fine("playSound: ID="+soundId+" type="+sound.type+" volume="+volume);
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
		}
	}
	
	/**
	 * plays a local mp3 file
	 * @param filename filename of mp3 file
	 * @param volume   optional volume. If null, volume remains unchanged
	 * @param append   if true, the currently playing sounds gets not interrupted
	 *                 and the new sound will start after it finished
	 */
	void playFile(String filename,Integer volume,boolean append) {
		try {
			connect();
			// a file has no sound ID
			activeSound = null;
			try {
				if(!append) {
					sendCommand("stop");
					sendCommand("clear");
				}
				sendCommand("add "+filename);
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
	void setVolume(int volume) {
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
	int getVolume() {
		log.fine("returning active volume: "+activeVolume);
		return activeVolume;
	}
	
	/**
	 * returns the active sound
	 * @return active sound or null
	 */
	Integer getSound() {
		log.fine("returning active sound: "+activeSound);
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
	private void playRadioStream(String uri,Integer volume,boolean append) {
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
	private void connect() throws UnknownHostException, IOException {
		socket = new Socket(Configuration.getConfiguration().getMpdAddress(),Configuration.getConfiguration().getMpdPort());
		socket.setKeepAlive(true);
		reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
		writer = new PrintWriter(socket.getOutputStream(),true);
	}
	
	/**
	 * closes telnet connection again
	 * @return
	 * @throws IOException
	 */
	private void disconnect() throws IOException {
		if(socket!=null) {
			socket.close();
		}
		socket = null;
	}
	
	/**
	 * sends a single command to mpd
	 * @param cmd command to send
	 * @throws IOException
	 */
	private void sendCommand(String cmd) throws IOException {
		writer.print(cmd+"\n");
		writer.flush();

		// expect OK or ACK to indicate end of answer
		int c;
		String answer = new String();
		while(!answer.startsWith("OK") && !answer.startsWith("ACK")) {
			answer = new String();
			// read single line
			while ((c = reader.read()) != -1 && c!=10) {
				answer += (char)c;
			}
			log.info("sendCommand "+cmd+" returns "+answer);
		}
		
		if(!answer.startsWith("OK")) {
			throw new IOException("mpd error during cmd "+cmd+" : "+answer);
		}
	}

	// private members
	private static final Logger  log    = Logger.getLogger( SoundControl.class.getName() );
	private static SoundControl  object = null;   // singleton object

	private GpioPinDigitalOutput gpioPinAudioControl;   // WiringPi Pin GPIOO5 = BRCM GPIO 24, used to enable Audio 5V power
	
	private Socket               socket;                // TCP socket
	private BufferedReader       reader;
	private PrintWriter          writer;
	
	private int                  activeVolume;          // caches the active volume, 0=off
	private Integer              activeSound;           // caches the active sound ID or null
}
