package alarmpi;

import alarmpi.Configuration.Sound.Type;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;

import org.ini4j.Ini;
import org.ini4j.InvalidFileFormatException;


/**
 * Implements the configuration database for AlarmPi
 * Stores all parameters read initially from the configuration file,
 * but also parameters created at runtime.
 * Implemented as singleton
 */
public class Configuration {
	
	public String getName() {
		return name;
	}
	/**
	 * local class used as structure to store data about sounds
	 */
	static class Sound {
		enum Type {RADIO,FILE,EXTERNAL,PLAYLIST};
		
		String      name;             // name (unique identifier)
		Type        type;             // type of sound (radio station, file, external)
		String      source;           // source for this sound, either radio stream URL or filename
		Integer     duration = null;  // duration of a song or null (only valid for files)
		List<Sound> playlist = null;  // list of sounds in case type is PLAYLIST
		
		/**
		 * Constructor
		 * @param name   sound name (as defined in configuration)
		 * @param type   sound type
		 * @param source sound source location (internet stream URL or filename)
		 */
		Sound(String name,Type type,String source) {
			this.name     = name;
			this.type     = type;
			this.source   = source;
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
		boolean useAws;           // if true, single click is triggers Amazon AWS speech control
		int brightnessIncrement;  // LED control (single click): brightness increment in percent
		int soundId;              // sound control (double click): sound to play
		int soundVolume;          // sound control (double click): volume (in percent)
		int soundTimer;           // sound control (double click): timer to switch off in minutes (0 = no timer)
		int lightId;              // light control: ID of associated light (PCA9685 only)
	}
	

	
	/**
	 * returns the singleton object with configuration data
	 * @return the Configuration singleton object
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
		alarmProcessQueue    = new ConcurrentLinkedQueue<Alarm>();
		lightControlSettings = new LightControlSettings();
		pushButtonList       = new ArrayList<PushButtonSettings>();

        // general data
		Ini.Section sectionGeneral = ini.get("general");
        name          = sectionGeneral.get("name", String.class, "");
        if(name==null) {
        	name = "AlarmPi";
        }
        volumeDefault = sectionGeneral.get("volumeDefault", Integer.class,50);
        
        // mpd configuration
        mpdAddress    = ini.get("mpd", "address", String.class);
        mpdPort       = ini.get("mpd", "port", Integer.class);
        mpdFiles      = ini.get("mpd", "files", String.class);
        mpdTmpSubDir  = ini.get("mpd", "tmpSubDir", String.class);
        
        // network access (thru TCP clients)
        port           = ini.get("network", "port", Integer.class);
        Integer jsonServerPortTemp = ini.get("network", "jsonServerPort", Integer.class);
        if(jsonServerPortTemp==null) {
        	jsonServerPort = 0;
        	log.info("JSON Server disabled (no port specified)");
        }
        else {
        	jsonServerPort = jsonServerPortTemp;
        }
        
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
		// read alarms from preferences
		alarmList = Alarm.readAll();
		
        Ini.Section sectionAlarm = ini.get("alarm");
        
        alarmSettings = new Alarm();
        alarmSettings.setGreeting(sectionAlarm.get("greeting", String.class, ""));
        alarmSettings.setFadeInDuration(sectionAlarm.get("fadeIn", Integer.class, 300));
        alarmSettings.setDuration(sectionAlarm.get("duration", Integer.class, 1800));
        alarmSettings.setReminderInterval(sectionAlarm.get("reminderInterval", Integer.class, 300));
        alarmSettings.setVolumeFadeInStart(sectionAlarm.get("volumeFadeInStart", Integer.class, 10));
        alarmSettings.setVolumeFadeInEnd(sectionAlarm.get("volumeFadeInEnd", Integer.class, 60));
        alarmSettings.setVolumeAlarmEnd(sectionAlarm.get("volumeAlarmEnd", Integer.class, 70));
        alarmSettings.setLightDimUpDuration(sectionAlarm.get("lightDimUpDuration", Integer.class, 600));
        alarmSettings.setLightDimUpBrightness(sectionAlarm.get("lightDimUpBrightness", Integer.class, 50));
        alarmSettings.setSound(sectionAlarm.get("sound", String.class, "alarm_5s.mp3"));
        
        for(Alarm alarm:alarmList) {
        	alarm.setGreeting(sectionAlarm.get("greeting", String.class, ""));
        	alarm.setFadeInDuration(sectionAlarm.get("fadeIn", Integer.class, 300));
        	alarm.setDuration(sectionAlarm.get("duration", Integer.class, 1800));
        	alarm.setReminderInterval(sectionAlarm.get("reminderInterval", Integer.class, 300));
        	alarm.setVolumeFadeInStart(sectionAlarm.get("volumeFadeInStart", Integer.class, 10));
        	alarm.setVolumeFadeInEnd(sectionAlarm.get("volumeFadeInEnd", Integer.class, 60));
        	alarm.setVolumeAlarmEnd(sectionAlarm.get("volumeAlarmEnd", Integer.class, 70));
        	alarm.setLightDimUpDuration(sectionAlarm.get("lightDimUpDuration", Integer.class, 600));
        	alarm.setLightDimUpBrightness(sectionAlarm.get("lightDimUpBrightness", Integer.class, 50));
        	alarm.setSound(sectionAlarm.get("sound", String.class, "alarm_5s.mp3"));
        }
        
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
        	pushButtonSettings.useAws              = sectionButton.get("useAws", Boolean.class, false);
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
        
        // mqtt
		Ini.Section sectionMqtt = ini.get("mqtt");
		if(sectionMqtt!=null) {
			mqttAddress    = sectionMqtt.get("address", String.class, null);
		    mqttPort       = sectionMqtt.get("port", Integer.class, null);
		    mqttKeepAlive  = sectionMqtt.get("keepalive", Integer.class, 60);
		    mqttPublishTopicShortClick    = sectionMqtt.get("publishTopicShortClick", String.class, null);
		    mqttPublishTopicLongClick     = sectionMqtt.get("publishTopicLongClick", String.class, null);
		    mqttSubscribeTopicTemperature = sectionMqtt.get("subscribeTopicTemperature", String.class, null);
		    mqttPublishTopicAlarmList     = sectionMqtt.get("publishTopicAlarmList", String.class, null);
		}
        
        
        // calendar
        Ini.Section sectionCalendar = ini.get("calendar");
        if(sectionCalendar!=null) {
        	googleCalendarSummary = sectionCalendar.get("summary", String.class, "");
        }
        
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
				log.severe("Configuration.read() can only be called once");
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
	 * @return the default sound volume
	 */
	int getDefaultVolume() {
		return volumeDefault;
	}
	
