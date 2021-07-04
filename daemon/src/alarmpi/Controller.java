package alarmpi;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoField;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalInput;
import com.pi4j.io.gpio.PinPullResistance;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;
import com.pi4j.io.gpio.event.GpioPinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;

import alarmpi.Alarm.Sound.Type;

/**
 * This class implements the main endless control loop that gets started
 * out of main in its own thread
 */
class Controller implements Runnable {

	/**
	 * Constructor
	 */
	public Controller() {
		configuration       = Configuration.getConfiguration();
		eventList           = new LinkedList<Event>();
		dataExecutorService = Executors.newSingleThreadExecutor();
		
		// initialize pi4j objects for GPIO handling
		final GpioController gpioController;
		if(configuration.getRunningOnRaspberry()) {
			log.info("running on Raspberry - initializing WiringPi");
			
			// initialize wiringPi library
			// I get a crash when calling this - something changed in the new rev of pi4j
			// com.pi4j.wiringpi.Gpio.wiringPiSetup();
			gpioController       = GpioFactory.getInstance();
			log.info("running on Raspberry - initializing WiringPi done.");
		}
		else {
			gpioController = null;
		}
			
		// instantiate light control
		log.info("initializing light control");
		Configuration.getConfiguration().getLightControlSettings().stream().forEach(setting -> {
			switch(setting.type) {
				case RASPBERRY:
					log.config("creating light control for Raspberry PWM");
					lightControlList.add(new LightControlRaspiPwm(setting));
					break;
				case PCA9685:
					log.config("creating light control for PCA9685");
					lightControlList.add(new LightControlPCA9685(setting));
					break;
				case NRF24LO1:
					log.config("creating light control for nRF24LO1 remote control");
					lightControlList.add(new LightControlNRF24LO1(setting.id,setting.name));
					break;
				case MQTT:
					log.config("creating light control for MQTT controlled light");
					lightControlList.add(new LightControlMqtt(setting.id,setting.name));
					break;
				default:
					log.warning("unknown or NONE light control specified");
			}
		});
		
		log.info("initializing of light control done. Switching all off now");
		lightControlList.stream().forEach(control -> control.setOff());
		
		// configure input key pins as input pins
		// default: key1=GPIO06 (BRCM GPIO 25)
		//          key2=GPIO05 (BRCM GPIO 24)
		log.info("running on Raspberry - initializing  buttons");
		Configuration.getConfiguration().getButtonSettings().stream().forEach(button -> {
			switch(button.type) {
				case GPIO :
					if(gpioController!=null) {
						try {
							GpioPinDigitalInput input = null;
							
							// https://github.com/raspberrypi/linux/issues/553
							// allow one retry
							try {
								input = gpioController.provisionDigitalInputPin(RaspiPin.getPinByAddress(button.wiringpigpio), PinPullResistance.PULL_UP);
								input.addListener(new PushButtonListener(button,input,this));
							}
							catch(Throwable e) {
								Thread.sleep(100);
								try {
									if(input==null) {
										input = gpioController.provisionDigitalInputPin(RaspiPin.getPinByAddress(button.wiringpigpio), PinPullResistance.PULL_UP);
									}
									input.addListener(new PushButtonListener(button,input,this));
								}
								catch(Throwable e2) {
									Thread.sleep(100);
									if(input==null) {
										input = gpioController.provisionDigitalInputPin(RaspiPin.getPinByAddress(button.wiringpigpio), PinPullResistance.PULL_UP);
									}
									input.addListener(new PushButtonListener(button,input,this));
								}
							}
						}
						catch(Throwable e) {
							log.severe("Uncaught runtime exception during initialization of button control, final attempts: "+e.getMessage());
							log.severe(e.getCause().toString());
							for(StackTraceElement element:e.getStackTrace()) {
				    			log.severe(element.toString());
							}
						}
					}
					break;
				default:
					log.severe("Unknown button type: "+button.type);
			}
		});
		

		log.info("initializing push buttons done");

		log.info("initializing sound control");
		soundControl = SoundControl.getSoundControl();
		log.info("initializing sound control done");
		
		log.info("initializing MQTT client");
		mqttClient = MqttClient.getMqttClient();
		log.info("initializing MQTT client done");
		
		// update mpd with tmp files for next alarm announcement
		new TextToSpeech().createTempFile("dummy", "nextAlarmToday.mp3");
		new TextToSpeech().createTempFile("dummy", "nextAlarmTomorrow.mp3");
		soundControl.update();
		
		mqttSendAliveTopic    = Configuration.getConfiguration().getValue("mqtt", "sendAliveTopic", null);
		mqttSendAliveInterval = Configuration.getConfiguration().getValue("mqtt", "sendAliveInterval", 30);
		if(mqttSendAliveTopic!=null) {
			log.config(String.format("Publishing MQTT alive messages to topic %s, interval=%d minutes",mqttSendAliveTopic,mqttSendAliveInterval));
		}
		else {
			log.config("No MQTT alive messages configured");
		}
		
		log.info("initialization done");
	}
	
