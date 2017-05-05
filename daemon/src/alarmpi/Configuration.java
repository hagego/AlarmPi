package alarmpi;

import alarmpi.Configuration.Sound.Type;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.ini4j.Ini;
import org.ini4j.InvalidFileFormatException;


/**
 * Implements the configuration database for AlarmPi
 * Stores all parameters read initially from the configuration file,
 * but also parameters created at runtime.
 * Implemented as singleton
 */
public class Configuration {
	
	/**
	 * local class used as structure to store data about sounds
	 */
	static class Sound {
		enum Type {RADIO,FILE,EXTERNAL};
		
		String name;    // name (unique identifier)
		Type   type;    // type of sound (radio station, file, external)
		String source;  // source for this sound, either radio stream URL or filename
		
		/**
		 * Constructor
		 * @param name   sound name (as defined in configuration)
		 * @param type   sound type
		 * @param source sound source location (internet stream URL or filename)
		 */
		Sound(String name,Type type,String source) {
			this.name   = name;
			this.type   = type;
			this.source = source;
		}
	}
	
	/**
	 * local class used as structure to store light (LED) settings
	 */
	static class LightControlSettings {
		public enum Type {             // available Light Control types
			RASPBERRY,                 // Raspberry built-in GPIO18 PWM
			PCA9685                    // NXP PCA9685 IIC
		};
		
		Type    type;                  // light control type used
		int     address;               // i2c address for PCA9685
		int     pwmOffset;             // pwm value at which light starts to glow
		int     pwmFullScale;          // pwm full scale
		boolean pwmInversion = false;  // invert PWM value if set to true
		ArrayList<Integer> addresses;  // sub-addresses of lights (PCA9685 only)
	}
	
	/**
	 * local class used as structure to store push button settings
	 */
	class PushButtonSettings {
		int wiringpigpio;         // WiringPi GPIO address of input key
		int brightnessIncrement;  // LED control (single click): brightness increment in percent
		int soundId;              // sound control (double click): sound to play
		int soundVolume;          // sound control (double click): volume (in percent)
		int soundTimer;           // sound control (double click): timer to switch off in minutes (0 = no timer)
		int lightId;              // light conrol: ID of associated light (PCA9685 only)
	}
	
	/**
	 * local class used to store alarm settings
	 */
	static class Alarm implements Serializable {
		EnumSet<DayOfWeek> weekDays = EnumSet.noneOf(DayOfWeek.class);  // weekdays when this alarm is active                
		boolean            enabled          = false;     // on/off switch
		boolean            oneTimeOnly      = false;     // automatically disable alarm again after it got executed once
		boolean            skipOnce         = false;     // skip alarm one time
		LocalTime          time;                         // alarm time
		Integer            soundId;                      // ID of sound to play. Must be configured in configuration file
		String             greeting;                     // greeting text
		String             sound;                        // filename of alarm sound (or null)
		int                fadeInDuration       = 0;     // fade in time in seconds
		int                volumeFadeInStart    = 0;     // alarm sound fade-in start volume
		int                volumeFadeInEnd      = 0;     // alarm sound fade-in end volume
		int                volumeAlarmEnd       = 0;     // alarm sound end volume
		int                lightDimUpDuration   = 0;     // duration of light dim up after alarm start in seconds
		int                lightDimUpBrightness = 0;     // brightness of light at end of dim up phase in percent
		int                reminderInterval     = 0;     // reminder interval (s)
		int                duration             = 0;     // time until alarm stops (s)
		int                id;                           // unique ID for this alarm
		
		/**
		 * constructor
		 * @param type alarm type
		 */
		Alarm() {
			id = nextId++;
		}
		
		// private members
		private static final long serialVersionUID = -2395516218365040408L;
		private static       int  nextId           = 0;   // ID of next alarm that is generated
		
		void store() {
			// serialize the alarm and store it in preferences (make it persistent)
			// using alarm ID as key
			try {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				ObjectOutputStream oos;
				
				oos = new ObjectOutputStream( baos );
				oos.writeObject( this );
				oos.close();
				
				byte[] bytes = baos.toByteArray();
				Preferences prefs = Preferences.userNodeForPackage(Configuration.class);
				prefs.putByteArray(String.valueOf(id), bytes);
				prefs.flush();
			} catch (IOException e) {
				log.severe("Failed to serialize alarm: "+e.getMessage());
			} catch (BackingStoreException e) {
				log.severe("Failed to store alarm in preferences: "+e.getMessage());
			}
			
			log.info("modified and stored alarm with ID="+id);
		}
	}
	
	
	/**
	 * returns the singleton object with configuration data
	 * @return the Configuratoin singleton object
	 */
	static Configuration getConfiguration() {
		if(object==null) {
			log.severe("Configuration.read must be called before getConfiguration can be used");
			throw new RuntimeException("Configuration.read must be called before getConfiguration can be used");
		}
		
		return object;
	}

