package alarmpi;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;

import alarmpi.Alarm.Sound.Type;




/**
 * class representing alarm settings
 */
public class Alarm {
	
	
	// local class to model alarm sounds
	static class Sound {
		enum Type {
			STREAM,  // internet stream
			FILE,    // local file
			EXTERNAL // mdp is playing an external sound
		};
		
		String      name;             // name (unique identifier)
		Type        type;             // type of sound (stream, file)
		String      source;           // source for this sound, either stream URL or filename
		
		public JsonObject toJsonObject() {
			JsonObjectBuilder builder = Json.createBuilderFactory(null).createObjectBuilder();
			builder.add("name", name);
			builder.add("type", type.toString());
			
			JsonObject jsonObject = builder.build();
			log.fine("Created JsonObject for sound: "+jsonObject.toString());
			
			return jsonObject;
		}
	}

	/**
	 * default constructor, creates an "empty", disabled alarm
	 */
	Alarm() {
		log.fine("new alarm created, UUID="+id);
		
		log.fine("applying default settings from configuration file");
		greeting             = Configuration.getConfiguration().getValue("alarm", "greeting", "Hallo");
		fadeInDuration       = Configuration.getConfiguration().getValue("alarm", "fadeIn",   300);
		duration             = Configuration.getConfiguration().getValue("alarm", "duration", 300);
		reminderInterval     = Configuration.getConfiguration().getValue("alarm", "reminderInterval", 300);
		volumeFadeInEnd      = Configuration.getConfiguration().getValue("alarm", "volumeFadeInEnd", 100);
		volumeAlarmEnd       = Configuration.getConfiguration().getValue("alarm", "volumeAlarmEnd", 100);
		lightDimUpDuration   = Configuration.getConfiguration().getValue("alarm", "lightDimUpDuration", 300);
		lightDimUpBrightness = Configuration.getConfiguration().getValue("alarm", "lightDimUpBrightness", 100);
		signalSoundList.clear();
		
		// signal sounds (each alarm has a list of signal sounds)
		int signalSoundIndex = 1;
		String signalSoundName;
		signalSoundName = Configuration.getConfiguration().getValue("alarm", "signalSound"+signalSoundIndex, null);
		while(signalSoundName!=null) {
			if(signalSoundName!=null) {
				Sound sound = soundMap.get(signalSoundName);
				if(sound!=null) {
					if(sound.type!=Type.FILE) {
						log.severe("wrong type for signal sound "+signalSoundName);
					}
					else {
						log.fine("adding signal sound "+signalSoundIndex+" to alarm: "+signalSoundName);
						signalSoundList.add(sound);
					}
				}
				else {
					log.warning("alarm settings: signal sound "+signalSoundIndex+" refers to non existing sound "+signalSoundName);
				}
			}
			// try next
			signalSoundIndex++;
			signalSoundName = Configuration.getConfiguration().getValue("alarm", "signalSound"+signalSoundIndex, null);
		}
	}
	
	/**
	 * Creates a JsonObject representation of the alarm
	 * @return JsonObject representation of the alarm
	 */
	public JsonObject toJsonObject() {
		JsonObjectBuilder builder = Json.createBuilderFactory(null).createObjectBuilder();
		builder.add("id", id.toString());
		builder.add("enabled", enabled);
		builder.add("oneTimeOnly", oneTimeOnly);
		builder.add("skipOnce", skipOnce);
		builder.add("time", time.format(DateTimeFormatter.ofPattern("HH:mm")));
		builder.add("weekDays",weekDays.toString());
		
		if(alarmSound!=null) {
			builder.add("alarmSound", alarmSound.name);
		}
		
		JsonObject jsonObject = builder.build();
		log.finest("Created JsonObject for alarm: "+jsonObject.toString());
		
		return jsonObject;
	}
	