	/**
	 * switches everything off
	 */
	void allOff(boolean announceNextAlarm) {
		stopActiveAlarm();
		lightControlList.stream().forEach(control -> control.setOff());
		soundControl.stop();
		
		if(announceNextAlarm) {
			// announce next alarms before switching off
			Alarm alarm = Alarm.getNextAlarmToday();
			if(alarm!=null) {
				String text = "Der nächste Alarm ist heute um "+alarm.getTime().getHour()+" Uhr";
				if(alarm.getTime().getMinute()!=0) {
					text += alarm.getTime().getMinute();
				}
				String filename = new TextToSpeech().createTempFile(text, "nextAlarmToday.mp3");
				soundControl.on();
				soundControl.setVolume(Configuration.getConfiguration().getDefaultVolume());
				soundControl.playFile(filename, null, false);
			}
			else {
				alarm = Alarm.getNextAlarmTomorrow();
				if(alarm!=null) {
					String text = "Der n�chste Alarm ist morgen um "+alarm.getTime().getHour()+" Uhr";
					if(alarm.getTime().getMinute()!=0) {
						text += alarm.getTime().getMinute();
					}
					String filename = new TextToSpeech().createTempFile(text, "nextAlarmTomorrow.mp3");
					soundControl.on();
					soundControl.setVolume(Configuration.getConfiguration().getDefaultVolume());
					soundControl.playFile(filename, null, false);
				}
			}
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
			}
			
			soundControl.stop();
		}
		
