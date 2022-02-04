package alarmpi;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

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
	 * local class used as structure to store light (LED) settings
	 */
	static class LightControlSettings {
		public enum Type {             // available Light Control types
			NONE,                      // no light
			RASPBERRY,                 // Raspberry built-in GPIO18 PWM
			PCA9685,                   // NXP PCA9685 PWM IIC
			NRF24LO1,                  // remote control based on nRF24LO1
			MQTT
		};
		
		Type    type;                  // light control type used
		String  name;                  // light name (identifier)
		int     id;                    // light ID
		int     deviceAddress;         // i2c address for PCA9685
		int     pwmOffset;             // pwm value at which light starts to glow
		int     pwmFullScale;          // pwm full scale
		boolean pwmInversion = false;  // invert PWM value if set to true
		int     ledId;                 // ID of LED to control (PCA9685 only)
	}
	
	/**
	 * local class used as structure to store push button settings
	 */
	static class ButtonSettings {
		public enum Type {        // available button types
			GPIO,                 // direct attached to gpio
			FLIC                  // Flic bluetooth button
		};
		
		Type    type;                 // button type
		int     id;                   // button ID
		boolean triggerSpeechControl; // if set to true, single click triggers speech control
		int     bcmgpio;              // BCM GPIO address of input key
		int     brightnessIncrement;  // LED control (single click): brightness increment in percent
		int     soundId;              // sound control (double click): sound to play
		int     soundVolume;          // sound control (double click): volume (in percent)
		int     soundTimer;           // sound control (double click): time to switch off in minutes (0 = no timer)
		List<Integer> lightIds;       // light control: IDs of associated lights
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
		this.iniFile = ini;
		
		if(System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("linux")
				&& System.getProperty("os.arch").toLowerCase(Locale.ENGLISH).contains("arm")) {
			runningOnRaspberry = true;
		}
		else {
			runningOnRaspberry = false;
			Alarm.setStorageDirectory("data");
		}
		
		log.info("parsing configuration file");

		// instantiate member objects
		lightControlSettingsList = new ArrayList<>();;
		buttonSettingsList       = new ArrayList<>();

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
        port = ini.get("network", "port", Integer.class);
        jsonServerPort = ini.get("network", "jsonServerPort", Integer.class);
        
       
        // light control. Search for sections [light1], [light2], ...
        int index = 1;
        Ini.Section sectionLightControl;
        while((sectionLightControl=ini.get("light"+index))!=null) {
        	log.fine("found section light"+index);
        	LightControlSettings lightControlSettingItem = new LightControlSettings();
        	
        	lightControlSettingItem.id = index;
            String pwmType = sectionLightControl.get("type", String.class, null);
	    	if(pwmType!=null) {
	    		if(pwmType.equalsIgnoreCase("none")) {
	    			lightControlSettingItem.type = LightControlSettings.Type.NONE;
	    		}
	    		else if(pwmType.equalsIgnoreCase("gpio18")) {
	    			lightControlSettingItem.type = LightControlSettings.Type.RASPBERRY;
	    		}
	    		else if(pwmType.equalsIgnoreCase("pca9685")) {
	    			lightControlSettingItem.type = LightControlSettings.Type.PCA9685;
	    		}
	    		else if(pwmType.equalsIgnoreCase("nrf24lo1")) {
	    			lightControlSettingItem.type = LightControlSettings.Type.NRF24LO1;
	    		}
	    		else if(pwmType.equalsIgnoreCase("mqtt")) {
	    			lightControlSettingItem.type = LightControlSettings.Type.MQTT;
	    		}
	    		else {
	    			log.severe("Invalid light control type: "+pwmType);
	    			lightControlSettingItem.type = LightControlSettings.Type.NONE;
	    		}
	    		
	    		lightControlSettingItem.name          = sectionLightControl.get("name", String.class, "");
	    		lightControlSettingItem.deviceAddress = sectionLightControl.get("deviceAddress", Integer.class, 0);
		    	lightControlSettingItem.pwmInversion  = sectionLightControl.get("pwmInversion", Boolean.class, false);
		    	lightControlSettingItem.pwmOffset     = sectionLightControl.get("pwmOffset", Integer.class, 0);
		    	lightControlSettingItem.pwmFullScale  = sectionLightControl.get("pwmFullScale", Integer.class, 0);
		    	lightControlSettingItem.ledId         = sectionLightControl.get("ledId", Integer.class, 0);
	    	}
	    	else {
	    		lightControlSettingItem.type = LightControlSettings.Type.NONE;
	    	}
	    	
	    	lightControlSettingsList.add(lightControlSettingItem);
	    	index++;
        }
        
        // push buttons. Search for sections [button1], [button2], ...
        index = 1;
        Ini.Section sectionButton;
        while((sectionButton=ini.get("button"+index))!=null) {
        	log.fine("found section light"+index);
        	ButtonSettings buttonSettingItem = new ButtonSettings();
        	
        	buttonSettingItem.id = index;
        	String buttonType = sectionButton.get("type", String.class, null);
	    	if(buttonType!=null) {
	    		if(buttonType.equalsIgnoreCase("gpio")) {
	    			buttonSettingItem.type = ButtonSettings.Type.GPIO;
	    		}
	    		else if(buttonType.equalsIgnoreCase("flic")) {
	    			buttonSettingItem.type = ButtonSettings.Type.FLIC;
	    		}
	    		else {
	    			log.severe("Unknown button type: "+buttonType+" for button"+index);
	    		}
	    	}
	    	else {
	    		log.severe("no button type specified for button"+index);
	    	}
	    	buttonSettingItem.bcmgpio              = sectionButton.get("bcmgpio", Integer.class, 0);
	    	buttonSettingItem.brightnessIncrement  = sectionButton.get("brightnessIncrement", Integer.class, 10);
			buttonSettingItem.soundId              = sectionButton.get("sound", Integer.class, 0)-1;
			buttonSettingItem.soundVolume          = sectionButton.get("soundVolume", Integer.class, 40);
			buttonSettingItem.soundTimer           = sectionButton.get("soundTimer", Integer.class, 30);
			buttonSettingItem.triggerSpeechControl = sectionButton.get("speechControl",Boolean.class,false);
			
			// light can be a comma-separated list of IDs
			buttonSettingItem.lightIds = new ArrayList<Integer>();
			String lightString = sectionButton.get("light", String.class, null);
			if(lightString!=null) {
				for(String id:lightString.split(",")) {
					try {
						buttonSettingItem.lightIds.add(Integer.parseInt(id));
					}
					catch(NumberFormatException e) {
						log.severe("parsing exception for light property of button"+index);
					}
				}
			}
			
			if(buttonSettingItem.type!=null) {
				buttonSettingsList.add(buttonSettingItem);
			}
			
			index++;
        }
        
        // weather
		Ini.Section sectionWeather = ini.get("weather");
		weatherLocation = sectionWeather.get("location", String.class, "");
        
        // mqtt
		Ini.Section sectionMqtt = ini.get("mqtt");
		if(sectionMqtt!=null) {
			mqttAddress     = sectionMqtt.get("address", String.class, null);
		    mqttPort        = sectionMqtt.get("port", Integer.class, 1883);
		    mqttKeepAlive   = sectionMqtt.get("keepalive", Integer.class, 60);
		    mqttTopicPrefix = sectionMqtt.get("topicPrefix", String.class, "alarmpi");
		}
        
		Ini.Section sectionSpeechControl = ini.get("speechcontrol");
		if(sectionSpeechControl!=null) {
			speechControlDevice = sectionSpeechControl.get("device",String.class,null);
			speechControlSound  = sectionSpeechControl.get("sound",Integer.class,null);
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
	static synchronized boolean read(String filename) {
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
	 * returns a boolean value from the ini file
	 * @param section          section name
	 * @param key              key name
	 * @param defaultValue     default value that will be returned if the value cannot be found
	 * @return                 value read from ini file or default if not found
	 */
	boolean getValue(String section,String key,boolean defaultValue) {
		Ini.Section iniSection= iniFile.get(section);
		
		if(iniSection!=null) {
			return iniSection.get(key,Boolean.class,defaultValue);
		}
		else {
			log.warning("Section "+section+" not found");
			return defaultValue;
		}
	}
	
	/**
	 * returns an integer value from the ini file
	 * @param section          section name
	 * @param key              key name
	 * @param defaultValue     default value that will be returned if the value cannot be found
	 * @return                 value read from ini file or default if not found
	 */
	int getValue(String section,String key,int defaultValue) {
		Ini.Section iniSection= iniFile.get(section);
		
		if(iniSection!=null) {
			return iniSection.get(key,Integer.class,defaultValue);
		}
		else {
			log.warning("Section "+section+" not found");
			return defaultValue;
		}
	}
	
	/**
	 * returns a double value from the ini file
	 * @param section          section name
	 * @param key              key name
	 * @param defaultValue     default value that will be returned if the value cannot be found
	 * @return                 value read from ini file or default if not found
	 */
	double getValue(String section,String key,double defaultValue) {
		Ini.Section iniSection= iniFile.get(section);
		
		if(iniSection!=null) {
			return iniSection.get(key,Double.class,defaultValue);
		}
		else {
			log.warning("Section "+section+" not found");
			return defaultValue;
		}
	}
	
	/**
	 * returns a string value from the ini file
	 * @param section          section name
	 * @param key              key name
	 * @param defaultValue     default value that will be returned if the value cannot be found
	 * @return                 value read from ini file or default if not found
	 */
	String getValue(String section,String key,String defaultValue) {
		Ini.Section iniSection= iniFile.get(section);
		
		if(iniSection!=null) {
			return iniSection.get(key,String.class,defaultValue);
		}
		else {
			log.warning("Section "+section+" not found");
			return defaultValue;
		}
	}
	
	/**
	 * @return list with all sounds defined in configuration file
	 */
	List<Alarm.Sound> getSoundList() {
        List<Alarm.Sound>soundList = new LinkedList<>(); 
        Ini.Section sectionSound;
        
        int index=1;
        while((sectionSound=iniFile.get("sound"+index)) != null) {
        	log.finest("found sound "+index);
        	
        	String soundType=sectionSound.get("type");
	    	boolean found = false;
	    	for(Alarm.Sound.Type type:Alarm.Sound.Type.values()) {
	    		if(soundType.equalsIgnoreCase(type.toString())) {
			    	String name   = sectionSound.get("name");
			    	String source = sectionSound.get("source");
			    	if(name==null ) {
			    		log.severe("Invalid sound with index "+index+" in config file: empty name");
			    	}
			    	if(source==null && type!=Alarm.Sound.Type.EXTERNAL) {
			    		log.severe("Invalid sound with index "+index+" in config file: empty source");
			    	}
			    	if(name!=null && source!=null) {
			    		Alarm.Sound sound = new Alarm.Sound();
			    		sound.name   = name;
			    		sound.source = source;
			    		sound.type   = type;
			    		log.fine("Found sound in config file: name="+name+" type="+type+" source="+source);
			    		
			    		soundList.add(sound);
			    	}
			    	found = true;
	    		}
	    	}
	    	if(!found) {
	    		log.severe("Unknown sound type: "+soundType);
	    	}
	    	
	    	index++;
        }
	    	
	    return soundList;
	}
	
	/**
	 * @return the sound list as Json array
	 */
	JsonArray getSoundListAsJsonArray() {
		JsonArrayBuilder builder = Json.createBuilderFactory(null).createArrayBuilder();
		getSoundList().stream().forEach(sound -> builder.add(sound.toJsonObject()));
		
		JsonArray jsonArray = builder.build();
		log.fine("create Json array from sound list: "+jsonArray.toString());
		
		return jsonArray;
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
	 * @return the network port for the AlarmPi TCP remote interface. null if no TCP server is configured
	 */
	Integer getPort() {
		return port;
	}

	/**
	 * @return the network port for the HTTP JSON server remote interface
	 */
	Integer getJsonServerPort() {
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
	 * @return light control settings as read-only object
	 */
	final List<LightControlSettings> getLightControlSettings() {
		return lightControlSettingsList;
	}

	/**
	 * @return the push button settings
	 */
	final List<ButtonSettings> getButtonSettings() {
		return buttonSettingsList;
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
	 * @return the MQTT topic prefix
	 */
	final String getMqttTopicPrefix() {
		return mqttTopicPrefix;
	}
	
	final String getSpeechControlDevice() {
		return speechControlDevice;
	}
	
	final Integer getSpeechControlSound() {
		return speechControlSound;
	}
	
	/**
	 * returns the summary name of the Google calendar to check or null
	 * @return summary name of the Google calendar to check or null
	 */
	final String getCalendarSummary() {
		return googleCalendarSummary;
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
		
		dump += "  lights\n";
		for(LightControlSettings lightControlSettings:lightControlSettingsList) {
			dump += "    id="+lightControlSettings.id+" type="+lightControlSettings.type+" name="+lightControlSettings.name+" deviceAddress=" + lightControlSettings.deviceAddress;
			dump += "    pwmInversion="+lightControlSettings.pwmInversion+" pwmOffset="+lightControlSettings.pwmOffset+" pwmFullScale="+lightControlSettings.pwmFullScale;
			dump += "    ledId: "+lightControlSettings.ledId+"\n";
		}
		
		dump += "  button settings:\n";
		for(ButtonSettings pushButtonSettings:buttonSettingsList) {
			dump += "    id="+pushButtonSettings.id+" speechControl="+pushButtonSettings.triggerSpeechControl+"\n";
			dump += "    sound: internal ID="+pushButtonSettings.soundId+" volume="+pushButtonSettings.soundVolume+" timer="+pushButtonSettings.soundTimer;
			dump += "    brightnessIncrement="+pushButtonSettings.brightnessIncrement+" light IDs: "+pushButtonSettings.lightIds+"\n";
		}
		
		if(mqttAddress==null || mqttPort==null) {
			dump += "  no MQTT Broker configured";
		}
		else {
			dump += "  MQTT broker: address="+mqttAddress+" port="+mqttPort+"\n";
			dump += "  MQTT topic prefix="+mqttTopicPrefix+"\n";
		}
		
		if(speechControlSound==null) {
			dump += "  no spechcontrol configured\n";
		}
		else {
			dump += "  speech control: device="+speechControlDevice+" sound="+speechControlSound+"\n";
		}
		dump += "  calendar:\n";
		dump += "    summary="+googleCalendarSummary+"\n";
		
		log.config(dump);
	}
	

	// private members
	private static final Logger   log    = Logger.getLogger( MethodHandles.lookup().lookupClass().getName() );
	private static Configuration  object = null;             // singleton object
	
	private Ini                              iniFile;
	
	// settings in configuration file
	private final boolean                    runningOnRaspberry;
	private String                           name;                      // AlarmPi Name
	private int                              volumeDefault;             // default sound volume
	private Integer                          port;                      // AlarmPi networt port for remote control
	private Integer                          jsonServerPort;            // tcp port for HTTP JSON based control
	private String                           mpdAddress;                // mpd network address
	private int                              mpdPort;                   // mpd network port
	private String                           mpdFiles;                  // directory for MPD sound files
	private String                           mpdTmpSubDir;              // subdirectory for MPD temporary sound files
	private String                           weatherLocation;           // Open Weather Map location for weather forecast
	private List<LightControlSettings>       lightControlSettingsList;  // list of light control settings
	private List<ButtonSettings>             buttonSettingsList;        // list of button settings
	private String                           googleCalendarSummary;     // summary name of google calendar (or null)
	private String                           mqttAddress;               // MQTT Broker address
	private Integer                          mqttPort;                  // MQTT broker port
	private Integer                          mqttKeepAlive;             // MQTT keepalive interval in seconds
	private String                           mqttTopicPrefix;           // MQTT topic prefix
	private String                           speechControlDevice;
	private Integer                          speechControlSound;
}