	/**
	 * parses this alarm object from a Json Object.
	 * @param jsonObject
	 */
	void fromJsonObject(JsonObject jsonObject) {
		log.finest("parsing Alarm from JsonObject: "+jsonObject.toString());
		
		try {
			JsonString jsonStringId =jsonObject.getJsonString("id");
			if(jsonStringId!=null) {
				id =UUID.fromString(jsonStringId.getString());
				log.fine("parsing Alarm from Json Object, id="+id);
			}
			else {
				log.severe("parsing Alarm from Json Object, but no ID present");
			}
		
			try {
				enabled     = jsonObject.getBoolean("enabled");
			}
			catch(NullPointerException e) {
				log.warning("parsing Alarm from Json Object: property \"enabled\" not present");
			}
			
			try {
				oneTimeOnly = jsonObject.getBoolean("oneTimeOnly");
			}
			catch(NullPointerException e) {
				log.warning("parsing Alarm from Json Object: property \"oneTimeOnly\" not present");
			}
			
			try {
				skipOnce    = jsonObject.getBoolean("skipOnce");
			}
			catch(NullPointerException e) {
				log.warning("parsing Alarm from Json Object: property \"skipOnce\" not present");
			}
			
			try {
				time        = LocalTime.parse(jsonObject.getString("time"));
			}
			catch(NullPointerException e) {
				log.warning("parsing Alarm from Json Object: property \"time\" not present");
			}
			
			try {
				weekDays.clear();
				String weekDaysString = jsonObject.getString("weekDays");
				for(DayOfWeek dayOfWeek:DayOfWeek.values()) {
					if(weekDaysString.contains(dayOfWeek.toString())) {
						weekDays.add(dayOfWeek);
					}
				}
			}
			catch(NullPointerException e) {
				log.warning("parsing Alarm from Json Object: property \"weekDays\" not present");
			}
			
			try {
				String alarmSoundName = jsonObject.getString("alarmSound");
				Sound sound = soundMap.get(alarmSoundName);
				if(sound!=null) {
					if(sound.type!=Sound.Type.STREAM) {
						log.severe("parsing Alarm from Json Object: alarmSound \""+alarmSoundName+"\" has wrong type");
						sound = null;
					}
				}
				if(sound==null) {
					log.severe("parsing Alarm from Json Object: sound "+alarmSoundName+" not found");
				}
				alarmSound = sound;
			}
			catch(NullPointerException e) {
				log.warning("parsing Alarm from Json Object: property \"alarmSound\" not present");
			}
		}
		catch(ClassCastException e) {
			log.severe("parsing Alarm from Json Object: ClassCastException: "+e.getMessage());
		}
	}
	
	/**
	 * @return alarm UUID
	 */
	UUID getId() {
		return id;
	}
	
	/**
	 * @return boolean if the alarm is enabled
	 */
	boolean getEnabled() {
		return enabled;
	}
	
	/**
	 * sets the enabled flag of the alarm
	 * @param enabled
	 */
	void setEnabled(boolean enabled) {
		this.enabled  = enabled;
		this.modified = true;
		
		storeAlarmList();
	}
	
	/**
	 * @return boolean if the alarm gest implicitly disabled again after being triggered
	 */
	boolean getOneTimeOnly() {
		return oneTimeOnly;
	}
	
	/**
	 * sets the oneTimeOnly flag of ths alarm
	 * @param oneTimeOnly
	 */
	void setOneTimeOnly(boolean oneTimeOnly) {
		this.oneTimeOnly = oneTimeOnly;
		this.modified    = true;
		
		storeAlarmList();
	}
	
	/**
	 * @return boolean if the alarm gets skipped on time
	 */
	boolean getSkipOnce() {
		return skipOnce;
	}
	
	/**
	 * sets the skipOnce flag of the alarm
	 * @param skipOnce
	 */
	void setSkipOnce(boolean skipOnce) {
		this.skipOnce = skipOnce;
		this.modified = true;
		
		storeAlarmList();
	}
	
	/**
	 * @return alarm time
	 */
	LocalTime getTime() {
		return time;
	}
	
	/**
	 * sets the alarm time
	 * @param time
	 */
	void setTime(LocalTime time) {
		this.time     = time;
		this.modified = true;
		
		storeAlarmList();
	}
	
	/**
	 * @return weekdays at which this alarm is active
	 */
	EnumSet<DayOfWeek> getWeekDays() {
		return weekDays;
	}
	
