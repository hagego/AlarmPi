package hagego.alarmpi;

import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.TimePicker;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;


/**
 * DayOfWeek enum as this is based on Java 7
 */
enum DayOfWeek {MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY}

/**
 * This class represents AlarmPi alarms.
 * It stores the alarm attributes but also the GUI widgets used in the ListView
 * to manage the data exchange with the GUI
 * Implements all the listener classes for GUI changes
 */
public class Alarm implements TimePicker.OnTimeChangedListener,CheckBox.OnCheckedChangeListener,AdapterView.OnItemSelectedListener {

    /**
     * Must be called before any other usage to initialize the application context
     * This is used to access application resources (strings)
     * @param context_ application context
     */
    static void setApplicationContext(Context context_) {
        context = context_;
    }

    static Alarm parseFromJsonObject(JSONObject jsonAlarm, Map<String,Integer> soundNameToIndexMap) {
        Alarm alarm = new Alarm();

        try {
            alarm.id          = UUID.fromString(jsonAlarm.getString("id"));
            alarm.enabled     = jsonAlarm.getBoolean("enabled");
            alarm.oneTimeOnly = jsonAlarm.getBoolean("oneTimeOnly");
            alarm.skipOnce    = jsonAlarm.getBoolean("skipOnce");
            alarm.hourOfDay   = LocalTime.parse(jsonAlarm.getString("time")).getHour();
            alarm.minuteOfDay = LocalTime.parse(jsonAlarm.getString("time")).getMinute();

            alarm.weekDays.clear();
            String weekDaysString = jsonAlarm.getString("weekDays");
            for(DayOfWeek dayOfWeek:DayOfWeek.values()) {
                if(weekDaysString.contains(dayOfWeek.toString())) {
                    alarm.weekDays.add(dayOfWeek);
                }
            }

            alarm.soundName = jsonAlarm.getString("alarmSound");
            Integer soundId = soundNameToIndexMap.get(alarm.soundName);
            if (soundId != null) {
                alarm.soundId = soundId;
            } else {
                Log.e(Constants.LOG, "parseFromJsonObject: no ID available for sound " + jsonAlarm.getString("alarmSound"));
                return null;
            }


        } catch (JSONException e) {
            Log.e(Constants.LOG, "parseFromJsonObject: JSON exception: "+e.getMessage());

            return null;
        } catch (IllegalArgumentException e) {
            Log.e(Constants.LOG, "parseFromJsonObject: Illegal argument exception: "+e.getMessage());

            return null;
        }
        return alarm;
    }

    /**
     * Creates a JsonObject representation of the alarm
     * @return JSONObject representation of the alarm or null in case of error
     */
    public JSONObject toJasonObject() {
        JSONObject jsonObject = new JSONObject();

        try {
            jsonObject.put("id",id);
            jsonObject.put("enabled",enabled);
            jsonObject.put("oneTimeOnly",oneTimeOnly);
            jsonObject.put("skipOnce",skipOnce);
            jsonObject.put("time",String.format("%02d:%02d",hourOfDay,minuteOfDay));
            jsonObject.put("weekDays",weekDays.toString());
            jsonObject.put("alarmSound",soundName);
        } catch (JSONException e) {
            Log.e(Constants.LOG,"Exception during creation of Alarm JSON Object: "+e.getMessage());
            return null;
        }

        Log.v(Constants.LOG,"Created JSON object for alarm: "+jsonObject.toString());
        return jsonObject;
    }

    private Alarm() {

    }

    /**
     * returns the ID of this alarm
     * @return ID of this alarm
     */
    public UUID getId() {
        return id;
    }

    /**
     * returns the sound for this alarm
     * @return sound for this alarm
     */
    Integer getSoundId() {
        return soundId;
    }

