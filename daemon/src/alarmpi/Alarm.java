package alarmpi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;

import alarmpi.Configuration.Sound;

/**
 * Class representing alarm settings
 */
public class Alarm implements Serializable {
	
	/**
	 * constructor
	 */
	Alarm() {
		id = nextId++;
	}
	
	Alarm(Alarm alarm) {
		id             = nextId++;
		enabled        = alarm.enabled;
		oneTimeOnly    = alarm.oneTimeOnly;
		skipOnce       = alarm.skipOnce;
		time           = alarm.time;
		weekDays       = alarm.weekDays;
		soundId        = alarm.soundId;
		
		greeting             = alarm.greeting;
		alarmSoundName            = alarm.alarmSoundName;
		fadeInDuration       = alarm.fadeInDuration;
		volumeFadeInStart    = alarm.volumeFadeInStart;
		volumeFadeInEnd      = alarm.volumeFadeInEnd;
		volumeAlarmEnd       = alarm.volumeAlarmEnd;
		lightDimUpDuration   = alarm.lightDimUpDuration;
		lightDimUpBrightness = alarm.lightDimUpBrightness;
		reminderInterval     = alarm.reminderInterval;
		duration             = alarm.duration;
	}
	
	/**
	 * reads all stored alarms from preferences
	 * @return list of all alarms
	 */
	static List<Alarm> readAll() {
		List<Alarm> alarmList = new LinkedList<Alarm>();
		
		try {
			int count=0;
			Preferences prefs = Preferences.userNodeForPackage(Alarm.class);
			for(String id:prefs.keys()) {
				byte bytes[] = prefs.getByteArray(id, null);
				if(bytes!=null) {
					ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
					Alarm alarm = (Alarm)ois.readObject();
					
					// set meaningful start values
					alarm.hasModifications   = false;
					alarm.transactionStarted = false;
					
					alarmList.add(alarm);
					
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
		
		return alarmList;
	}
	
	/**
	 * hack needed as long as I use sound index (ID) ins serialized object
	 */
	void setSoundObject() {
		if(soundId!=null) {
			sound = Configuration.getConfiguration().getSoundFromId(soundId);
			if(sound==null) {
				log.severe("unable to find sound object for ID "+soundId);
			}
		}
		else {
			sound = null;
		}
	}
	
	/**
	 * serializes the alarm and stores it in preferences to make it persistent
	 */
	private void store() {
		// serialize the alarm and store it in preferences (make it persistent)
		// using alarm ID as key
		try {
			log.fine("Storing alarm ID="+id);
			
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ObjectOutputStream oos;
			
			oos = new ObjectOutputStream( baos );
			oos.writeObject( this );
			oos.close();
			
			byte[] bytes = baos.toByteArray();
			Preferences prefs = Preferences.userNodeForPackage(Alarm.class);
			prefs.putByteArray(String.valueOf(id), bytes);
			prefs.flush();
			
			// publish modified alarm list on MQTT broker
			MqttClient.getMqttClient().publishAlarmList();
		} catch (IOException e) {
			log.severe("Failed to serialize alarm: "+e.getMessage());
		} catch (BackingStoreException e) {
			log.severe("Failed to store alarm in preferences: "+e.getMessage());
		}
		
		log.info("modified and stored alarm with ID="+id);
	}
	
	/**
	 * deletes this alarm from the list
	 */
	public void delete() {
		// remove from alarm list
		Configuration.getConfiguration().removeAlarmFromList(this);
		
		// remove from preferences
		Preferences prefs = Preferences.userNodeForPackage(Alarm.class);
		prefs.remove(String.valueOf(id));
		try {
			prefs.flush();
		} catch (BackingStoreException e) {
			log.severe("Failed to delete alarm from preferences");
			log.severe(e.getMessage());
		}
		
		Configuration.getConfiguration().addAlarmToProcess(this);
	}

	/**
	 * Creates a JsonObject representation of the alarm
	 * @return JsonObject representation of the alarm
	 */
	public JsonObject toJasonObject() {
		JsonObjectBuilder builder = Json.createBuilderFactory(null).createObjectBuilder();
		builder.add("id", id);
		builder.add("enabled", enabled);
		builder.add("oneTimeOnly", oneTimeOnly);
		builder.add("skipOnce", skipOnce);
		builder.add("time", time.toString());
		builder.add("weekDays",weekDays.toString());
		builder.add("soundName", sound.getName());
		
		JsonObject jsonObject = builder.build();
		log.fine("Created JsonObject for alarm: "+jsonObject.toString());
		
		return jsonObject;
	}
	
	public static void parseAllFromJsonObject(JsonObject jsonObject) {
		JsonArray jsonArray = jsonObject.getJsonArray("alarms");
		if(jsonArray!=null) {
			for(JsonValue jsonValue:jsonArray) {
				JsonObject alarm = jsonValue.asJsonObject();
				log.fine("parseAllFromJsonObject: Found alarm with id="+alarm.getInt("id"));
				
				Configuration.getConfiguration().getAlarm(alarm.getInt("id")).fromJsonObject(alarm);
			}
		}
		else {
			log.fine("parseAllFromJsonObject: JSON object has no array \"alarms\"");
		}
	}
	
	/**
	 * Updates the alarm object with the content of the JSON object
	 * @param jsonObject
	 */
	private void fromJsonObject(JsonObject jsonObject) {
		if(jsonObject.getInt("id")!=id) {
			log.severe("parseFromJsonObject: JSON id "+jsonObject.getInt("id")+" does not match alarm id "+id);
			return;
		}
		
		log.fine("parseFromJsonObject: parsing alarm with id "+id);
		enabled     = jsonObject.getBoolean("enabled");
		oneTimeOnly = jsonObject.getBoolean("oneTimeOnly");
		skipOnce    = jsonObject.getBoolean("skipOnce");
		time        = LocalTime.parse(jsonObject.getString("time"));
		
		// get Sound based on its name
		boolean found = false;
		int index     = 0;
		for(Sound sound:Configuration.getConfiguration().getSoundList()) {
			if(jsonObject.getString("soundName").equals(sound.getName())) {
				this.soundId = index;
				this.sound   = sound;
				found        = true;
				break;
			}
		}
		if(!found) {
			log.severe("Unable to find sound ");
			this.sound   = null;
			this.soundId = null;
		}
		
		weekDays.clear();
		String weekDaysString = jsonObject.getString("weekDays");
		for(DayOfWeek dayOfWeek:DayOfWeek.values()) {
			if(weekDaysString.contains(dayOfWeek.toString())) {
				weekDays.add(dayOfWeek);
			}
		}
		
		// store
		Configuration.getConfiguration().addAlarmToProcess(this);
		store();
	}
	
	/**
	 * if multiple modifications are made to an alarm by using any of the setter methods
	 * they should be grouped into a transaction to avoid unnecessary storage and event
	 * processing of the alarm
	 */
	public void startTransaction() {
		transactionStarted = true;
		hasModifications   = false;
	}
	
	/**
	 * ends a transaction started with startTransaction and triggers storage and event processing
	 * of this alarm
	 */
	public void endTransaction() {
		if(hasModifications) {
			store();
			Configuration.getConfiguration().addAlarmToProcess(this);
		}
		
		hasModifications   = false;
		transactionStarted = false;
	}
	
	// getter/setter methods
	
	public int getId() {
		return id;
	}
	
	public boolean isEnabled() {
		return enabled;
	}
	
	public void setEnabled(boolean enabled) {
		if(this.enabled != enabled) {
			log.fine("setting alarm ID "+id+" to enabled="+enabled);
			this.enabled = enabled;
			
			if(transactionStarted) {
				hasModifications = true;
			}
			else {
				store();
				Configuration.getConfiguration().addAlarmToProcess(this);
			}
		}
		else {
			log.fine("ignoring setting alarm ID "+id+" to enabled="+enabled+" - already done");
		}
	}

	public boolean isOneTimeOnly() {
		return oneTimeOnly;
	}
	
	public void setOneTimeOnly(boolean oneTimeOnly) {
		if(this.oneTimeOnly != oneTimeOnly) {
			this.oneTimeOnly = oneTimeOnly;
			
			if(transactionStarted) {
				hasModifications = true;
			}
			else {
				store();
				Configuration.getConfiguration().addAlarmToProcess(this);
			}
		}
	}
	
	public EnumSet<DayOfWeek> getWeekDays() {
		return weekDays;
	}
	
	public void setWeekDays(EnumSet<DayOfWeek> weekdays) {
		if(this.weekDays==null || this.weekDays.equals(weekdays) == false) {
			this.weekDays = weekdays;
			
			if(transactionStarted) {
				hasModifications = true;
			}
			else {
				store();
				Configuration.getConfiguration().addAlarmToProcess(this);
			}
		}
	}

	public boolean isSkipOnce() {
		return skipOnce;
	}
	
	public void setSkipOnce(boolean skipOnce) {
		if(this.skipOnce != skipOnce) {
			this.skipOnce = skipOnce;
			
			if(transactionStarted) {
				hasModifications = true;
			}
			else {
				store();
				Configuration.getConfiguration().addAlarmToProcess(this);
			}
		}
	}

	public LocalTime getTime() {
		return time;
	}
	
	public void setTime(LocalTime time) {
		if(this.time==null ||this.time.equals(time) == false) {
			this.time = time;
			
			if(transactionStarted) {
				hasModifications = true;
			}
			else {
				store();
				Configuration.getConfiguration().addAlarmToProcess(this);
			}
		}
	}

	public Integer getSoundId() {
		return soundId;
	}
	
	public Sound getSound() {
		return sound;
	}
	
	
	public void setSoundId(Integer soundId) {
		if(this.soundId==null || this.soundId.equals(soundId) == false) {
			this.soundId = soundId;
			if(soundId!=null) {
				this.sound = Configuration.getConfiguration().getSoundFromId(soundId);
				if(sound==null) {
					log.severe("Unable to find sound for ID "+soundId);
				}
			}
			else {
				this.sound = null;
			}
			
			if(transactionStarted) {
				hasModifications = true;
			}
			else {
				store();
				Configuration.getConfiguration().addAlarmToProcess(this);
			}
		}
	}

	public String getGreeting() {
		return greeting;
	}

	public void setGreeting(String greeting) {
		this.greeting = greeting;
	}

	public String getAlarmSoundName() {
		return alarmSoundName;
	}

	public void setAlarmSoundName(String sound) {
		this.alarmSoundName = sound;
	}

	public int getFadeInDuration() {
		return fadeInDuration;
	}

	public void setFadeInDuration(int fadeInDuration) {
		this.fadeInDuration = fadeInDuration;
	}

	public int getVolumeFadeInStart() {
		return volumeFadeInStart;
	}
	
	public void setVolumeFadeInStart(int volumeFadeInStart) {
		this.volumeFadeInStart = volumeFadeInStart;
	}

	public int getVolumeFadeInEnd() {
		return volumeFadeInEnd;
	}
	
	public void setVolumeFadeInEnd(int volumeFadeInEnd) {
		this.volumeFadeInEnd = volumeFadeInEnd;
	}

	public int getVolumeAlarmEnd() {
		return volumeAlarmEnd;
	}
	
	public void setVolumeAlarmEnd(int volumeAlarmEnd) {
		this.volumeAlarmEnd = volumeAlarmEnd;
	}

	public int getLightDimUpDuration() {
		return lightDimUpDuration;
	}
	
	public void setLightDimUpDuration(int lightDimUpDuration) {
		this.lightDimUpDuration = lightDimUpDuration;
	}

	public int getLightDimUpBrightness() {
		return lightDimUpBrightness;
	}
	
	public void setLightDimUpBrightness(int lightDimUpBrightness) {
		this.lightDimUpBrightness = lightDimUpBrightness;
	}

	public int getReminderInterval() {
		return reminderInterval;
	}
	
	public void setReminderInterval(int reminderInterval) {
		this.reminderInterval = reminderInterval;
	}

	public int getDuration() {
		return duration;
	}
	
	public void setDuration(int duration) {
		this.duration = duration;
	}



	// private members
	private static final Logger log              = Logger.getLogger( Alarm.class.getName() );
	private static final long   serialVersionUID = -2395516218365040408L;
	private static       int    nextId           = 0;   // ID of next alarm that is generated
	
	private int                id;                           // unique ID for this alarm
	private boolean            enabled          = false;     // o  n/off switch
	private boolean            oneTimeOnly      = false;     // automatically disable alarm again after it got executed once
	private boolean            skipOnce         = false;     // skip alarm one time
	private LocalTime          time;                         // alarm time
	private EnumSet<DayOfWeek> weekDays         = EnumSet.noneOf(DayOfWeek.class);  // weekdays when this alarm is active
	private Integer            soundId;                      // ID of sound to play. Must be configured in configuration file
	private transient Sound    sound;                        // sound to play. Currently maintained in addition to the above ID     
	
	private String             greeting;                     // greeting text
	private String             alarmSoundName;               // filename of alarm sound (or null)
	private int                fadeInDuration       = 0;     // fade in time in seconds
	private int                volumeFadeInStart    = 0;     // alarm sound fade-in start volume
	private int                volumeFadeInEnd      = 0;     // alarm sound fade-in end volume
	private int                volumeAlarmEnd       = 0;     // alarm sound end volume
	private int                lightDimUpDuration   = 0;     // duration of light dim up after alarm start in seconds
	private int                lightDimUpBrightness = 0;     // brightness of light at end of dim up phase in percent
	private int                reminderInterval     = 0;     // reminder interval (s)
	private int                duration             = 0;     // time until alarm stops (s)

	private boolean    transactionStarted   = false; // if true, a write transaction has been started
	private boolean    hasModifications     = false; // if true, this alarm has unsaved and unprocessed modifications
}