	/**
	 * sets the weekdays for ths alarm
	 * @param weekdays
	 */
	void setWeekDays(EnumSet<DayOfWeek> weekdays) {
		this.weekDays = weekdays;
		this.modified = true;
		
		storeAlarmList();
	}
	
	/**
	 * 
	 * @return the alarm sound of this alarm or null if none is set
	 */
	Alarm.Sound getAlarmSound() {
		return alarmSound;
	}
	
	void setAlarmSound(Alarm.Sound alarmSound) {
		this.alarmSound = alarmSound;
		if(alarmSound!=null && alarmSound.type!=Alarm.Sound.Type.STREAM) {
			log.severe("Alarm.setAlarmSound: specified sound is not of type STREAM");
			this.alarmSound = null;
		}
	}
	
	/**
	 * @return the greeting text
	 */
	String getGreeting() {
		return greeting;
	}
	
	/**
	 * @return fade in duration in seconds
	 */
	int getFadeInDuration() {
		return fadeInDuration;
	}
	
	/**
	 * @return alarm duration in seconds
	 */
	int getDuration() {
		return duration;
	}
	
	/**
	 * @return alarm signal (reminder) interval in seconds
	 */
	int getReminderInterval() {
		return reminderInterval;
	}
	
	/**
	 * @return volume at the start of the fade in period in percent
	 */
	int getVolumeFadeInStart() {
		return volumeFadeInStart;
	}
	/**
	 * @return volume at the end of the fade period in percent
	 */
	int getVolumeFadeInEnd() {
		return volumeFadeInEnd;
	}
	
	/**
	 * @return volume at the end of the alarm in percent
	 */
	int getVolumeAlarmEnd() {
		return volumeAlarmEnd;
	}
	
	/**
	 * @return light dim up duration in seconds
	 */
	int getLightDimUpDuration() {
		return lightDimUpDuration;
	}
	
	/**
	 * @return light dim up brightness in percent
	 */
	int getLightDimUpBrightness() {
		return lightDimUpBrightness;
	}
	
	/**
	 * @return list of signal sounds
	 */
	List<Alarm.Sound> getSignalSoundList() {
		return signalSoundList;
	}
	
	
	/**
	 * applies the settings of the given alarm to the alarm in the list with the same ID
	 * @param newAlarmSettings new alarm settings to apply
	 */
	static void updateAlarmFromJsonObject(final JsonObject newAlarmSettings) {
		final Alarm newAlarm = new Alarm();
		newAlarm.fromJsonObject(newAlarmSettings);
		
		UnaryOperator<Alarm> copySettings = alarm -> {
			if(alarm.id.equals(newAlarm.id)) {
				newAlarm.modified = true;
				return newAlarm;
			}
			else {
				return alarm;
			}
		};
		
		alarmList.replaceAll(copySettings);
		
		storeAlarmList();
	}
	
	/**
	 * @return the alarm list
	 */
	static List<Alarm> getAlarmList() {
		return alarmList;
	}
	
	/**
	 * return a list of all modified alarms and resets the modified flag for those alarms again
	 * @return the list of modified alarms
	 */
	static List<Alarm> getModifiedAlarmList() {
		return alarmList.stream()
				.filter(alarm -> alarm.modified==true)
				.peek(alarm -> alarm.modified=false)
				.collect(Collectors.toList());
		
		
	}
	
	
	/**
	 * @return the alarm list as Json array
	 */
	static JsonArray getAlarmListAsJsonArray() {
		JsonArrayBuilder builder = Json.createBuilderFactory(null).createArrayBuilder();
		alarmList.stream().forEach(alarm -> builder.add(alarm.toJsonObject()));
		
		JsonArray jsonArray = builder.build();
		log.finest("create Json array from alarm list: "+jsonArray.toString());
		
		return jsonArray;
	}
	