	/**
	 * private Constructor
	 * Reads configuration from the specified ini file
	 * @param iniFile   Ini object of the configuration file
	 */
	private Configuration(Ini ini) {
		if(!System.getProperty("os.name").toLowerCase(Locale.ENGLISH).startsWith("windows")) {
			runningOnRaspberry = true;
		}
		else {
			runningOnRaspberry = false;
		}
		
		log.info("parsing configuration file");

		// instantiate member objects
		soundList            = new ArrayList<Sound>();
		alarmList            = new LinkedList<Alarm>();
		alarmProcessQueue    = new ConcurrentLinkedQueue<Integer>();
		lightControlSettings = new LightControlSettings();
		pushButtonList       = new ArrayList<PushButtonSettings>();
        openhabCommands      = new LinkedList<String>();
        
        
        // mpd configuration
        mpdAddress    = ini.get("mpd", "address", String.class);
        mpdPort       = ini.get("mpd", "port", Integer.class);
        mpdFiles      = ini.get("mpd", "files", String.class);
        mpdTmpSubDir  = ini.get("mpd", "tmpSubDir", String.class);
        
        // network access (thru TCP clients)
        port          = ini.get("network", "port", Integer.class);
        
        // sounds (radio stations)
        Ini.Section sectionSound;
        int index=1;
        while((sectionSound=ini.get("sound"+index)) != null) {
        	log.config("found sound "+index);
        	
        	String soundType=sectionSound.get("type");
	    	boolean found = false;
	    	for(Type type:Sound.Type.values()) {
	    		if(soundType.equalsIgnoreCase(type.toString())) {
			    	Sound sound = new Sound(sectionSound.get("name"),type,sectionSound.get("source"));
			    	soundList.add(sound);
			    	found = true;
	    		}
	    	}
	    	if(!found) {
	    		log.severe("Unknown sound type: "+soundType);
	    	}
	    	
	    	index++;
        }
        
        // alarm settings
        alarmSettings            = new Alarm();
        Ini.Section sectionAlarm = ini.get("alarm");
        
        alarmSettings.greeting             = sectionAlarm.get("greeting", String.class, "");
    	alarmSettings.fadeInDuration       = sectionAlarm.get("fadeIn", Integer.class, 300);
    	alarmSettings.duration             = sectionAlarm.get("duration", Integer.class, 1800);
    	alarmSettings.reminderInterval     = sectionAlarm.get("reminderInterval", Integer.class, 300);
    	alarmSettings.volumeFadeInStart    = sectionAlarm.get("volumeFadeInStart", Integer.class, 10);
    	alarmSettings.volumeFadeInEnd      = sectionAlarm.get("volumeFadeInEnd", Integer.class, 60);
    	alarmSettings.volumeAlarmEnd       = sectionAlarm.get("volumeAlarmEnd", Integer.class, 70);
    	alarmSettings.lightDimUpDuration   = sectionAlarm.get("lightDimUpDuration", Integer.class, 600);
    	alarmSettings.lightDimUpBrightness = sectionAlarm.get("lightDimUpBrightness", Integer.class, 50);
    	alarmSettings.sound                = sectionAlarm.get("sound", String.class, "alarm_5s.mp3");
        
        // light control
        Ini.Section sectionLightControl=ini.get("light");
        if(sectionLightControl==null) {
        	log.warning("No light section in configuration file");
        }
        else {
        	lightControlSettings = new LightControlSettings();
        	lightControlSettings.addresses = new ArrayList<Integer>();
        	
	        String pwmType = sectionLightControl.get("type", String.class, "gpio18");
	    	if(pwmType!=null) {
	    		if(pwmType.equalsIgnoreCase("gpio18")) {
	    			lightControlSettings.type = LightControlSettings.Type.RASPBERRY;
	    		}
	    		else if(pwmType.equalsIgnoreCase("pca9685")) {
	    			lightControlSettings.type = LightControlSettings.Type.PCA9685;
	    		}
	    		else {
	    			log.severe("Invalid PWM type: "+pwmType);
	    		}
	    	}
        
	    	lightControlSettings.address      = sectionLightControl.get("address", Integer.class, 0);
	    	lightControlSettings.pwmInversion = sectionLightControl.get("pwmInversion", Boolean.class, false);
	    	lightControlSettings.pwmOffset    = sectionLightControl.get("pwmOffset", Integer.class, 0);
	    	lightControlSettings.pwmFullScale = sectionLightControl.get("pwmFullScale", Integer.class, 0);
	    
	    	index=1;
	    	while(sectionLightControl.get("address"+index, Integer.class) != null ) {
	    		lightControlSettings.addresses.add(sectionLightControl.get("address"+index, Integer.class));
	    		index++;
	    	}
        }
    	
    	// push buttons
        Ini.Section sectionButton;
        index=1;
        while((sectionButton=ini.get("button"+index)) != null) {
        	PushButtonSettings pushButtonSettings = new PushButtonSettings();
        	pushButtonSettings.wiringpigpio        = sectionButton.get("wiringpigpio", Integer.class, 0);
			pushButtonSettings.brightnessIncrement = sectionButton.get("brightnessIncrement", Integer.class, 10);
			// config file starts counting from 1, internally we use array indexes (starting from 0)
			pushButtonSettings.lightId             = sectionButton.get("light", Integer.class, 0)-1;
			pushButtonSettings.soundId             = sectionButton.get("sound", Integer.class, 0)-1;
			pushButtonSettings.soundVolume         = sectionButton.get("soundVolume", Integer.class, 40);
			pushButtonSettings.soundTimer          = sectionButton.get("soundTimer", Integer.class, 30);
			
			// sanity checks
			if(pushButtonSettings.lightId<0 || pushButtonSettings.lightId>=lightControlSettings.addresses.size()) {
				log.severe("push button index "+index+" references invalid light "+pushButtonSettings.lightId+1);
			}
			else {
				if(pushButtonSettings.soundId<0 || pushButtonSettings.soundId>=soundList.size()) {
					log.severe("push button index "+index+" references invalid sound "+pushButtonSettings.soundId+1);
				}
				else {
					pushButtonList.add(pushButtonSettings);
				}
			}
			
			index++;
        }
    	
        // weather
		Ini.Section sectionWeather = ini.get("weather");
		weatherLocation = sectionWeather.get("location", String.class, "");
		
		// openhab
		Ini.Section sectionOpenhab = ini.get("openhab");
		openhabAddress = sectionOpenhab.get("address", String.class, "");
	    openhabPort    = sectionOpenhab.get("port", String.class, "");
	    
	    index=1;
	    String command;
        while(!(command = sectionOpenhab.get("command"+index, String.class, "")).isEmpty()) {
        	log.config("found command"+index);
        	openhabCommands.add(command);
        	index++;
	    }
        
        // calendar
        Ini.Section sectionCalendar = ini.get("calendar");
        if(sectionCalendar!=null) {
        	googleCalendarSummary = sectionCalendar.get("summary", String.class, "");
        }
        
		// read alarms from preferences
		readAlarms();
		
		// dump the content into logfile
		dump();
	}
	

	
	/**
	 * reads the ini file from disk
	 * @param  filename full filename of ini file
	 * @return true if ini file could be loaded, otherwise false 
	 */
	static boolean read(String filename) {
		Ini iniFile = new Ini();
		
		try {
			log.info("reading configuration file "+filename);
			iniFile.load(new FileReader(filename));
			
			if(object==null) {
				object = new Configuration(iniFile);
			}
			else {
				log.severe("Configuration.read can only be called once");
				return false;
			}
			
			return true;
		} catch (InvalidFileFormatException e) {
			log.severe("Invalid format of ini file "+filename);
			log.severe(e.getMessage());
			return false;
		} catch (FileNotFoundException e) {
			log.severe("Unable to find ini file "+filename);
			log.severe(e.getMessage());
			return false;
		} catch (IOException e) {
			log.severe("IO Exception during reading of ini file "+filename);
			log.severe(e.getMessage());
			return false;
		}
	}