		soundControl.off();
	}

	
	@Override
	public void run() {
		
		final int    sleepPeriod        = 1000;   // thread sleep period: 1s
		final int    watchDogCounterMax = 60;     // update watchdog file every 60*sleepPeriod
		
		log.info("controller daemon thread started");

		LocalDate date = LocalDate.now().minusDays(1);
		LocalTime time = LocalTime.now();
		int watchDogCounter = watchDogCounterMax;
		
		// load alarm list
		Alarm.restoreAlarmList();
		
		// start endless loop
		while (!Thread.interrupted()) {
			try {
				watchDogCounter++;
				if(watchDogCounter >= watchDogCounterMax) {
					watchDogCounter = 0;
					
					// touch watchdog file
					if(configuration.getRunningOnRaspberry()) {
						try {
							FileWriter writer = new FileWriter(watchDogFile);
							writer.write(LocalTime.now().toString());
							writer.close();
						} catch (IOException e) {
							log.severe("Unable to update watchdog file: "+e.getMessage());
						}
					}
					
					// send sign of life to MQTT broker
					if(mqttSendAliveTopic!=null && LocalTime.now().minusMinutes(mqttSendAliveInterval).isAfter(time)) {
						log.fine("publishing sign of life to MQTT");;
						
						MqttClient.getMqttClient().publish(mqttSendAliveTopic, LocalDateTime.now().toString());
						time = LocalTime.now();
					}
				}
				
				// check for a new day
				if(!date.equals(LocalDate.now())) {
					date = LocalDate.now();
					log.fine("New day detected, adding alarms for "+date);
					
					// create all the events for the alarms of today
					deleteAlarmEvents();
					Alarm.getAlarmList().stream().forEach(alarm -> addAlarmEvents(alarm));
					
					// publish modified alarm status on MQTT broker
					MqttClient.getMqttClient().publishAlarmList();
				}
				
				// check if an alarm was modified and the events need to be processed
				Alarm.getModifiedAlarmList().stream().forEach(alarm -> updateAlarmEvents(alarm));
				
				// check if an event needs to be processed
				checkForEventsToProcess();
				
				// sleep
				TimeUnit.MILLISECONDS.sleep(sleepPeriod);
			} catch (InterruptedException e) {
				// this indicates that SIGTERM signal has been received
				log.warning("controller loop interrupted");
			}
			catch( Exception e) {
				log.severe("runtime exception in controller thread: "+e.getMessage());
			}
		}
		
		log.info("controller thread terminating");
	}
	
	
	/**
	 * checks if there are events to be processed and fires them
	 */
	synchronized void checkForEventsToProcess() {
		Event   e     = null;
		boolean fired = false;
		do {
			fired=false;
			try {
				e = eventList.getFirst();
				if(LocalTime.now().isAfter(e.time)) {
					// time to fire event
					fireEvent(e);
					eventList.removeFirst();
					fired = true;
				}
			}
			catch(NoSuchElementException exception) {}
			
		} while(e!=null && fired);
	}
	
	/**
	 * stops the active alarm
	 */
	synchronized void stopActiveAlarm() {
		if(activeAlarm!=null) {
			log.fine("stopping active alarm");
			
			// if this alarm is one time only: disable it again
			if(activeAlarm.getOneTimeOnly()) {
				log.fine("oneTimeOnly alarm. Disabling again");
				activeAlarm.setEnabled(false);
			}
			else {
				log.fine("oneTimeOnly=false. Leaving alarm enabled.");
			}
			
			lightControlList.stream().forEach(control -> control.setOff());
			soundControl.off();
			deleteAlarmEvents(activeAlarm);
			
			activeAlarm = null;
		}
		else {
			log.fine("stopActiveAlarm: no alarm active");
		}
	}

	/**
	 * deletes all alarm events
	 */
	synchronized private void deleteAlarmEvents() {
		log.fine("deleting all alarm events");
		eventList.clear();
		
		lightControlList.stream().forEach(control -> control.setOff());
		soundControl.off();
		activeAlarm = null;
	}
	
	/**
	 * updates (re-creates) all events for the specified alarm
	 * @param alarm Alarm for which all events shall be updated
	 */
	synchronized private void updateAlarmEvents(Alarm alarm) {
		deleteAlarmEvents(alarm);
		addAlarmEvents(alarm);
	}
	
	/**
	 * deletes all events corresponding to this alarm
	 * @param alarm  alarm 
	 */
	synchronized private void deleteAlarmEvents(Alarm alarm) {
		log.fine("deleting alarm events for alarm ID="+alarm.getId());
		Iterator<Event> it = eventList.iterator();
		while(it.hasNext()) {
			Event e = it.next();
			if(e.alarm != null && e.alarm == alarm) {
				// delete this event
				it.remove();
			}
		}
		
		if(activeAlarm!=null && alarm==activeAlarm) {
			lightControlList.stream().forEach(control -> control.setOff());
			soundControl.off();
			activeAlarm = null;
		}
	}
	
	
	/**
	 * creates alarm events
	 * @param alarm Alarm for which events must be created
	 */
	private void generateAlarmEvents(Alarm alarm) {
		// alarm time as LocalDateTime
		LocalTime alarmTime = alarm.getTime();
		
		// generate fade-in events
		final int stepCountFadeIn         = 20;
		final LocalTime fadeInStart       = alarmTime.minusSeconds(alarm.getFadeInDuration());
		final double fadeInTimeInterval   = (double)alarm.getFadeInDuration()/(double)stepCountFadeIn;                 // in seconds
		final double fadeInVolumeInterval = (double)(alarm.getVolumeFadeInEnd()-alarm.getVolumeFadeInStart())/(double)stepCountFadeIn;
		
		log.fine("fade in start at: "+fadeInStart+" alarm at "+alarmTime+" stop at "+alarmTime.plusSeconds(alarm.getDuration()));
		
		// create events only if alarm is still to come today
		if(fadeInStart.isAfter(LocalTime.now())) {
			Event eventStart = new Event();
			eventStart.type      = Event.EventType.ALARM_START;
			eventStart.alarm     = alarm;
			eventStart.time      = fadeInStart;
			eventList.add(eventStart);
			
			if(alarm.getAlarmSound()!=null) {
				Event eventSound = new Event();
				eventSound.type         = Event.EventType.PLAY_SOUND;
				eventSound.alarm        = alarm;
				eventSound.time         = fadeInStart.plusNanos(1);
				eventSound.sound        = alarm.getAlarmSound();
				eventSound.volume       = alarm.getVolumeFadeInStart();
				eventList.add(eventSound);
			}
			
			for(int step=1 ; step<stepCountFadeIn ; step++) {
				// increase volume
				Event eventVolume = new Event();
				eventVolume.type      = Event.EventType.SET_VOLUME;
				eventVolume.alarm     = alarm;
				eventVolume.time      = fadeInStart.plusSeconds((long)(step*fadeInTimeInterval));
				eventVolume.volume    = alarm.getVolumeFadeInStart()+(int)(step*fadeInVolumeInterval);
				eventList.add(eventVolume);
			}
		}
		
		// generate post fade-in events to increase volume and brightness
		final int    stepCountPostFadeIn      = 5;
		final double postFadeInTimeInterval   = (double)alarm.getDuration()/(double)stepCountPostFadeIn;    // in seconds
		final double postFadeInVolumeInterval = (double)(alarm.getVolumeAlarmEnd()-alarm.getVolumeFadeInEnd())/(double)stepCountPostFadeIn;
		
		for(int step=1 ; step<stepCountPostFadeIn ; step++) {
			// increase volume
			Event eventVolume = new Event();
			eventVolume.type      = Event.EventType.SET_VOLUME;
			eventVolume.alarm     = alarm;
			eventVolume.time      = alarmTime.plusSeconds((long)(step*postFadeInTimeInterval));
			eventVolume.volume    = alarm.getVolumeFadeInEnd()+(int)(step*postFadeInVolumeInterval);
			eventList.add(eventVolume);
		}
		
		// generate main alarm and post alarm announcements
		Event eventGreeting = new Event();
		Alarm.Sound sound   = new Alarm.Sound();
		sound.name                 = "greeting";
		sound.type                 = Type.FILE;
		sound.source               = new TextToSpeech().createPermanentFile(alarm.getGreeting());
		eventGreeting.type         = Event.EventType.PLAY_SOUND;
		eventGreeting.alarm        = alarm;
		eventGreeting.sound        = sound;
		eventGreeting.time         = alarmTime.plusNanos(1);
		eventGreeting.interrupt    = true;
		eventList.add(eventGreeting);
		
		if(alarmTime.isAfter(LocalTime.now())) {
			int count = 0;
			int ANNOUNCEMENT_INTERVAL = 3;

			Iterator<Alarm.Sound> it = alarm.getSignalSoundList().iterator();
			Alarm.Sound signalSound = null;
			for(LocalTime time=alarmTime ;  time.isBefore(alarmTime.plusSeconds(alarm.getDuration())) ; time=time.plusSeconds(alarm.getReminderInterval())) {
				boolean interrupt = true;
				
				if(it.hasNext()) {
					signalSound = it.next();
				}
				if(signalSound!=null) {
					Event eventAlarm = new Event();
					eventAlarm.type         = Event.EventType.PLAY_SOUND;
					eventAlarm.alarm        = alarm;
					eventAlarm.time         = time;
					eventAlarm.sound        = signalSound;
					eventAlarm.interrupt    = interrupt;
					eventList.add(eventAlarm);
					
					interrupt = false;
				}
				
				Event eventAnnouncement = new Event();
				sound                   = new Alarm.Sound();
				sound.name                 = "time announcement";
				sound.type                 = Type.FILE;
				sound.source               = prepareTimeAnnouncement(time);
				eventAnnouncement.type         = Event.EventType.PLAY_SOUND;
				eventAnnouncement.alarm        = alarm;
				eventAnnouncement.time         = time.plusNanos(2);
				eventAnnouncement.sound        = sound;
				eventAnnouncement.interrupt    = interrupt;
				eventList.add(eventAnnouncement);
				
				if(count%ANNOUNCEMENT_INTERVAL == 0) {
					// play additional announcements
					Event eventWeather = new Event();
					eventWeather.type         = Event.EventType.PLAY_WEATHER;
					eventWeather.alarm        = alarm;
					eventWeather.time         = time.plusNanos(3);
					eventList.add(eventWeather);
					
					if(Configuration.getConfiguration().getCalendarSummary()!=null) {
						Event eventCalendar = new Event();
						eventCalendar.type         = Event.EventType.PLAY_CALENDAR;
						eventCalendar.alarm        = alarm;
						eventCalendar.time         = time.plusNanos(4);
						eventList.add(eventCalendar);
					}
				}
				
				if(alarm.getAlarmSound()!=null) {
					Event eventPlay = new Event();
					eventPlay.type         = Event.EventType.PLAY_SOUND;
					eventPlay.alarm        = alarm;
					eventPlay.time         = time.plusNanos(5);
					eventPlay.sound        = alarm.getAlarmSound();
					eventPlay.interrupt    = false;
					eventList.add(eventPlay);
				}
				
				count++;
			}
			
			Event eventStop = new Event();
			eventStop.type      = Event.EventType.ALARM_END;
			eventStop.alarm     = alarm;
			eventStop.time      = alarmTime.plusSeconds(alarm.getDuration());
			eventList.add(eventStop);
		}		
	}
	
	/**
	 * generates the events needed to process an alarm
	 * @param alarm new alarm to process
	 */
	synchronized private void addAlarmEvents(Alarm alarm) {
		log.fine("generating events for alarm ID="+alarm.getId()+" at time="+alarm.getTime());
		
		if(!alarm.getWeekDays().contains(LocalDate.now().getDayOfWeek())) {
			log.fine("alarm not scheduled for today");
			return;
		}
		
		if(alarm.getTime().isBefore(LocalTime.now())) {
			log.fine("alarm time has already passed");
			return;
		}
		
		if(!alarm.getEnabled()) {
			log.fine("alarm disabled");
			return;
		}
		
		
		generateAlarmEvents(alarm);

		// sort events in order of execution time again
		Collections.sort(eventList);
	}
	
	/**
	 * sets the timer to switch off the sound
	 * @param secondsFromNow time in seconds from now to switch off sound again
	 */
	synchronized void setSoundTimer(int secondsFromNow) {
		if(soundTimerEvent!=null && eventList.stream().filter(event -> event==soundTimerEvent).findAny().isPresent()) {
			soundTimerEvent.time = LocalTime.now().plusSeconds(secondsFromNow);
		}
		else {
			// create a new event
			soundTimerEvent = new Event();
			soundTimerEvent.type      = Event.EventType.STOP_SOUND;
			soundTimerEvent.alarm     = null;
			soundTimerEvent.time      = LocalTime.now().plusSeconds(secondsFromNow);
			eventList.add(soundTimerEvent);
		}
		
		// sort events in order of execution time again
		Collections.sort(eventList);
	}
	
	/**
	 * deletes the timer to switch off the sound
	 */
	synchronized void deleteSoundTimer() {
		if(soundTimerEvent!=null) {
			Iterator<Event> it = eventList.iterator();
			while(it.hasNext()) {
				if( it.next()==soundTimerEvent ) {
					// this is the timer event
					it.remove();
					break;
				}
			}
			
			soundTimerEvent = null;
		}
	}
	
	/**
	 * returns the time in seconds from now when the sound gets switched off
	 * or null if timer is not active
	 * @return time in seconds from now when the sound gets switched off or null
	 */
	synchronized int getSoundTimer() {
		Integer secondsFromNow = 0;
		
		if(soundTimerEvent!=null && eventList.stream().filter(event -> event==soundTimerEvent).findAny().isPresent()) {
			secondsFromNow = soundTimerEvent.time.get(ChronoField.SECOND_OF_DAY)-LocalDateTime.now().get(ChronoField.SECOND_OF_DAY);
		}
		
		return secondsFromNow;
	}
	
	
	synchronized List<LightControl> getLightControlList() {
		return lightControlList;
	}
	
	synchronized JsonObject getSoundStatusAsJsonObject() {
		JsonObjectBuilder builder = Json.createBuilderFactory(null).createObjectBuilder();
		SoundControl soundControl = SoundControl.getSoundControl();
		
		builder.add("activeSound",soundControl.getActiveSound()==null ? "" : soundControl.getActiveSound().name );
		builder.add("activeVolume", soundControl.getVolume());
		builder.add("activeTimer", 0);
		
		JsonObject jsonObject = builder.build();
		log.fine("Created JsonObject for sound status: "+jsonObject.toString());
		
		return jsonObject;
	}
	
	synchronized void parseSoundStatusFromJsonObject(JsonObject jsonObject) {
		try {
			String soundName = jsonObject.getString("activeSound");
			if(soundName!=null && !soundName.isEmpty()) {
				try {
					Alarm.Sound sound = Configuration.getConfiguration().getSoundList().stream().filter(s -> s.name.equals(soundName)).findAny().get();
					if(sound!=null) {
						SoundControl.getSoundControl().on();
						SoundControl.getSoundControl().playSound(sound, null, false);
					}
				}
				catch(NoSuchElementException e) {
					log.severe("parseSoundStatusFromJsonObject: sound not found");
				}
			}
		}
		catch(NullPointerException e) {
			// activeSound is not stored in the JsonObject
		}
		
		try {
			int volume = jsonObject.getInt("activeVolume");
			SoundControl.getSoundControl().setVolume(volume);
			
			if(volume==0) {
				SoundControl.getSoundControl().off();
			}
		}
		catch(NullPointerException e) {
			// activeVolume is not stored in the Json Object
		}
	}
	
	
	/**
	 * fires an event
	 * @param e event to fire
	 */
	private void fireEvent(Event e) {	
		// if this is timer event, set timer to off
		if(e==soundTimerEvent) {
			soundTimerEvent = null;
		}
		
		if(e.alarm != null && e.alarm.getSkipOnce()) {
			log.fine("skipping firing event of type "+e.type);
			
			// do nothing if alarm is to be skipped
			if(e.type==alarmpi.Controller.Event.EventType.ALARM_END) {
				// skipping done. Mark alarm as active again
				e.alarm.setSkipOnce(false);
			}
			return;
		}
		
		log.fine("firing event of type "+e.type);
		
		switch(e.type) {
		case PLAY_SOUND:
			soundControl.playSound(e.sound, e.volume, !e.interrupt);
			break;
		case SET_VOLUME:
			soundControl.setVolume(e.volume);
			break;
		case STOP_SOUND:
			soundControl.off();
			break;
		case PLAY_WEATHER:
			if(weatherAnnouncementFile!=null && weatherAnnouncementFile.isDone()) {
				try {
					String file = weatherAnnouncementFile.get();
					if(file!=null && !file.isEmpty()) {
						soundControl.playFile(file, null, true);
					}
					else {
						log.warning("weather announcement file does not exist");
					}
				} catch (InterruptedException | ExecutionException exception) {
					log.severe("Unable to play weather announcement");
					log.severe(exception.getMessage());
				}
				weatherAnnouncementFile = null;
			}
			break;
		case PLAY_CALENDAR:
			if(calendarAnnouncementFile!=null && calendarAnnouncementFile.isDone()) {
				try {
					String file = calendarAnnouncementFile.get();
					if(file!=null && !file.isEmpty()) {
						soundControl.playFile(file, null, true);
					}
					else {
						// can be null in case no calendar entry exists
						log.fine("calendar announcement file does not exist");
					}
				} catch (InterruptedException | ExecutionException exception) {
					log.severe("Unable to play calendar announcement");
					log.severe(exception.getMessage());
				}
				calendarAnnouncementFile = null;
			}
			break;
		case LED_OFF:
			lightControlList.stream().forEach(control -> control.setOff());
			break;
		case ALARM_START:
			log.fine("start of alarm with id="+e.alarm.getId());
			activeAlarm = e.alarm;;
			soundControl.stop();
			soundControl.on();
			
			// start thread to dim up the light
			lightControlList.stream().forEach(control -> control.dimUp(e.alarm.getLightDimUpBrightness(), e.alarm.getLightDimUpDuration()));
			
			// trigger weather data and calendar retrieval in an extra thread
			weatherAnnouncementFile  = dataExecutorService.submit(new WeatherProvider());
			if(Configuration.getConfiguration().getCalendarSummary()!=null) {
				calendarAnnouncementFile = dataExecutorService.submit(new CalendarProvider());
			}
			
			// publish modified alarm status on MQTT broker
			MqttClient.getMqttClient().publishAlarmList();
			
			break;
		case ALARM_END:
			stopActiveAlarm();
			break;
		default:
			log.severe("Unknown event type: "+e.type);
			break;
		}
	}
	
	

	/**
	 * prepares the mp3 file to announce the time and an optional greeting before
	 * @param time      time to announce
	 * @return          name of generated mp3 file
	 */
	private String prepareTimeAnnouncement(LocalTime time) {
		int minute      = time.getMinute();
		int hour        = time.getHour();
		
		// create mp3 file with this time announcement
		String text = new String();
		text  += "Es ist jetzt "+hour+" Uhr";
		if(minute>0) {
			text += " "+Integer.toString(minute);
		}
		
		return new TextToSpeech().createPermanentFile(text);
	}
	
	/**
	 * returns all light control objects as JSON array
	 * @return all light control objects as JSON array
	 */
	final JsonArray getLightStatusAsJsonArray() {
		log.fine("creating JSON array with all light control objects");
		// add list of all light control objects
		JsonArrayBuilder arrayBuilder = Json.createBuilderFactory(null).createArrayBuilder();
		lightControlList.stream().forEach(control -> arrayBuilder.add(control.toJasonObject()));
		
		JsonArray array=arrayBuilder.build();
		log.fine("JSON array="+array);;
		
		return array;
	}

	/**
	 * parses the light status from a JSON object that contains the array "lights"
	 * @param jsonObject
	 */
	final void parseLightStatusFromJsonObject(JsonObject jsonObject) {
		log.fine("parsing light status from JSON array");
		JsonArray jsonArray = jsonObject.getJsonArray("lights");
		if(jsonArray!=null) {
			lightControlList.stream().forEach(light -> light.parseFromJsonArray(jsonArray));
		}
		else {
			log.fine("parseLightStatusFromJsonObject: JSON object has no array \"lights\"");
		}
	}

		
	//
	// private members
	//
	private static final Logger log = Logger.getLogger( Controller.class.getName() );
	
	/**
	 * private local class to model events
	 */
	private static class Event implements Comparable<Event> {
		enum EventType {SET_VOLUME,PLAY_SOUND,PLAY_WEATHER,PLAY_CALENDAR,STOP_SOUND,LED_OFF,LED_SET_PWM,ALARM_START,ALARM_END};

		EventType            type;            // event type
		Alarm                alarm;           // alarm to which this event belongs to (or null)
		LocalTime            time;            // event time
		Alarm.Sound          sound;           // sound to play for this event
		Integer              volume;          // sound volume (only for event type SET_VOLUME)
		boolean              interrupt;       // for type PLAY_SOUND only, interrupt current song
		
		@Override
		public int compareTo(Event e) {
			return time.compareTo(e.time);
		}
	}
	
	
	/**
	 * private class implementing Listener for PushButton
	 */
	private class PushButtonListener implements GpioPinListenerDigital {
		public PushButtonListener(Configuration.ButtonSettings pushButtonSetting,GpioPinDigitalInput inputPin,Controller controller) {
			this.pushButtonSetting = pushButtonSetting;
			this.inputPin          = inputPin;
			
			log.fine("Creating PushButtonListener for button with WiringPi IO="+pushButtonSetting.wiringpigpio+" light IDs="+pushButtonSetting.lightIds);
			
			if(pushButtonSetting.triggerSpeechControl) {
				// prepare speech control
				try {
					speechToCommand = new SpeechToCommand(controller);
				}
				catch(Throwable e) {
	        		log.severe("runtime exception in preparing speechToCommand: "+e.getMessage());
	        		log.severe("runtime exception in preparing speechToCommand: "+e.getCause());
	        		for(StackTraceElement element:e.getStackTrace()) {
	        			log.severe(element.toString());
	        		}
	        	}
			}
		}
		
        @Override
        public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
        	try {
	        	log.fine("LED control button state change on GPIO address "+event.getPin().getPin().getAddress()+" state="+event.getState()+" light IDy="+pushButtonSetting.lightIds);
	        	if(event.getState()==PinState.LOW) {
	        		start = System.currentTimeMillis();
	        		boolean longClick = false;
	        		while(inputPin.getState()==PinState.LOW) {
	        			try {
							TimeUnit.MILLISECONDS.sleep(50);
		            		if(System.currentTimeMillis()-start > 400) {
		            			// long click. Turn off everything
		            			log.fine("long click");
		            			longClick = true;
		            			
		            			// publish to MQTT broker (if configured)
		            			if(mqttClient!=null) {
		            				log.fine("publishing MQTT long click topic");
		            				mqttClient.publishLongClick();
		            			}
		            			
		            			allOff(activeAlarm==null);
		        				
		            			break;
		            		}
						} catch (InterruptedException e) {
							log.severe(e.getMessage());
						}
	        		}
	        		if(!longClick) {
	        			log.fine("short click");
	        			if(start-lastClick>300) {
	        				// single click
	    					log.fine("processing single click.");
	    					
	    					if(speechToCommand!=null) {
	    						// trigger speech control
	    						speechToCommand.captureCommand();
	    					}
	    					else {
								if(Configuration.getConfiguration().getMqttPublishTopicShortClick()!=null) {
			            			// publish to MQTT broker (if configured)
			            			if(mqttClient!=null) {
			            				log.fine("publishing MQTT short click topic");
			            				mqttClient.publishShortClick();
			            			}
								}
								else {
		    						// no speech control - increase LED brightness
		    						// lightControl.setBrightness(pushButtonSetting.lightId,lightControl.getBrightness(pushButtonSetting.lightId)+pushButtonSetting.brightnessIncrement);
		    						Alarm.Sound sound = Configuration.getConfiguration().getSoundList().get(pushButtonSetting.soundId);
		    						soundControl.playSound(sound, pushButtonSetting.soundVolume, false);
		    						try {
										Thread.sleep(100);
									} catch (InterruptedException e) {
										log.severe(e.getMessage());
									}
		    						soundControl.on();
		    						if(pushButtonSetting.soundTimer>0) {
		    							setSoundTimer(pushButtonSetting.soundTimer*60);
		    						}
								}
	    					}
	        			}
	        			else {
	        				// double click
	    					log.fine("procesing double click");
	    					if(soundControl.getVolume()>0) {
	    						// sound already on - switch it off
	    						soundControl.off();
	    					}
	    					else {
	    						soundControl.on();
	    						Alarm.Sound sound = Configuration.getConfiguration().getSoundList().get(pushButtonSetting.soundId);
	    						soundControl.playSound(sound, pushButtonSetting.soundVolume, false);
	    						if(pushButtonSetting.soundTimer>0) {
	    							setSoundTimer(pushButtonSetting.soundTimer*60);
	    						}
	    					}
	        			}
	        			
	        			lastClick = start;
	        		}
	        	}
        	}
        	catch(Throwable e) {
        		log.severe("runtime exception in button handler: "+e.getMessage());
        		log.severe("runtime exception in button handler: "+e.getCause());
        		for(StackTraceElement element:e.getStackTrace()) {
        			log.severe(element.toString());
        		}
        	}
        }
        
        private long                             start = System.currentTimeMillis();
        private Configuration.ButtonSettings pushButtonSetting;
        private GpioPinDigitalInput              inputPin;
        
        private SpeechToCommand                  speechToCommand; 
	}
	
	final static String watchDogFile       = "/var/log/alarmpi/watchdog";
	
	String               mqttSendAliveTopic;    // MQTT topic name used to publish alive messages
	int                  mqttSendAliveInterval; // interval for sending alive messages in minutes
	
	LinkedList<Event>    eventList;             // list of events to process, sorted by fire time
	Configuration        configuration;         // configuration data
	SoundControl         soundControl;          // proxy for sound control
	MqttClient           mqttClient;            // MQTT client (or null if no QMTT broker is configured)
	Alarm                activeAlarm;           // active alarm (or null if no alarm is active)
	Event                soundTimerEvent;       // event to switch off sound or null if no timer is active
	
	final List<LightControl>   lightControlList = new LinkedList<>();    // list of light control objects
	
	
	
	private long         lastClick;             // time in milliseconds since last push button click
	
	ExecutorService      dataExecutorService;      // thread executor service to retrieve data like weather or calendar
	Future<String>       weatherAnnouncementFile;  // future with filename of mp3 weather announcement
	Future<String>       calendarAnnouncementFile; // future with filename of mp3 calendar announcement
}
