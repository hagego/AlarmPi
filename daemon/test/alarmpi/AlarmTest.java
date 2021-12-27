package alarmpi;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.StringReader;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.UUID;
import java.util.logging.LogManager;

import javax.json.Json;
import javax.json.JsonObject;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import alarmpi.Alarm.Sound.Type;



class AlarmTest {
	
	@BeforeAll
	static void setUpBeforeClass(@TempDir Path tempDir) throws Exception {
		System.setProperty( "java.util.logging.config.file", "conf/alarmpitest.logging" );
		
		try {
			LogManager.getLogManager().readConfiguration();
		}
		catch ( Exception e ) {
			// unable to read logging configuration file
			e.printStackTrace();
		}
		
		// read configuration file
		Configuration.read("conf/alarmpitest.cfg");

		// use temp directory to store alarm list
		Alarm.setStorageDirectory(tempDir.toString());
	}
	
	// asserts that alarm has default properties
	void assertDefaultProperties(Alarm alarm) {
		assertThat(alarm.getEnabled(),is(false));
		assertThat(alarm.getOneTimeOnly(),is(false));
		assertThat(alarm.getSkipOnce(),is(false));
		assertThat(alarm.getTime(),is(LocalTime.MIDNIGHT));
		assertThat(alarm.getWeekDays(),is(EnumSet.noneOf(DayOfWeek.class)));
		assertThat(alarm.getSignalSoundList(),hasSize(2));
	}

	@Test
	void testConstructor() {
		Alarm alarm = new Alarm();
		assertDefaultProperties(alarm);
	}
	
	@Test
	void testFromJsonObjectEmpty() {
		final String emptyAlarmString = "{}";
		
		JsonObject jsonObject = Json.createReaderFactory(null).createReader(new StringReader(emptyAlarmString)).readObject();
		Alarm alarm = new Alarm();
		UUID id = alarm.getId();
		alarm.fromJsonObject(jsonObject);
		assertThat(alarm.getId(),is(id));
		assertDefaultProperties(alarm);
	}
	
	@Test
	void testFromJsonObjectWithId() {
		final String alarmString = "{\"id\" : \"95421e49-f5e2-418f-8063-c19975e1371b\"}";
		
		JsonObject jsonObject = Json.createReaderFactory(null).createReader(new StringReader(alarmString)).readObject();
		Alarm alarm = new Alarm();
		alarm.fromJsonObject(jsonObject);
		assertThat(alarm.getId().toString(),is("95421e49-f5e2-418f-8063-c19975e1371b"));
		assertDefaultProperties(alarm);
	}
	
	@Test
	void testFromJsonObjectWithWrongType() {
		final String alarmString = "{\"enabled\" : \"abc\"}";
		
		JsonObject jsonObject = Json.createReaderFactory(null).createReader(new StringReader(alarmString)).readObject();
		Alarm alarm = new Alarm();
		UUID id = alarm.getId();
		alarm.fromJsonObject(jsonObject);
		assertThat(alarm.getId(),is(id));
		assertDefaultProperties(alarm);
	}
	
	@Test
	void testJsonLoopback() {
		Alarm alarm = new Alarm();
		UUID id = alarm.getId();
		JsonObject jsonObject = alarm.toJsonObject();
		alarm.fromJsonObject(jsonObject);
		assertThat(alarm.getId(),is(id));
		assertDefaultProperties(alarm);
	}
	
	@Test
	void testJsonLoopbackWithInvalidSound() {
		Alarm alarm = new Alarm();
		Alarm.Sound sound = new Alarm.Sound();
		sound.name   = "invalid alarm sound";
		sound.source = "source";
		sound.type   = Type.STREAM;
		alarm.setAlarmSound(sound);
		
		JsonObject jsonObject = alarm.toJsonObject();
		
		// needed to initialize sound list
				Alarm.restoreAlarmList();
		alarm.fromJsonObject(jsonObject);
		assertNull(alarm.getAlarmSound());
	}
	