	/**
	 * @return if running on raspberry
	 */
	boolean getRunningOnRaspberry() {
		return runningOnRaspberry;
	}
	
	/**
	 * @return the network port for the AlarmPi TCP remote interface
	 */
	int getPort() {
		return port;
	}
	
	/**
	 * @return the MPD network Address
	 */
	String getMpdAddress() {
		return mpdAddress;
	}

	/**
	 * @return the MPD network Port
	 */
	int getMpdPort() {
		return mpdPort;
	}
	
	/**
	 * @return the MPD file directory
	 */
	String getMpdFileDirectory() {
		return mpdFiles;
	}
	
	/**
	 * @return the MPD tmp file directory
	 */
	String getMpdTmpSubDir() {
		return mpdTmpSubDir;
	}
	
	/**
	 * @return the Open Weather Map location
	 */
	String getWeatherLocation() {
		return weatherLocation;
	}
	
	/**
	 * @return the sound list
	 */
	final ArrayList<Sound> getSoundList() {
		return soundList;
	}
	
	/**
	 * @return the alarm list as read-only object
	 */
	final List<Alarm> getAlarmList() {
		return alarmList;
	}
	
	/**
	 * @return light control settings as read-only object
	 */
	final LightControlSettings getLightControlSettings() {
		return lightControlSettings;
	}