	/**
	 * sets the alarm list from a Json array. All old content in the alarm list will be deleted and overwritten
	 * @param jsonArray
	 */
	static void setAlarmListFromJsonArray(JsonArray jsonArray) {
		alarmList.clear();
		jsonArray.stream().forEach(jsonValue -> {
			try {
				Alarm alarm = new Alarm();
				alarm.fromJsonObject(jsonValue.asJsonObject());
				alarm.modified = true;
				alarmList.add(alarm);
			}
			catch(ClassCastException e) {
				log.severe("ClassCastException when parsing JsonArray in setAlarmListFromJsonArray");
			}
		});
	}
	
	/**
	 * updates the alarm list with the content of the Json array
	 * @param jsonArray
	 */
	static void updateAlarmListFromJsonArray(JsonArray jsonArray) {
		jsonArray.stream().forEach(jsonValue -> {
			try {
				updateAlarmFromJsonObject(jsonValue.asJsonObject());
			}
			catch(ClassCastException e) {
				log.severe("ClassCastException when parsing JsonArray in setAlarmListFromJsonArray");
			}
		});
	}
	
	/**
	 * Overwrites the default location to store the alarm list
	 * @param storageDirectory
	 */
	static void setStorageDirectory(String storageDirectory) {
		storagePath = Paths.get(storageDirectory, storageFile);
	}
	
	/**
	 * stores the alarm list as Json object to a file
	 * @param path filename
	 */
	private static void storeAlarmList() {
		log.fine("storing alarm list to file "+storagePath);
		
		JsonArray jsonArray = getAlarmListAsJsonArray();
		log.fine("storing alarm list as Json array: "+jsonArray.toString());
		
		// publish modified alarm status on MQTT broker
		JsonObjectBuilder builder = Json.createBuilderFactory(null).createObjectBuilder();
		builder.add("name", Configuration.getConfiguration().getName())
		       .add("alarms", Alarm.getAlarmListAsJsonArray());
		MqttClient mqttClient = MqttClient.getMqttClient();
		if(mqttClient!=null) {
			mqttClient.publish(MQTT_TOPIC_ALARMLIST, builder.build().toString());
		}
		
		try {
			FileWriter writer = new FileWriter(storagePath.toFile());
			writer.write(jsonArray.toString());
			writer.flush();
			writer.close();
		} catch (IOException e) {
			log.severe("Unable to store alarm list as file: "+e.getMessage());
		}
	}
	
	/**
	 * reads the alarm list in Json format from file
	 */
	static void restoreAlarmList() {
		log.info("restoring alarm list from file "+storagePath);
		alarmList.clear();
		
		// read sound list from configuration file
		soundMap.clear();
		Configuration.getConfiguration().getSoundList().stream().forEach(sound -> soundMap.put(sound.name, sound));
		
		
		try {
			FileReader reader = new FileReader(storagePath.toFile());
			JsonArray jsonArray = Json.createReaderFactory(null).createReader(reader).readArray();
			setAlarmListFromJsonArray(jsonArray);
			
			alarmList.stream().forEach(alarm -> alarm.modified=true);
		} catch (IOException e) {
			log.severe("Unable to restore alarm list from file: "+e.getMessage());
			
			// create default alarms
			log.info("Creating default alarms");
			for(int i=0 ; i<defaultAlarmCount ; i++) {
				Alarm alarm = new Alarm();
				alarm.setAlarmSound(Configuration.getConfiguration().getSoundList().get(0));
				alarmList.add(alarm);
			}
		}
		
		// publish modified alarm status on MQTT broker
		JsonObjectBuilder builder = Json.createBuilderFactory(null).createObjectBuilder();
		builder.add("name", Configuration.getConfiguration().getName())
		       .add("alarms", Alarm.getAlarmListAsJsonArray());
		MqttClient mqttClient = MqttClient.getMqttClient();
		if(mqttClient!=null) {
			mqttClient.publish(MQTT_TOPIC_ALARMLIST, builder.build().toString());
		}
	}
	
	/**
	 * @return the next active alarm for today or null
	 */
	static Alarm getNextAlarmToday() {
		return alarmList.stream()
			.filter(alarm -> alarm.getEnabled()==true && alarm.getSkipOnce()==false && alarm.getWeekDays().contains(LocalDate.now().getDayOfWeek()))
			.filter(alarm -> alarm.getTime().isAfter(LocalTime.now()))
			.min( (alarm1,alarm2) -> alarm1.time.compareTo(alarm2.time))
			.orElse(null);
	}
	