	@Test
	void testJsonLoopbackWithModifications() {
		Alarm alarm = new Alarm();
		alarm.setEnabled(true);
		alarm.setOneTimeOnly(true);
		alarm.setSkipOnce(true);
		
		LocalTime time = LocalTime.of(23, 32);
		alarm.setTime(time);
		
		EnumSet<DayOfWeek> weekDays = EnumSet.noneOf(DayOfWeek.class);
		weekDays.add(DayOfWeek.MONDAY);
		alarm.setWeekDays(weekDays);
		
		Alarm.Sound sound = new Alarm.Sound();
		sound.name   = "alarm sound";
		sound.source = "source";
		sound.type   = Type.STREAM;
		alarm.setAlarmSound(sound);
		
		UUID id = alarm.getId();
		JsonObject jsonObject = alarm.toJsonObject();
		
		// needed to initialize sound list
		Alarm.restoreAlarmList();
		
		alarm.fromJsonObject(jsonObject);
		assertThat(alarm.getId(),is(id));
		assertThat(alarm.getEnabled(),is(true));
		assertThat(alarm.getOneTimeOnly(),is(true));
		assertThat(alarm.getSkipOnce(),is(true));
		assertThat(alarm.getTime(),is(time));
		assertThat(alarm.getWeekDays(),is(weekDays));
		assertNotNull(alarm.getAlarmSound());
	}
	
	@Test
	void testStoreAndRestoreAlarmList() {
		Alarm.restoreAlarmList();
		assertThat(Alarm.getAlarmListAsJsonArray(),hasSize(4));
		assertThat(Alarm.getAlarmList().get(0).getSignalSoundList(),hasSize(2));
	}

	@Test
	void testModifyAlarm() {
		Alarm.restoreAlarmList();
		Alarm alarm2 = Alarm.getAlarmList().stream().findFirst().get();
		alarm2.setSkipOnce(true);
		UUID idAlarm2 = alarm2.getId();
		
		assertThat(Alarm.getAlarmList().stream().filter(alarm -> alarm.getId().equals(idAlarm2)).findFirst().get().getSkipOnce(),is(true));
		
		Alarm alarm3 = new Alarm();
		alarm3.fromJsonObject(alarm2.toJsonObject());
		assertThat(alarm3.getId(),is(alarm2.getId()));
		
		alarm3.setSkipOnce(false);
		Alarm.updateAlarmFromJsonObject(alarm3.toJsonObject());
		assertThat(Alarm.getAlarmList().stream().filter(alarm -> alarm.getId().equals(idAlarm2)).findFirst().get().getSkipOnce(),is(false));
	}
	
	@Test
	void testSetAlarmSoundWithValidSound() {
		Alarm.Sound sound = new Alarm.Sound();
		sound.name   = "name";
		sound.source = "source";
		sound.type   = Type.STREAM;
		
		Alarm alarm = new Alarm();
		alarm.setAlarmSound(sound);
		
		assertNotNull(alarm.getAlarmSound());
	}
	
	@Test
	void testSetAlarmSoundWithInvalidSound() {
		Alarm.Sound sound = new Alarm.Sound();
		sound.name   = "name";
		sound.source = "source";
		sound.type   = Type.FILE;
		
		Alarm alarm = new Alarm();
		alarm.setAlarmSound(sound);
		
		assertNull(alarm.getAlarmSound());
	}
	
	@Test
	void testGetNextAlarmTodayNoAlarm() {
		assertNull(Alarm.getNextAlarmToday());
	}
	
	@Test
	void testGetNextAlarmToday() {
		Alarm.restoreAlarmList();
		Alarm alarm = Alarm.getAlarmList().get(0);
		alarm.setEnabled(true);
		alarm.getWeekDays().add(LocalDate.now().getDayOfWeek());
		alarm.setTime(LocalTime.now().plusMinutes(1));
		assertThat(Alarm.getNextAlarmToday(),is(alarm));
	}
	
	@Test
	void testGetNextTomorrowTodayNoAlarm() {
		assertNull(Alarm.getNextAlarmTomorrow());
	}
	
	@Test
	void testGetNextAlarmTomorrow() {
		Alarm.restoreAlarmList();
		Alarm alarm = Alarm.getAlarmList().get(0);
		alarm.setEnabled(true);
		alarm.getWeekDays().add(LocalDate.now().getDayOfWeek().plus(1));
		alarm.setTime(LocalTime.now());
		assertThat(Alarm.getNextAlarmTomorrow(),is(alarm));
	}
}