	/**
	 * @return the push button settings
	 */
	final List<PushButtonSettings> getPushButtons() {
		return pushButtonList;
	}
	
	/**
	 * @return the openhab server address
	 */
	final String getOpenhabAddress() {
		return openhabAddress;
	}
	
	/**
	 * @return the openhab server port
	 */
	final String getOpenhabPort() {
		return openhabPort;
	}
	
	/**
	 * @return the list of openhab commands possible on this AlarmPi
	 */
	final List<String> getOpenhabCommands() {
		return openhabCommands;
	}
	
	/**
	 * returns the summary name of the Google calendar to check or null
	 * @return summary name of the Google calendar to check or null
	 */
	final String getCalendarSummary() {
		return googleCalendarSummary;
	}
	
	/**
	 * reads stored alarms from Java Preferences
	 */
	void readAlarms() {

		// reset alarm list just in case...
		alarmList.clear();
		
		try {
			int count=0;
			Preferences prefs = Preferences.userNodeForPackage(Configuration.class);
			for(String id:prefs.keys()) {
				byte bytes[] = prefs.getByteArray(id, null);
				if(bytes!=null) {
					ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
					Alarm alarm = (Alarm)ois.readObject();
					alarmList.add(alarm);
					
					// adopt fixed properties to latest from configuration file
					alarm.fadeInDuration       = alarmSettings.fadeInDuration;
					alarm.volumeFadeInStart    = alarmSettings.volumeFadeInStart;
					alarm.volumeFadeInEnd      = alarmSettings.volumeFadeInEnd;
					alarm.volumeAlarmEnd       = alarmSettings.volumeAlarmEnd;
					alarm.lightDimUpDuration   = alarmSettings.lightDimUpDuration;
					alarm.lightDimUpBrightness = alarmSettings.lightDimUpBrightness;
					alarm.reminderInterval     = alarmSettings.reminderInterval;
					alarm.duration             = alarmSettings.duration;
					alarm.greeting             = alarmSettings.greeting;
					
					// adjust next alarm ID
					if(alarm.id>=Alarm.nextId) {
						Alarm.nextId = alarm.id+1;
					}
				}
				count++;
			}
			log.info("Read "+count+" alarms");
		} catch (IOException e) {
			log.severe("Failed to serialize alarm");
			log.severe(e.getMessage());
		} catch (BackingStoreException e) {
			log.severe("Failed to read alarm from preferences");
			log.severe(e.getMessage());
		} catch (ClassNotFoundException e) {
			log.severe("Failed to find class during serialization of alarms");
			log.severe(e.getMessage());
		} 
	}
	
	/**
	 * creates a new   alarm
	 * @param  days    week days when alarm shall be active
	 * @param  time    alarm time
	 * @param  soundId sound ID
	 * @return alarm ID
	 */
	synchronized int createAlarm(EnumSet<DayOfWeek> days,LocalTime time,Integer soundId) {
		Alarm alarm = new Alarm();
		
		// assign some properties directly from configuration file
		alarm.fadeInDuration       = alarmSettings.fadeInDuration;
		alarm.volumeFadeInStart    = alarmSettings.volumeFadeInStart;
		alarm.volumeFadeInEnd      = alarmSettings.volumeFadeInEnd;
		alarm.volumeAlarmEnd       = alarmSettings.volumeAlarmEnd;
		alarm.lightDimUpDuration   = alarmSettings.lightDimUpDuration;
		alarm.lightDimUpBrightness = alarmSettings.lightDimUpBrightness;
		alarm.reminderInterval     = alarmSettings.reminderInterval;
		alarm.duration             = alarmSettings.duration;
		alarm.greeting             = alarmSettings.greeting;
		alarm.sound                = alarmSettings.sound;
		
		// add to alarm list
		alarmList.add(alarm);
		
		// set remaining properties
		modifyAlarm(alarm.id,days,time,soundId,false,false,false);
		
		log.info("created and stored alarm with ID="+alarm.id);
		return alarm.id;
	}
	