	/**
	 * @return the next active alarm for tomorrow or null
	 */
	static Alarm getNextAlarmTomorrow() {
		return alarmList.stream()
			.filter(alarm -> alarm.getEnabled()==true && alarm.getSkipOnce()==false && alarm.getWeekDays().contains(LocalDate.now().getDayOfWeek().plus(1)))
			.min( (alarm1,alarm2) -> alarm1.time.compareTo(alarm2.time))
			.orElse(null);
	}
	
	/**
	 * skips all alarms tomorrow
	 */
	static void skipAllAlarmsTomorrow() {
		alarmList.stream()
		.filter(alarm -> alarm.getEnabled()==true && alarm.getSkipOnce()==false && alarm.getWeekDays().contains(LocalDate.now().getDayOfWeek().plus(1)))
		.forEach(alarm -> alarm.setSkipOnce(true));
		
		storeAlarmList();
	}
	
	/**
	 * set a single alarm for the next day
	 */
	static void setAlarmTomorrow(LocalTime time) {
		skipAllAlarmsTomorrow();
		Alarm alarm = alarmList.get(alarmList.size()-1);
		
		alarm.enabled     = true;
		alarm.skipOnce    = false;
		alarm.oneTimeOnly = true;
		alarm.time        = time;
		alarm.weekDays.clear();
		alarm.weekDays.add(DayOfWeek.from(LocalDate.now().getDayOfWeek().plus(1)));
		
		storeAlarmList();
	}
	
	
	
	
	// private members
	
	private static final int    defaultAlarmCount = 4;                   // default number of alarms
	private static final String storageDirectory  = "/var/lib/alarmpi";  // default directory to store alarm list
	private static final String storageFile       = "alarmlist.json";    // filename for alarm list
	
	private static       Path   storagePath       = Paths.get(storageDirectory, storageFile);
	
	private static final Logger log               = Logger.getLogger( Alarm.class.getName() );
		
	private UUID                id                = UUID.randomUUID();   // unique ID for this alarm
	private boolean             enabled           = false;               // on/off switch for the alarm
	private boolean             oneTimeOnly       = false;               // automatically disable alarm again after it got triggered once
	private boolean             skipOnce          = false;               // skip alarm one time
	private LocalTime           time              = LocalTime.MIDNIGHT;  // alarm time
	private EnumSet<DayOfWeek>  weekDays          = EnumSet.noneOf(DayOfWeek.class); // weekdays when this alarm is active
	private Sound               alarmSound        = null;                // the sound which is played continuously (must be of type STREAM)
	private List<Sound>         signalSoundList   = new LinkedList<>();  // list of signal sounds
	
	// the following properties are for now defined by the configuration file and have no setters
	private String              greeting             = "";               // greeting text
	private int                 fadeInDuration       = 300;              // fade in duration in seconds
	private int                 duration             = 1800;             // alarm duration in seconds
	private int                 reminderInterval     = 300;              // alarm signal interval in seconds
	private int                 volumeFadeInStart    = 0;                // volume at start of fade in period in percent
	private int                 volumeFadeInEnd      = 100;              // volume at end of fade in period in percent
	private int                 volumeAlarmEnd       = 100;              // volume at the end of the alarm in percent
	private int                 lightDimUpDuration   = 300;              // duration of light dim up after alarm start in seconds
	private int                 lightDimUpBrightness = 100;              // brightness of light at end of dim up phase in percent
	
	
	// list with all alarms
	private static List<Alarm> alarmList          = new LinkedList<>();
	
	// map with all sounds (from configuration file)
	private static Map<String,Sound> soundMap     = new HashMap<>();
	
	//
	private boolean             modified         = false;               // flag to indicate if alarm got modified and changes need to be processed
	
	final static String MQTT_TOPIC_ALARMLIST   = "alarmlist";   // published topic, contains alarm list in JSON format
}