	/**
	 * @return the network port for the AlarmPi TCP remote interface
	 */
	int getPort() {
		return port;
	}

	/**
	 * @return the network port for the HTTP JSON server remote interface
	 */
	int getJsonServerPort() {
		return jsonServerPort;
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
	 * processes the sound list:
	 *  - adds the duration for each file (song)
	 *  - for playlists, build up the list of Sound objects in the playlist
	 */
	void processSoundList() {
		// get duration of files
		soundList.stream().filter(s -> s.type==Type.FILE).forEach(s -> s.duration=SoundControl.getSoundControl().getSongDuration(s.source));
		
		// build playlists
		soundList.stream().filter(p -> p.type==Type.PLAYLIST).forEach(p ->
			{
				p.playlist = new LinkedList<Sound>();
				log.fine("processing playlist "+p.name);
				Arrays.asList(p.source.split(",")).stream().forEach(r -> 
					p.playlist.addAll(soundList.stream().filter(s -> s.name.equals(r)).collect(Collectors.toList()))
				);
			});
	}
	
	/**
	 * @return the sound list
	 */
	final List<Sound> getSoundList() {
		return soundList;
	}
	
	/**
	 * returns the sound object for a given sound ID
	 * @param soundId
	 * @return sound object for the given ID
	 */
	final Sound getSoundFromId(int soundId) {
		if(soundId>=0 && soundId < soundList.size()) {
			return soundList.get(soundId);
		}
		else {
			log.severe("getSoundFromId called for invalid sound id: "+soundId);
			return null;
		}
	}
	
	/**
	 * @return the alarm list as read-only object
	 */
	final List<Alarm> getAlarmList() {
		return alarmList;
	}
	
	/**
	 * @return the alarm list as JsonArray
	 */
	final JsonArray getAlarmsAsJsonArray() {
		// add list of all alarms
		JsonArrayBuilder alarmArrayBuilder = Json.createBuilderFactory(null).createArrayBuilder();
		for(Alarm alarm:Configuration.getConfiguration().getAlarmList()) {
			alarmArrayBuilder.add(alarm.toJasonObject());
		}
		
		return alarmArrayBuilder.build();
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
	 * @return the MQTT broker address
	 */
	final String getMqttAddress() {
		return mqttAddress;
	}
	
	/**
	 * @return the MQTT broker port
	 */
	final Integer getMqttPort() {
		return mqttPort;
	}
	
	/**
	 * @return the MQTT keepalive period (in seconds)
	 */
	final Integer getMqttKeepalive() {
		return mqttKeepAlive;
	}
	
	/**
	 * @return the MQTT topic to subsrice for locally measured temperature
	 */
	final String getMqttSubscribeTopicTemperature() {
		return mqttSubscribeTopicTemperature;
	}
	
	/**
	 * @return the MQTT topic to publish on a short button click
	 */
	final String getMqttPublishTopicShortClick() {
		return mqttPublishTopicShortClick;
	}
	
	/**
	 * @return the MQTT topic to publish on a long button click
	 */
	final String getMqttPublishTopicLongClick() {
		return mqttPublishTopicLongClick;
	}
	
	/**
	 * @return the MQTT topic to publish the alarm list
	 */
	final String getMqttPublishTopicAlarmList() {
		return mqttPublishTopicAlarmList;
	}
	
	/**
	 * returns the summary name of the Google calendar to check or null
	 * @return summary name of the Google calendar to check or null
	 */
	final String getCalendarSummary() {
		return googleCalendarSummary;
	}
	
	/**
	 * creates a new   alarm
	 * @param  days    week days when alarm shall be active
	 * @param  time    alarm time
	 * @param  soundId sound ID
	 * @return alarm ID
	 */
	synchronized int createAlarm(EnumSet<DayOfWeek> days,LocalTime time,Integer soundId) {
		Alarm alarm = new Alarm(alarmSettings);
		
		// add to alarm list
		alarmList.add(alarm);
		
		// set remaining properties
		alarm.startTransaction();
		
		alarm.setWeekDays(days);
		alarm.setTime(time);
		alarm.setSoundId(soundId);
		
		alarm.endTransaction();
		
		log.info("created and stored alarm with ID="+alarm.getId());
		return alarm.getId();
	}
	

	void addAlarmToProcess(Alarm alarm) {
		alarmProcessQueue.add(alarm);
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
			if(alarm.getId()==alarmId) {
				return alarm;
			}
		}
		
		return null;
	}
	
	synchronized void removeAlarmFromList(Alarm alarm) {
		alarmList.remove(alarm);
	}
	
	/**
	 * returns an alarm that changed and needs to be processed by the Controller thread
	 * @return ID of an alarm to be processed or null if there is nothing to be done
	 */
	synchronized final Alarm getAlarmToProcess() {
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
		dump += "  name: "+name+"\n";
		dump += "  default volume: "+volumeDefault+"\n";
		dump += "  mpdAddress="+mpdAddress+"\n";
		dump += "  mpdPort="+mpdPort+"\n";
		dump += "  mpdFiles="+mpdFiles+" mpdTmpSubDir="+mpdTmpSubDir+"\n";
		dump += "  cmdServerPort="+port+"\n";
		dump += "  jsonServerPort="+jsonServerPort+"\n";
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
		
		dump += "  Alarm: greeting="+alarmSettings.getGreeting()+"\n";
		dump += "         fadeInDuration="+alarmSettings.getFadeInDuration()+" duration="+alarmSettings.getDuration()+" reminderInterval="+alarmSettings.getReminderInterval()+"\n";
		dump += "         volumeFadeInStart="+alarmSettings.getVolumeFadeInStart()+" volumeFadeInEnd="+alarmSettings.getVolumeFadeInEnd()+" volumeAlarmEnd="+alarmSettings.getVolumeAlarmEnd()+"\n";
		dump += "         lightDimUpDuration="+alarmSettings.getLightDimUpDuration()+" lightDimUpBrightness="+alarmSettings.getLightDimUpBrightness()+"\n";
		dump += "         sound="+alarmSettings.getSound()+"\n";
		dump += "  Stored alarms:\n";
		for(Alarm alarm:alarmList) {
			dump += "    id="+alarm.getId()+" enabled="+alarm.isEnabled()+" oneTimeOnly="+alarm.isOneTimeOnly()+" skipOnce="+alarm.isSkipOnce()+" time="+alarm.getTime()+" days="+alarm.getWeekDays()+" sound ID="+alarm.getSoundId()+"\n";
		}
		dump += "  push button configurations:\n";
		for(PushButtonSettings pushButtonSettings:pushButtonList) {
			dump += "    light: internal ID="+pushButtonSettings.lightId+" increment="+pushButtonSettings.brightnessIncrement+"\n";
			dump += "    useAws="+pushButtonSettings.useAws+"\n";
			dump += "    sound: internal ID="+pushButtonSettings.soundId+" volume="+pushButtonSettings.soundVolume+" timer="+pushButtonSettings.soundTimer+"\n";
		}
		
		if(mqttAddress==null || mqttPort==null) {
			dump += "  no MQTT Broker configured";
		}
		else {
			dump += "  MQTT broker: address="+mqttAddress+" port="+mqttPort+"\n";
			if(mqttPublishTopicLongClick!=null) {
				dump += "    MQTT publishTopicLongCick="+mqttPublishTopicLongClick+"\n";
			}
			if(mqttPublishTopicAlarmList!=null) {
				dump += "    MQTT publishTopicAlarmList="+mqttPublishTopicAlarmList+"\n";
			}
			if(mqttSubscribeTopicTemperature!=null) {
				dump += "    MQTT subscribeTopicTemperature="+mqttSubscribeTopicTemperature+"\n";
			}
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
	private String                           name;                  // AlarmPi Name
	private int                              volumeDefault;         // default sound volume
	private int                              port;                  // AlarmPi networt port for remote control
	private int                              jsonServerPort;        // tcp port for HTTP JSON based control
	private String                           mpdAddress;            // mpd network address
	private int                              mpdPort;               // mpd network port
	private String                           mpdFiles;              // directory for MPD sound files
	private String                           mpdTmpSubDir;          // subdirectory for MPD temporary sound files
	private String                           weatherLocation;       // Open Weather Map location for weather forecast
	private LightControlSettings             lightControlSettings;  // light control settings
	private ArrayList<Sound>                 soundList;             // list with available sounds (as defined in configuration)
	private ArrayList<PushButtonSettings>    pushButtonList;        // pushbutton settings
	private String                           googleCalendarSummary; // summary name of google calendar (or null)
	private String                           mqttAddress;           // MQTT Broker address
	private Integer                          mqttPort;              // MQTT broker port
	private Integer                          mqttKeepAlive;         // MQTT keepalive interval in seconds
	private String                           mqttPublishTopicShortClick;    // MQTT topic published on a short click of a connected button
	private String                           mqttPublishTopicLongClick;     // MQTT topic published on a long click of a connected button
	private String                           mqttPublishTopicAlarmList;     // MQTT topic for publishing alarm list as JSON object
	private String                           mqttSubscribeTopicTemperature; // MQTT topic subscribed to get locally measured temperature
	
	// other data
	private Alarm              alarmSettings; 
	private List<Alarm>        alarmList;                         // alarm list
	private Queue<Alarm>       alarmProcessQueue;                 // queue of alarm IDs that need to be processed by Controller thread
}