    @Override
    @NonNull
    public String toString() {

        String onOff = enabled ? context.getResources().getString(R.string.on) : context.getResources().getString(R.string.off);
        String weekdays = new String();
        for( DayOfWeek dayOfWeek:DayOfWeek.values()) {
            if(weekDays.contains(dayOfWeek)) {
                switch(dayOfWeek) {
                    case MONDAY:    weekdays += context.getResources().getString(R.string.stringWeekdayMonday).substring(0,1); break;
                    case TUESDAY:   weekdays += context.getResources().getString(R.string.stringWeekdayTuesday).substring(0,1); break;
                    case WEDNESDAY: weekdays += context.getResources().getString(R.string.stringWeekdayWednesday).substring(0,1); break;
                    case THURSDAY:  weekdays += context.getResources().getString(R.string.stringWeekdayThursday).substring(0,1); break;
                    case FRIDAY:    weekdays += context.getResources().getString(R.string.stringWeekdayFriday).substring(0,1); break;
                    case SATURDAY:  weekdays += context.getResources().getString(R.string.stringWeekdaySaturday).substring(0,1); break;
                    case SUNDAY:    weekdays += context.getResources().getString(R.string.stringWeekdaySunday).substring(0,1); break;
                }
            }
            else {
                weekdays += " ";
            }
        }
        return String.format(" %3s %s %02d:%02d",onOff,weekdays,hourOfDay,minuteOfDay);
    }

    /**
     * returns the enabled status of the alarm
     * @return enabled status of the alarm
     */
    public boolean getEnabled() {
        return enabled;
    }

    /**
     * returns if the alarm shall trigger only once and then becomes disabled again
     * @return oneTimeOnly status of the alarm
     */
    public boolean getOneTimeOnly() { return oneTimeOnly; }

    /**
     * returns if the alarm shall be skipped once
     * @return skipOnce status of the alarm
     */
    public boolean getSkipOnce() { return skipOnce; }

    /**
     * returns the alarm time as string in the format hh:mm
     * @return alarm time as string
     */
    public String getTime() {
        return String.format("%02d:%02d",hourOfDay,minuteOfDay);
    }

    /**
     * sets the index of this alarm in the alarm list of the proxy
     * @param index
     */
    public void setIndex(int index) {
        this.index = index;
    }

    /**
     * returns the index of this alarm in the alarm list of the proxy
     * @return index of this alarm in the alarm list of the proxy
     */
    public int getIndex() {
        return index;
    }

    /**
     * returns the active days as list of coma-separated strings
     * @return the active days as list of coma-separated strings
     */
    String getWeekDays() {
        String weekdays = new String();

        for( DayOfWeek dayOfWeek:DayOfWeek.values()) {
            if(weekDays.contains(dayOfWeek)) {
                if(weekdays.isEmpty()) {
                    weekdays=dayOfWeek.toString();
                }
                else {
                    weekdays += ","+dayOfWeek.toString();
                }
            }
        }

        if(weekdays.isEmpty()) {
            weekdays = "-";
        }

        return weekdays;
    }

    /**
     * returns if the alarm got modified locally and is not yet updated on AlarmPi
     * @return true if alarm has been modified locally
     */
    public boolean getHasChanged() {
        return hasChanged;
    }

    /**
     * marks this alarm as up to date with data on AlarmPi
     */
    public void resetHasChanged() {
        hasChanged = false;
    }

    View getView() {
        return view;
    }

    void setView(View view) {
        this.view = view;
    }

    /**
     * sets the CheckBox GUI widget for the enabled property of this alarm
     * and registers the alarm itself as handler for this checkbox
     * @param checkbox CheckBox GUI widget
     */
    void setCheckboxEnabled(CheckBox checkbox) {
        checkbox.setChecked(enabled);
        checkbox.setOnCheckedChangeListener(this);
    }

    /**
     * sets the CheckBox GUI widget for the oneTimeOnly property of this alarm
     * and registers the alarm itself as handler for this checkbox
     * @param checkbox CheckBox GUI widget
     */
    void setCheckboxOneTimeOnly(CheckBox checkbox) {
        checkbox.setChecked(oneTimeOnly);
        checkbox.setOnCheckedChangeListener(this);
    }

    /**
     * sets the CheckBox GUI widget for the skipOnce property of this alarm
     * and registers the alarm itself as handler for this checkbox
     * @param checkbox CheckBox GUI widget
     */
    void setCheckboxSkipOnce(CheckBox checkbox) {
        checkbox.setChecked(skipOnce);
        checkbox.setOnCheckedChangeListener(this);
    }