	/**
	 * modifies an existing alarm
	 * @param  id          alarm ID
	 * @param  days        week days when alarm shall be active
	 * @param  time        alarm time
	 * @param  soundId     sound ID to play
	 * @param  enabled     on/off switch 
	 * @param  oneTimeOnly if true, alarm gets disabled again after executed once
	 * @param  skipOnce    if true, alarm gest skipped on time
	 * @return if alarm with this ID was found and modified
	 */
	synchronized boolean modifyAlarm(int alarmId,EnumSet<DayOfWeek> days,LocalTime time,Integer soundId,boolean enabled,boolean oneTimeOnly,boolean skipOnce) {
		boolean found = false;
		Iterator<Alarm> it = alarmList.iterator();
		while(it.hasNext()) {
			Alarm alarm = it.next();
			if(alarm.id==alarmId) {
				// assign properties given as function parameters
				alarm.weekDays    = days;
				alarm.time        = time;
				alarm.soundId     = soundId;
				alarm.enabled     = enabled;
				alarm.oneTimeOnly = oneTimeOnly;
				alarm.skipOnce    = skipOnce;
				
				// add to queue of alarms that need to be processed
				alarmProcessQueue.add(alarm.id);
				
				// serialize the alarm and store it in preferences (make it persistent)
				// using alarm ID as key
				alarm.store();
				
				found = true;
				break;
			}
		}
		
		return found;
	}
	
	/**
	 * returns the alarm object with the specified ID
	 * @param  alarmId
	 * @return Alarm object or null if not found
	 */
	synchronized Alarm getAlarm(int alarmId) {
		Alarm alarm;

		Iterator<Alarm> it = alarmList.iterator();
		while(it.hasNext()) {
			alarm=it.next();
			if(alarm.id==alarmId) {
				return alarm;
			}
		}
		
		return null;
	}
	
	/**
	 * enables or disables an alarm
	 * @param alarmId      ID of the alarm to enable/disable
	 * @param enable       true/false enables/disables the alarm
	 * @param stopIfActive if enable=false, this determines if the alarm gets stopped if it is active 
	 */
	synchronized boolean enableAlarm(int alarmId,boolean enabled,boolean stopIfActive) {
		boolean found = false;
		Iterator<Alarm> it = alarmList.iterator();
		while(it.hasNext()) {
			Alarm alarm = it.next();
			if(alarm.id==alarmId) {
				alarm.enabled = enabled;
				
				// delete all events belonging to this alarm
				if(enabled==true || (enabled==false && stopIfActive==true)) {
					alarmProcessQueue.add(alarmId);
				}
				alarm.store();
				
				found = true;
				break;
			}
		}
		
		return found;
	}
	
