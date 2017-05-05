package alarmpi;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoField;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalInput;
import com.pi4j.io.gpio.PinPullResistance;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;
import com.pi4j.io.gpio.event.GpioPinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;

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
		if(configuration.getRunningOnRaspberry()) {
			log.info("running on Raspberry - initializing WiringPi");
			
			// initialize wiringPi library
			// I get a crash when calling this - something changed in the new rev of pi4j
			// com.pi4j.wiringpi.Gpio.wiringPiSetup();
			GpioController gpioController       = GpioFactory.getInstance();
			
			log.info("running on Raspberry - initializing WiringPi done.");
			
			// instantiate light control
			log.info("running on Raspberry - initializing light control");
			if(configuration.getLightControlSettings().type==Configuration.LightControlSettings.Type.RASPBERRY) {
				lightControl = new LightControlRaspiPwm(configuration.getLightControlSettings());
			}
			else if(configuration.getLightControlSettings().type==Configuration.LightControlSettings.Type.PCA9685) {
				lightControl = new LightControlPCA9685(configuration.getLightControlSettings());
			}
			else {
				// dummy implementation - does nothing
				lightControl = new LightControlNone();
			}
			lightControl.off();
			log.info("running on Raspberry - initializing light control done");
			
			log.info("running on Raspberry - initializing sound control");
			soundControl = SoundControl.getSoundControl();
			log.info("running on Raspberry - initializing sound control done");
			
			// update mpd with tmp files for next alarm announcement
			new TextToSpeech().createTempFile("dummy", "nextAlarmToday.mp3");
			new TextToSpeech().createTempFile("dummy", "nextAlarmTomorrow.mp3");
			soundControl.update();
			
			
			// configure input key pins as input pins
			// default: key1=GPIO06 (BRCM GPIO 25)
			//          key2=GPIO05 (BRCM GPIO 24)
			log.info("running on Raspberry - initializing push buttons");
			for(Configuration.PushButtonSettings pushButtonSetting:configuration.getPushButtons()) {
				if(pushButtonSetting.wiringpigpio!=0) {
					GpioPinDigitalInput input = gpioController.provisionDigitalInputPin(RaspiPin.getPinByAddress(pushButtonSetting.wiringpigpio), PinPullResistance.PULL_UP);
					
					// add pin listener
					input.addListener(new PushButtonListener(pushButtonSetting,input));
				}

			}
			log.info("running on Raspberry - initializing push buttons done");
			/*
			LedStripControl ledControl = new LedStripControl();
			LedStripControl.LedPattern pattern = ledControl.new LedPatternRainbow1(1000, 1.0);
			//LedStripControl.LedPattern pattern = ledControl.new LedPatternDimUp(300, (short)255);
			pattern.setCorrectionFactors(1.0, 0.3, 0.3);
			ledControl.executePattern(pattern);
			*/
			
			log.info("running on Raspberry - initialization done");
		}
	}
	
	/**
	 * switches everything off
	 */
	void allOff(boolean announceNextAlarm) {
		stopAlarm();
		lightControl.off();
		soundControl.stop();
		
		if(announceNextAlarm) {
			// announce next alarms before switching off
			Configuration.Alarm alarm = getNextAlarmToday();
			if(alarm!=null) {
				String text = "Der nächste Alarm ist heute um "+alarm.time.getHour()+" Uhr";
				if(alarm.time.getMinute()!=0) {
					text += alarm.time.getMinute();
				}
				String filename = new TextToSpeech().createTempFile(text, "nextAlarmToday.mp3");
				soundControl.on();
				soundControl.setVolume(30);
				soundControl.playFile(filename, null, false);
			}
			else {
				alarm = getNextAlarmTomorrow();
				if(alarm!=null) {
					String text = "Der nächste Alarm ist morgen um "+alarm.time.getHour()+" Uhr";
					if(alarm.time.getMinute()!=0) {
						text += alarm.time.getMinute();
					}
					String filename = new TextToSpeech().createTempFile(text, "nextAlarmTomorrow.mp3");
					soundControl.on();
					soundControl.setVolume(30);
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
		
		final int sleepPeriod = 500;   // thread sleep period: 500ms
		
		log.info("controller daemon thread started");
		
		LocalDate date = LocalDate.now();
		
		// create all the events for the alarms of today
		for(Configuration.Alarm alarm:configuration.getAlarmList()) {
			addAlarmEvents(alarm);
		}
		
		// create all the events for the alarms of today which are still in the future
		for(Configuration.Alarm alarm:configuration.getAlarmList()) {
			if(alarm.time.isAfter(LocalTime.now())) {
				addAlarmEvents(alarm);
			}
		}

		// start endless loop
		while (!Thread.interrupted()) {
			try {
				// check for a new day
				if(!date.equals(LocalDate.now())) {
					date = LocalDate.now();
					log.fine("New day detected, adding alarms for "+date);
					
					// create all the events for the alarms of today
					deleteAlarmEvents();
					for(Configuration.Alarm alarm:configuration.getAlarmList()) {
						addAlarmEvents(alarm);
					}
				}
				
				// check if an alarm was modified and the events need to be processed
				Integer id = null;
				if((id=configuration.getAlarmToProcess())!=null) {
					// an alarm has changed. First delete all old events
					log.fine("processing change in alarm ID="+id);
					deleteAlarmEvents(id);
					
					for(Configuration.Alarm alarm:configuration.getAlarmList()) {
						if(alarm.id==id) {
							// and add events again
							addAlarmEvents(alarm);
							break;
						}
					}
				}
				
				// check if an event needs to be processed
				checkForEventsToProcess();
				
				// sleep
				TimeUnit.MILLISECONDS.sleep(sleepPeriod);
			} catch (InterruptedException e) {
				// this indicates that SIGTERM signal has been recived
				log.warning("controller loop interrupted");
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
				if(LocalDateTime.now().isAfter(e.time)) {
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
	synchronized void stopAlarm() {
		if(activeAlarmId!=null) {
			log.fine("stopping active alarm");
			
			lightControl.off();
			soundControl.off();
			deleteAlarmEvents(activeAlarmId);
			
			activeAlarmId = null;
		}
		else {
			log.fine("no alarm active");
		}
	}

	/**
	 * deletes all alarm events
	 */
	synchronized private void deleteAlarmEvents() {
		log.fine("deleting all alarm events");
		eventList.clear();
		
		lightControl.off();
		soundControl.off();
		activeAlarmId = null;
	}
	
	/**
	 * deletes all events corresponding to this alarm
	 * @param alarmId  alarm ID
	 */
	synchronized private void deleteAlarmEvents(int alarmId) {
		log.fine("deleting alarm events for alarm ID="+alarmId);
		Iterator<Event> it = eventList.iterator();
		while(it.hasNext()) {
			Event e = it.next();
			if(e.alarmId!=null && e.alarmId==alarmId) {
				// delete this event
				it.remove();
			}
		}
		
		if(activeAlarmId!=null && alarmId==activeAlarmId) {
			lightControl.off();
			soundControl.off();
			activeAlarmId = null;
		}
	}
	
	/**
	 * Returns the next active alarm today
	 * @return
	 */
	Configuration.Alarm getNextAlarmToday() {
		DayOfWeek today               = LocalDate.now().getDayOfWeek();
		Configuration.Alarm nextAlarm = null;
		
		for(Configuration.Alarm alarm:configuration.getAlarmList()) {
			if(alarm.enabled && alarm.weekDays.contains(today)) {
				if(alarm.time.isAfter(LocalTime.now()) && (nextAlarm==null || alarm.time.isBefore(nextAlarm.time))) {
					nextAlarm = alarm;
				}
			}
		}
		
		return nextAlarm;
	}
	
	/**
	 * Returns the next active alarm tomorrow
	 * @return
	 */
	Configuration.Alarm getNextAlarmTomorrow() {
		DayOfWeek tomorrow            = LocalDate.now().getDayOfWeek().plus(1);
		Configuration.Alarm nextAlarm = null;
		
		for(Configuration.Alarm alarm:configuration.getAlarmList()) {
			if(alarm.enabled && alarm.weekDays.contains(tomorrow)) {
				if(nextAlarm==null || alarm.time.isBefore(nextAlarm.time)) {
					nextAlarm = alarm;
				}
			}
		}
		
		return nextAlarm;
	}
	
	/**
	 * generates the events needed to process an alarm
	 * @param alarm new alarm to process
	 */
	synchronized private void addAlarmEvents(Configuration.Alarm alarm) {
		log.fine("generating events for alarm ID="+alarm.id+" at time="+alarm.time);
		
		if(!alarm.weekDays.contains(LocalDate.now().getDayOfWeek())) {
			log.fine("alarm not scheduled for today");
			return;
		}
		
		if(alarm.time.isBefore(LocalTime.now())) {
			log.fine("alarm time has already passed");
			return;
		}
		
		if(!alarm.enabled) {
			log.fine("alarm disabled");
			return;
		}
		
		if(alarm.skipOnce) {
			log.fine("alarm skipOnce set, skipping");
			alarm.skipOnce = false;
			alarm.store();
			
			return;
			
		}
		
		// alarm time as LocalDateTime
		LocalDateTime alarmDateTime = LocalDate.now().atTime(alarm.time);
		
		// generate fade-in events
		final int stepCountFadeIn         = 20;
		final LocalDateTime fadeInStart   = alarmDateTime.minusSeconds(alarm.fadeInDuration);
		final double fadeInTimeInterval   = (double)alarm.fadeInDuration/(double)stepCountFadeIn;                 // in seconds
		final double fadeInVolumeInterval = (double)(alarm.volumeFadeInEnd-alarm.volumeFadeInStart)/(double)stepCountFadeIn;
		
		log.fine("fade in start at: "+fadeInStart+" alarm at "+alarmDateTime+" stop at "+alarmDateTime.plusSeconds(alarm.duration));
		
		// create events only if alarm is still to come today
		if(fadeInStart.isAfter(LocalDateTime.now())) {
			Event eventStart = new Event();
			eventStart.type      = Event.EventType.ALARM_START;
			eventStart.alarmId   = alarm.id;
			eventStart.time      = fadeInStart;
			eventStart.paramInt1 = alarm.lightDimUpDuration;
			eventStart.paramInt2 = alarm.lightDimUpBrightness;
			eventList.add(eventStart);
			
			Event eventSound = new Event();
			eventSound.type         = Event.EventType.PLAY_SOUND;
			eventSound.alarmId      = alarm.id;
			eventSound.time         = fadeInStart.plusNanos(1);
			eventSound.paramInt1    = alarm.soundId;
			eventSound.paramInt2    = alarm.volumeFadeInStart;
			eventList.add(eventSound);
			
			for(int step=1 ; step<stepCountFadeIn ; step++) {
				// increase volume
				Event eventVolume = new Event();
				eventVolume.type      = Event.EventType.SET_VOLUME;
				eventVolume.alarmId   = alarm.id;
				eventVolume.time      = fadeInStart.plusSeconds((long)(step*fadeInTimeInterval));
				eventVolume.paramInt1 = alarm.volumeFadeInStart+(int)(step*fadeInVolumeInterval);
				eventList.add(eventVolume);
			}
		}
		
		// generate post fade-in events to increase volume and brightness
		final int    stepCountPostFadeIn      = 5;
		final double postFadeInTimeInterval   = (double)alarm.duration/(double)stepCountPostFadeIn;    // in seconds
		final double postFadeInVolumeInterval = (double)(alarm.volumeAlarmEnd-alarm.volumeFadeInEnd)/(double)stepCountPostFadeIn;
		
		for(int step=1 ; step<stepCountPostFadeIn ; step++) {
			// increase volume
			Event eventVolume = new Event();
			eventVolume.type      = Event.EventType.SET_VOLUME;
			eventVolume.alarmId   = alarm.id;
			eventVolume.time      = alarmDateTime.plusSeconds((long)(step*postFadeInTimeInterval));
			eventVolume.paramInt1 = alarm.volumeFadeInEnd+(int)(step*postFadeInVolumeInterval);
			eventList.add(eventVolume);
		}
		
		// generate main alarm and post alarm announcements
		Event eventGreeting = new Event();
		eventGreeting.type         = Event.EventType.PLAY_FILE;
		eventGreeting.alarmId      = alarm.id;
		eventGreeting.time         = alarmDateTime.plusNanos(1);
		eventGreeting.paramString  = new TextToSpeech().createPermanentFile(alarm.greeting);
		eventGreeting.paramBool    = true;
		eventList.add(eventGreeting);
		
		if(alarmDateTime.isAfter(LocalDateTime.now())) {
			int count = 0;
			int ANNOUNCEMENT_INTERVAL = 3;
			
			for(LocalDateTime time=alarmDateTime ;  time.isBefore(alarmDateTime.plusSeconds(alarm.duration)) ; time=time.plusSeconds(alarm.reminderInterval)) {
				boolean append = false;
				if(alarm.sound!=null) {
					Event eventAlarm = new Event();
					eventAlarm.type         = Event.EventType.PLAY_FILE;
					eventAlarm.alarmId      = alarm.id;
					eventAlarm.time         = time;
					eventAlarm.paramString  = alarm.sound;
					eventAlarm.paramBool    = append;
					eventList.add(eventAlarm);
					
					append = true;
				}
				
				Event eventAnnouncement = new Event();
				eventAnnouncement.type         = Event.EventType.PLAY_FILE;
				eventAnnouncement.alarmId      = alarm.id;
				eventAnnouncement.time         = time.plusNanos(2);
				eventAnnouncement.paramString  = prepareTimeAnnouncement(time.toLocalTime());
				eventAnnouncement.paramBool    = append;
				eventList.add(eventAnnouncement);
				
				if(count%ANNOUNCEMENT_INTERVAL == 0) {
					// play additional announcements
					Event eventWeather = new Event();
					eventWeather.type         = Event.EventType.PLAY_WEATHER;
					eventWeather.alarmId      = alarm.id;
					eventWeather.time         = time.plusNanos(3);
					eventList.add(eventWeather);
					
					if(Configuration.getConfiguration().getCalendarSummary()!=null) {
						Event eventCalendar = new Event();
						eventCalendar.type         = Event.EventType.PLAY_CALENDAR;
						eventCalendar.alarmId      = alarm.id;
						eventCalendar.time         = time.plusNanos(4);
						eventList.add(eventCalendar);
					}
				}
				
				Event eventPlay = new Event();
				eventPlay.type         = Event.EventType.PLAY_SOUND;
				eventPlay.alarmId      = alarm.id;
				eventPlay.time         = time.plusNanos(5);
				eventPlay.paramInt1    = alarm.soundId;
				eventPlay.paramBool    = true;
				eventList.add(eventPlay);
				
				count++;
			}
			
			Event eventStop = new Event();
			eventStop.type      = Event.EventType.ALARM_END;
			eventStop.alarmId   = alarm.id;
			eventStop.time      = alarmDateTime.plusSeconds(alarm.duration);
			eventList.add(eventStop);
		}
		
		// update mpd database
		soundControl.update();
		
		// sort events in order of execution time again
		Collections.sort(eventList);
	}
	
	/**
	 * sets the timer to switch off the sound
	 * @param secondsFromNow time in seconds from now to switch off sound again
	 */
	synchronized void setSoundTimer(int secondsFromNow) {
		boolean found = false;
		
		// check if timer is already active
		if(soundTimerEvent!=null) {
			Iterator<Event> it = eventList.iterator();
			while(it.hasNext()) {
				if( it.next()==soundTimerEvent ) {
					// sound timer already active, this is the stop event for it
					// adopt time of the event
					soundTimerEvent.time = LocalDateTime.now().plusSeconds(secondsFromNow);
					found = true;
				}
			}
		}
		
		if(!found) {
			// create a new event
			soundTimerEvent = new Event();
			soundTimerEvent.type      = Event.EventType.STOP_SOUND;
			soundTimerEvent.alarmId   = null;
			soundTimerEvent.time      = LocalDateTime.now().plusSeconds(secondsFromNow);
			eventList.add(soundTimerEvent);
		}
		
		// sort events in order of execution time again
		Collections.sort(eventList);
	}
	
	/**
	 * deletes the timer to switch off the sound
	 */
	synchronized void deleteSoundTimer() {
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
	
	/**
	 * returns the time in seconds from now when the sound gets switched off
	 * or null if timer is not active
	 * @return time in seconds from now when the sound gets switched off or null
	 */
	synchronized int getSoundTimer() {
		Integer secondsFromNow = 0;
		
		if(soundTimerEvent!=null) {
			Iterator<Event> it = eventList.iterator();
			while(it.hasNext()) {
				if( it.next()==soundTimerEvent ) {
					// this is the timer event
					secondsFromNow = soundTimerEvent.time.get(ChronoField.SECOND_OF_DAY)-LocalDateTime.now().get(ChronoField.SECOND_OF_DAY);
				}
			}
		}
		
		return secondsFromNow;
	}
	
	
	/**
	 * fires an event
	 * @param e event to fire
	 */
	private void fireEvent(Event e) {	
		log.fine("firing event of type "+e.type+" i1="+e.paramInt1+" i2="+e.paramInt2+" s1="+e.paramString);
		
		// if this is timer event, set timer to off
		if(e==soundTimerEvent) {
			soundTimerEvent = null;
		}
		
		switch(e.type) {
		case PLAY_SOUND:
			soundControl.playSound(e.paramInt1, e.paramInt2, e.paramBool==null ? true : e.paramBool);
			break;
		case SET_VOLUME:
			soundControl.setVolume(e.paramInt1);
			break;
		case STOP_SOUND:
			soundControl.off();
			break;
		case PLAY_FILE:
			soundControl.playFile(e.paramString, e.paramInt2, e.paramBool==null ? true : e.paramBool);
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
			lightControl.off();
			break;
		case ALARM_START:
			log.fine("start of alarm with id="+e.alarmId);
			activeAlarmId = e.alarmId;
			soundControl.stop();
			soundControl.on();
			
			// start thread to dim up the light
			lightControl.dimUp(e.paramInt2, e.paramInt1);
			
			// trigger weather data and calendar retrieval in an extra thread
			weatherAnnouncementFile  = dataExecutorService.submit(new WeatherProvider());
			if(Configuration.getConfiguration().getCalendarSummary()!=null) {
				calendarAnnouncementFile = dataExecutorService.submit(new CalendarProvider());
			}
			
			// if this alarm is one time only: disable it again
			Configuration.Alarm alarm = configuration.getAlarm(e.alarmId);
			if(alarm==null) {
				log.warning("getAlarm at ALARM_START returns null");
			}
			else {
				if(alarm.oneTimeOnly) {
					log.fine("oneTimeOnly alarm. Disabling again");
					configuration.enableAlarm(e.alarmId,false,false);
				}
				else {
					log.fine("oneTimeOnly=false. Leaving alarm enabled.");
				}
			}
			break;
		case ALARM_END:
			stopAlarm();
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
	 * returns the LightControl object
	 * @return LightControl object
	 */
	LightControl getLightControl() {
		return lightControl;
	}
		
	//
	// private members
	//
	private static final Logger log = Logger.getLogger( Controller.class.getName() );
	
	/**
	 * private local class to model events
	 */
	private static class Event implements Comparable<Event> {
		enum EventType {SET_VOLUME,PLAY_SOUND,PLAY_WEATHER,PLAY_CALENDAR,PLAY_FILE,STOP_SOUND,LED_OFF,LED_SET_PWM,ALARM_START,ALARM_END};

		EventType      type;            // event type
		Integer        alarmId;         // ID of the alarm this event belongs to or null
		LocalDateTime  time;            // event time
		Integer        paramInt1;       // integer parameter 1, depends on event type
		Integer        paramInt2;       // integer parameter 2, depends on event type
		String         paramString;     // String parameter 1, depends on event type
		Boolean        paramBool;       // Boolean parameter, depends on event type
		
		@Override
		public int compareTo(Event e) {
			return time.compareTo(e.time);
		}
	}
	
	/**
	 * private class implementing Listener for PushButton
	 */
	private class PushButtonListener implements GpioPinListenerDigital {
		public PushButtonListener(Configuration.PushButtonSettings pushButtonSetting,GpioPinDigitalInput inputPin) {
			this.pushButtonSetting = pushButtonSetting;
			this.inputPin          = inputPin;
			
			log.fine("Creating PushButtonListener for button with WiringPi IO="+pushButtonSetting.wiringpigpio+" light ID="+pushButtonSetting.lightId);
		}
		
        @Override
        public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
        	log.fine("LED control button state change on GPIO address "+event.getPin().getPin().getAddress()+" state="+event.getState()+" light ID="+pushButtonSetting.lightId);
        	if(event.getState()==PinState.LOW) {
        		start = System.currentTimeMillis();
        		boolean longClick = false;
        		while(inputPin.getState()==PinState.LOW) {
        			try {
						TimeUnit.MILLISECONDS.sleep(50);
	            		if(System.currentTimeMillis()-start > 400) {
	            			// long click. Turn off everything
	            			log.info("long click");
	            			longClick = true;
	            			
	            			final String cmd = "light_bedroom_off";
	            			if(configuration.getOpenhabCommands().contains(cmd)) {
	            				log.info("sending openhab command: "+cmd);
	            				OpenhabClient client = new OpenhabClient();
		        				String error = null;
		        				if( !client.sendCommand(cmd, error) ) {
		        					log.severe("error sending openhab command "+cmd+" : "+error);
		        				}
	            			}
	            			
	            			allOff(true);
	        				
	            			break;
	            		}
					} catch (InterruptedException e) {
						log.severe(e.getMessage());
					}
        		}
        		if(!longClick) {
        			log.info("short click");
        			if(start-lastClick>300) {
        				// single click
    					log.info("processing single click");
    					lightControl.setBrightness(pushButtonSetting.lightId,lightControl.getBrightness(pushButtonSetting.lightId)+pushButtonSetting.brightnessIncrement);
        			}
        			else {
        				// double click
    					log.info("procesing double click");
    					if(soundControl.getVolume()>0) {
    						// sound already on - switch it off
    						soundControl.off();
    					}
    					else {
    						soundControl.on();
    						soundControl.playSound(pushButtonSetting.soundId, pushButtonSetting.soundVolume, false);
    						if(pushButtonSetting.soundTimer>0) {
    							setSoundTimer(pushButtonSetting.soundTimer*60);
    						}
    					}
        			}
        			
        			lastClick = start;
        		}
        	}
        }
        
        private long                             start = System.currentTimeMillis();
        private Configuration.PushButtonSettings pushButtonSetting;
        private GpioPinDigitalInput              inputPin;
	}
	
	LinkedList<Event>    eventList;             // list of events to process, sorted by fire time
	Configuration        configuration;         // configuration data
	SoundControl         soundControl;          // proxy for sound control	
	Integer              activeAlarmId;         // ID of active alarm (or null if no alarm is active)
	Event                soundTimerEvent;       // event to switch off sound or null if no timer is active
	
	LightControl         lightControl;          // light control
	
	
	
	private long         lastClick;             // time in milliseconds since last push button click
	
	ExecutorService      dataExecutorService;      // thread executor service to retrieve data like weather or calendar
	Future<String>       weatherAnnouncementFile;  // future with filename of mp3 weather announcement
	Future<String>       calendarAnnouncementFile; // future with filename of mp3 calendar announcement
}