    /**
     * sets the TimePicker GUI object for this alarm and registers the alarm itself as handler
     * for time changes
     * @param timePicker TimePicker GUI widget
     */
    void setTimePicker(TimePicker timePicker) {
        timePicker.setHour(hourOfDay);
        timePicker.setMinute(minuteOfDay);
        timePicker.setOnTimeChangedListener(this);
    }

    /**
     * sets an alarm day checkbox GUI object for this alarm and registers the alarm itself as handler
     * for changes of active days
     * @param dayOfWeek day of week to which the checkbox belongs
     * @param checkbox  Checkbox GUI widget
     */
    void setWeekdayCheckbox(DayOfWeek dayOfWeek,CheckBox checkbox) {
        checkbox.setChecked(weekDays.contains(dayOfWeek));
        checkbox.setOnCheckedChangeListener(this);
    }

    void setSoundListSpinner(Spinner spinner) {
        spinner.setSelection(soundId);
        spinner.setOnItemSelectedListener(this);
    }

    //
    // private members
    //

    // alarm attributes
    private UUID                        id;                                          // alarm ID
    private boolean                     enabled;                                     // alarm enabled ?
    private boolean                     oneTimeOnly;                                 // one time only alarm ?
    private boolean                     skipOnce;                                    // skip one alarm event ?
    private EnumSet<DayOfWeek>          weekDays = EnumSet.noneOf(DayOfWeek.class);  // weekdays when this alarm is active
    private int                         hourOfDay;                                   // alarm hour
    private int                         minuteOfDay;                                 // alarm minute
    private int soundId;                                       // sound to play (index into sound list)
    private String                      soundName;                                   // sound to play (unique name)

    private int                         index;                                       // index of this alarm in the alarm list of the proxy
    private boolean                     hasChanged;                                  // stores if alarm got changed locally and needs an update on AlarmPi server

    // GUI View
    private View                        view;                      // child item View in ExpandableListView

    // Android application context
    private static Context              context;

    private Future<Boolean> proxyStatusAlarmUpdated;       // Future for updated status of alarms on AlarmPi

    // handlers
    @Override
    public void onTimeChanged(TimePicker view, int hour, int minute) {
        hourOfDay   = hour;
        minuteOfDay = minute;

        hasChanged = true;
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        DayOfWeek dayOfWeek=null;
        switch( buttonView.getId() ) {
            case R.id.checkBoxAlarmMonday: dayOfWeek=DayOfWeek.MONDAY;
                break;
            case R.id.checkBoxAlarmTuesday: dayOfWeek=DayOfWeek.TUESDAY;
                break;
            case R.id.checkBoxAlarmWednesday: dayOfWeek=DayOfWeek.WEDNESDAY;
                break;
            case R.id.checkBoxAlarmThursday: dayOfWeek=DayOfWeek.THURSDAY;
                break;
            case R.id.checkBoxAlarmFriday: dayOfWeek=DayOfWeek.FRIDAY;
                break;
            case R.id.checkBoxAlarmSaturday: dayOfWeek=DayOfWeek.SATURDAY;
                break;
            case R.id.checkBoxAlarmSunday: dayOfWeek=DayOfWeek.SUNDAY;
                break;
        }

        if(dayOfWeek!=null) {
            Log.d(Constants.LOG, dayOfWeek + " is set to " + isChecked);
            if (isChecked) {
                weekDays.add(dayOfWeek);
            } else {
                weekDays.remove(dayOfWeek);
            }
        }

        if(buttonView.getId()==R.id.checkBoxAlarmOn) {
            Log.d(Constants.LOG, "alarm enabled set to "+isChecked);
            enabled = isChecked;
        }

        if(buttonView.getId()==R.id.checkBoxAlarmOneTime) {
            Log.d(Constants.LOG, "alarm oneTimeOnly set to "+isChecked);
            oneTimeOnly = isChecked;
        }

        if(buttonView.getId()==R.id.checkBoxAlarmSkip) {
            Log.d(Constants.LOG, "alarm skipOnce set to "+isChecked);
            skipOnce = isChecked;
        }

        hasChanged = true;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if(position!= soundId) {
            Log.d(Constants.LOG, "alarm sound item changed: pos=" + position + " id=" + id);
            soundId = position;

            hasChanged = true;
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }
}