	/**
	 * deletes an alarm
	 * @param alarmId ID of the alarm to delete
	 */
	synchronized boolean deleteAlarm(int alarmId) {
		boolean found = false;
		Iterator<Alarm> it = alarmList.iterator();
		while(it.hasNext()) {
			Alarm a = it.next();
			if(a.id==alarmId) {
				// delete this alarm from in-memory alarm list
				it.remove();
				
				// delete all events belonging to this alarm
				alarmProcessQueue.add(alarmId);
				
				// remove from preferences
				Preferences prefs = Preferences.userNodeForPackage(Configuration.class);
				prefs.remove(String.valueOf(alarmId));
				try {
					prefs.flush();
				} catch (BackingStoreException e) {
					log.severe("Failed to delete alarm from preferences");
					log.severe(e.getMessage());
				}
				
				found = true;
				break;
			}
		}
		
		return found;
	}
	
	
	/**
	 * returns an alarm that changed and needs to be processed by the Controller thread
	 * @return ID of an alarm to be processed or null if there is nothing to be done
	 */
	synchronized final Integer getAlarmToProcess() {
		return alarmProcessQueue.poll();
	}
	
	
	//
	// private methods
	//
	/**
	 * dumps all settings into logfile
	 */
	private void dump() {
		String dump = new String("configuration data:\n");
		dump += "  mpdAddress="+mpdAddress+"\n";
		dump += "  mpdPort="+mpdPort+"\n";
		dump += "  mpdFiles="+mpdFiles+" mpdTmpSubDir="+mpdTmpSubDir+"\n";
		dump += "  port="+port+"\n";
		dump += "  weather location="+weatherLocation+"\n";
		dump += "  light control: type="+lightControlSettings.type+" address=" + lightControlSettings.address+"\n";
		dump += "                 pwmInversion="+lightControlSettings.pwmInversion+" pwmOffset="+lightControlSettings.pwmOffset+" pwmFullScale="+lightControlSettings.pwmFullScale+" addresses: ";
		for(Integer address:lightControlSettings.addresses) {
			dump += address+" ";
		}
		dump += "\n";
		dump += "  Sounds:\n";
		for(Sound sound:soundList) {
			dump += "    name="+sound.name+"  type="+sound.type+"  source="+sound.source+"\n";
		}
		dump += "  Alarm: greeting="+alarmSettings.greeting+"\n";
		dump += "         fadeInDuration="+alarmSettings.fadeInDuration+" duration="+alarmSettings.duration+" reminderInterval="+alarmSettings.reminderInterval+"\n";
		dump += "         volumeFadeInStart="+alarmSettings.volumeFadeInStart+" volumeFadeInEnd="+alarmSettings.volumeFadeInEnd+" volumeAlarmEnd="+alarmSettings.volumeAlarmEnd+"\n";
		dump += "         lightDimUpDuration="+alarmSettings.lightDimUpDuration+" lightDimUpBrightness="+alarmSettings.lightDimUpBrightness+"\n";
		dump += "         sound="+alarmSettings.sound+"\n";
		dump += "  Stored alarms:\n";
		for(Alarm alarm:alarmList) {
			dump += "    id="+alarm.id+" enabled="+alarm.enabled+" oneTimeOnly="+alarm.oneTimeOnly+" skipOnce="+alarm.skipOnce+" time="+alarm.time+" days="+alarm.weekDays+"\n";
		}
		dump += "  push button configurations:\n";
		for(PushButtonSettings pushButtonSettings:pushButtonList) {
			dump += "    light: internal ID="+pushButtonSettings.lightId+" increment="+pushButtonSettings.brightnessIncrement+"\n";
			dump += "    sound: internal ID="+pushButtonSettings.soundId+" volume="+pushButtonSettings.soundVolume+" timer="+pushButtonSettings.soundTimer+"\n";
		}
		dump += "  openhab configuration:\n";
		dump += "    address="+openhabAddress+" port="+openhabPort+"\n";
		for(String cmd:openhabCommands) {
			dump += "    command="+cmd+"\n";
		}
		dump += "  calendar:\n";
		dump += "    summary="+googleCalendarSummary+"\n";
		
		log.config(dump);
	}
	

	// private members
	private static final Logger   log    = Logger.getLogger( Configuration.class.getName() );
	private static Configuration  object = null;             // singleton object
	
	// settings in configuration file
	private final boolean                    runningOnRaspberry;
	private int                              port;                  // AlarmPi networt port for remote control
	private String                           mpdAddress;            // mpd network address
	private int                              mpdPort;               // mpd network port
	private String                           mpdFiles;              // directory for MPD sound files
	private String                           mpdTmpSubDir;          // subdirectory for MPD temporary sound files
	private String                           weatherLocation;       // Open Weather Map location for weather forecast
	private Alarm                            alarmSettings;         // dummy alarm object with default settings
	private LightControlSettings             lightControlSettings;  // light control settings
	private ArrayList<Sound>                 soundList;             // list with available sounds (as defined in configuration)
	private ArrayList<PushButtonSettings>    pushButtonList;        // pushbutton settings
	private String                           googleCalendarSummary; // summary name of google calendar (or null)
	private String                           openhabAddress;        // openhab server network address
	private String                           openhabPort;           // openhab server port
	private List<String>                     openhabCommands;       // list of possible openhab commands on this AlarmPi
	
	// other data
	private List<Alarm>          alarmList;                         // alarm list
	private Queue<Integer>       alarmProcessQueue;                 // queue of alarm IDs that need to be processed by Controller thread
}
