package hagego.alarmpi;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;

import static android.content.Context.MODE_PRIVATE;

/**
 * Activity to select the AlarmPi
 */
public class SelectActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(Constants.LOG, "AlarmPi SelectActivity onCreate()");

        setContentView(R.layout.activity_select);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        listViewAlarmPies = (ListView)findViewById(R.id.listViewSelect);

        adapter = new AlarmPiListAdapter(this);
        listViewAlarmPies.setAdapter(adapter);

        // floating button to add a new AlarmPi to the list of known ones
        FloatingActionButton fabAdd    = (FloatingActionButton) findViewById(R.id.fab);
        FloatingActionButton fabDelete = (FloatingActionButton) findViewById(R.id.floatingActionButtonDelete);
        final AppCompatActivity activity = this;

        fabAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity (new Intent(activity, AddActivity.class));
            }
        });

        // floating action button to delete selected AlarmPi again
        fabDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SharedPreferences prefs = getSharedPreferences(Constants.PREFS_KEY,MODE_PRIVATE);
                int active = prefs.getInt("active",-1);
                // only delete if there is at least one left in list
                if(active>=0 && active<adapter.getCount() && adapter.getCount()>1) {
                    adapter.getItem(active).setActive(false);
                    adapter.remove(adapter.getItem(active));

                    // write new list to preferences
                    SharedPreferences.Editor editor = prefs.edit();
                    for(int i=0 ; i<adapter.getCount() ; i++) {
                        editor.putString("name" + i, adapter.getItem(i).name);
                        editor.putString("hostname" + i, adapter.getItem(i).hostname);
                    }
                    editor.putInt("count",adapter.getCount());

                    adapter.getItem(0).setActive(true);
                    editor.putInt("active",0);

                    editor.commit();
                }
            }
        });
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void onResume() {
        super.onResume();

        Log.d(Constants.LOG, "AlarmPi SelectActivity onResume()");

        // populate list of known AlarmPies
        SharedPreferences prefs = getSharedPreferences(Constants.PREFS_KEY,MODE_PRIVATE);

        ArrayList<AlarmPi> alarmPiList = new ArrayList<>(5);

        int count = prefs.getInt("count",0);
        for(int i=0 ; i<count ; i++) {
            alarmPiList.add(new AlarmPi(prefs.getString("name"+i,""),prefs.getString("hostname"+i,"")));
            Log.d(Constants.LOG, "adding AlarmPi "+prefs.getString("name"+i,""));
        }

        adapter.clear();
        adapter.addAll(alarmPiList);
        for(AlarmPi alarmPi:alarmPiList) {
            alarmPi.setActive(false);
        }

        // mark active AlarmPi as selected
        int active = prefs.getInt("active",-1);
        if(active>=0 && active<count) {
            adapter.getItem(active).setActive(true);
        }
    }

    // private members
    ListView            listViewAlarmPies;
    AlarmPiListAdapter  adapter;

    /**
     * private class to model the data of an AlarmPi
     */
    private class AlarmPi {
        AlarmPi(String name,String hostname) {
            this.name     = name;
            this.hostname = hostname;
        }

        @Override
        public String toString() {
            return name;
        }

        private void setText() {
            textView.setText(name);
        }

        private void setActive(boolean isChecked) {
            active = isChecked;
            if(radioButton!=null) {
                radioButton.setChecked(isChecked);
            }
        }

        // private members
        private String      name;
        private String      hostname;
        private boolean     active       = false;
        private TextView    textView     = null;
        private RadioButton radioButton  = null;
    }

    /**
     * private class for the ListView adapter
     */
    private class AlarmPiListAdapter extends ArrayAdapter<AlarmPi> {
        public AlarmPiListAdapter(Context context) {
            super(context,R.layout.listview_item_select, R.id.textViewSelect);

            this.context     = context;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            final View rowView = inflater.inflate(R.layout.listview_item_select, parent, false);

            final AlarmPi alarmPi = getItem(position);
            alarmPi.radioButton = (RadioButton) rowView.findViewById(R.id.radioButtonSelect);
            alarmPi.textView    = (TextView)    rowView.findViewById(R.id.textViewSelect);
            alarmPi.setText();

            alarmPi.radioButton.setChecked(alarmPi.active);

            rowView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for(int i=0 ; i<getCount() ; i++) {
                        if(i==position) {
                            getItem(i).setActive(true);

                            SharedPreferences prefs = getSharedPreferences(Constants.PREFS_KEY,MODE_PRIVATE);
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putInt("active",i);
                            editor.commit();
                        }
                        else {
                            getItem(i).setActive(false);
                        }
                    }
                }
            });

            return rowView;
        }

        // private members
        private Context            context;
    }
}
