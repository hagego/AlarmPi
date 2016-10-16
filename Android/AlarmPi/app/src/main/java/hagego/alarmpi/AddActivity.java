package hagego.alarmpi;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * activity to add a new AlarmPi
 */
public class AddActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        buttonOk         = (Button)   findViewById(R.id.buttonAddOk);
        buttonCancel     = (Button)   findViewById(R.id.buttonAddCancel);
        editTextName     = (TextView) findViewById(R.id.editTextAddName);
        editTextHostname = (TextView) findViewById(R.id.editTextAddHostname);

        buttonOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences prefs = getSharedPreferences("hagego.alarmpi",MODE_PRIVATE);
                int count = prefs.getInt("count",0);

                SharedPreferences.Editor editor = prefs.edit();

                editor.putString("name"+count,editTextName.getText().toString());
                editor.putString("hostname"+count,editTextHostname.getText().toString());
                count++;
                editor.putInt("count",count);

                editor.commit();

                finish();
            }
        });

        buttonCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });


        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    // private members
    Button   buttonOk;
    Button   buttonCancel;
    TextView editTextName;
    TextView editTextHostname;
}
