package hagego.alarmpi;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseExpandableListAdapter;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;

/**
 * Adapter class for alarm list
 */
public class AlarmListAdapter extends BaseExpandableListAdapter {

    /**
     * constructor
     * @param context context
     */
    public AlarmListAdapter(Context context,Proxy proxy) {
        this.context = context;
        this.proxy   = proxy;
    }

    @Override
    public Object getChild(int groupPosition, int childPosititon) {
        return proxy.getAlarmList().get(groupPosition);
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        // only one child - always 0
        return childPosition;
    }

    @Override
    public View getChildView(int groupPosition, final int childPosition,
                             boolean isLastChild, View convertView, ViewGroup parent) {

        Log.d(Constants.LOG, "AlarmListAdapter.getChildView called for alarm ID="+groupPosition+", view="+convertView );
        final Alarm alarm = (Alarm) getChild(groupPosition, childPosition);

        if (convertView == null || alarm.getView()==null || alarm.getView()!=convertView ) {
            LayoutInflater inflater = (LayoutInflater) this.context
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = inflater.inflate(R.layout.listview_item_alarm, null);
            Log.d(Constants.LOG, "Created new view in AlarmListAdapter.getChildView: "+convertView );

            alarm.setView(convertView);

            // create GUI View objects for alarm properties
            Spinner spinnerSoundList = (Spinner) convertView.findViewById(R.id.spinnerAlarmSoundList);
            ArrayAdapter<String> soundAdapter = new ArrayAdapter<String>(context,
                    android.R.layout.simple_spinner_item, proxy.getSoundList());
            soundAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerSoundList.setAdapter(soundAdapter);
            alarm.setSoundListSpinner(spinnerSoundList);

            TimePicker timePicker = (TimePicker)convertView.findViewById(R.id.timePickerAlarm);
            timePicker.setIs24HourView(true);
            alarm.setTimePicker(timePicker);

            alarm.setWeekdayCheckbox(DayOfWeek.MONDAY, (CheckBox) convertView.findViewById(R.id.checkBoxAlarmMonday));
            alarm.setWeekdayCheckbox(DayOfWeek.TUESDAY,(CheckBox)convertView.findViewById(R.id.checkBoxAlarmTuesday));
            alarm.setWeekdayCheckbox(DayOfWeek.WEDNESDAY,(CheckBox)convertView.findViewById(R.id.checkBoxAlarmWednesday));
            alarm.setWeekdayCheckbox(DayOfWeek.THURSDAY,(CheckBox)convertView.findViewById(R.id.checkBoxAlarmThursday));
            alarm.setWeekdayCheckbox(DayOfWeek.FRIDAY,(CheckBox)convertView.findViewById(R.id.checkBoxAlarmFriday));
            alarm.setWeekdayCheckbox(DayOfWeek.SATURDAY,(CheckBox)convertView.findViewById(R.id.checkBoxAlarmSaturday));
            alarm.setWeekdayCheckbox(DayOfWeek.SUNDAY,(CheckBox)convertView.findViewById(R.id.checkBoxAlarmSunday));

            alarm.setCheckboxEnabled((CheckBox) convertView.findViewById(R.id.checkBoxAlarmOn));
            alarm.setCheckboxOneTimeOnly((CheckBox)convertView.findViewById(R.id.checkBoxAlarmOneTime));
        }

        return convertView;
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        // always 1
        return 1;
    }

    @Override
    public Object getGroup(int groupPosition) {
        return proxy.getAlarmList().get(groupPosition);
    }

    @Override
    public int getGroupCount() {
        return proxy.getAlarmList().size();
    }

    @Override
    public long getGroupId(int groupPosition) {
        Alarm alarm = (Alarm)getGroup(groupPosition);
        return alarm.getId();
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
        Log.d(Constants.LOG, "AlarmListAdapter.getGroupView called for group postion "+groupPosition+", view="+convertView );
        Alarm alarm = (Alarm) getGroup(groupPosition);
        if (convertView == null) {
            LayoutInflater inflater = (LayoutInflater) this.context
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = inflater.inflate(R.layout.listview_group_alarm, null);

        }

        CheckedTextView lblListHeader = (CheckedTextView) convertView.findViewById(R.id.checkedTextViewAlarmSummary);
        lblListHeader.setText(alarm.toString());
        lblListHeader.setChecked(alarm.getEnabled());

        return convertView;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return false;
    }


    //
    // private members
    //
    private Context context;
    private Proxy   proxy;
}
