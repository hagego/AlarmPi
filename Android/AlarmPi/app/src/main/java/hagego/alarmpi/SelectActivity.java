package hagego.alarmpi;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.TextView;

import java.util.ArrayList;

public class SelectActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_select);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        listViewAlarmPies = (ListView)findViewById(R.id.listViewSelect);

        Log.d("alarmpi", "AlarmPi SelectActivity onCreate()");

        ArrayList<AlarmPi> nameList = new ArrayList<>();
        /*
        nameList.add(new AlarmPi("AlarmPi 1",""));
        nameList.add(new AlarmPi("AlarmPi 2",""));
        nameList.add(new AlarmPi("AlarmPi 3",""));
        */

        adapter = new AlarmPiListAdapter(this);

        listViewAlarmPies.setAdapter(adapter);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        final AppCompatActivity activity = this;

        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                startActivity (new Intent(activity, AddActivity.class));
            }
        });
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void onResume() {
        // populate list of known AlarmPies
        SharedPreferences prefs = getSharedPreferences("hagego.alarmpi",MODE_PRIVATE);

        ArrayList<AlarmPi> alarmPiList = new ArrayList<>(5);

        int count = prefs.getInt("count",0);
        for(int i=0 ; i<count ; i++) {
            alarmPiList.add(new AlarmPi(prefs.getString("name"+i,""),prefs.getString("hostname"+i,"")));
        }

        adapter.clear();
        adapter.addAll(alarmPiList);

        super.onResume();
    }

    // private members
    ListView            listViewAlarmPies;
    AlarmPiListAdapter  adapter;

    /**
     * private class to model the data of an Alarmpi
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

        private void setChecked(boolean isChecked) {
            radioButton.setChecked(isChecked);
        }

        // private members
        private String      name;
        private String      hostname;
        private TextView textView;
        private RadioButton radioButton;
    }

    /**
     * private class for the ListView adapter
     */
    private class AlarmPiListAdapter extends ArrayAdapter<AlarmPi> {
        public AlarmPiListAdapter(Context context) {
            super(context,R.layout.listview_select, R.id.textViewSelect);

            this.context     = context;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            final View rowView = inflater.inflate(R.layout.listview_select, parent, false);

            final AlarmPi alarmPi = getItem(position);
            alarmPi.radioButton = (RadioButton) rowView.findViewById(R.id.radioButtonSelect);
            alarmPi.textView    = (TextView)    rowView.findViewById(R.id.textViewSelect);
            alarmPi.setText();

            rowView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for(int i=0 ; i<getCount() ; i++) {
                        if(i==position) {
                            getItem(i).setChecked(true);
                        }
                        else {
                            getItem(i).setChecked(false);
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
