package hagego.alarmpi;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;


import static android.content.Context.MODE_PRIVATE;

/**
 * this class acts as a proxy to the AlarmPi server
 * implemented as Singleton
 *
 */
class Proxy {

    /**
     * private constructor - this class implements the singleton pattern
     */
    private Proxy(Context context) {
        this.context = context;

        // get/construct connetion details
        url = null;

        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_KEY, MODE_PRIVATE);
        int active = prefs.getInt("active", -1);
        if (active == -1) {
            log.severe("No active AlarmPi set in SharedPreferences");
        }
        else {
            String addr = prefs.getString("hostname" + active, "");

            if (addr.isEmpty()) {
                log.severe("No hostname found in SharedPrefs for AlarmPi index " + active);
                return;
            }

            try {
                url = new URL("http://" + addr + ":" + Constants.JSON_PORT);

                log.info("Proxy created with URL String "+url.toString());
            } catch (MalformedURLException e) {
                log.severe("Unable to build URL to connect to AlarmPi server: " + e.getMessage());
            }
        }
    }

    /**
     * returns the singleton object
     * @return singleton object
     */
    static Proxy getProxy(Context context) {
        if(object==null) {
            object = new Proxy(context);
        }

        return object;
    }

    /**
     * Executes an HTTP POST query, returning the result as JSON object
     * @param jsonObject        JSON object to post
     * @return                  true on success, false on error
     */
    boolean executeHttpPostJsonQuery(JSONObject jsonObject) {
        log.info("executing HTTP Post query");

        if(url!=null) {
            try {
                // add POST parameter to URL
                String urlString = url.toString();
                urlString += "/"+ URLEncoder.encode(jsonObject.toString(),"UTF-8");
                log.finest("new URL: "+urlString);
                URL postUrl = new URL(urlString);


                HttpURLConnection con = (HttpURLConnection) postUrl.openConnection();
                con.setRequestMethod("POST");
                con.setRequestProperty("Content-Type", "application/json");
                con.setDoInput(true);

                log.finest("sending POST content: "+jsonObject.toString());
                OutputStream os = con.getOutputStream();
                byte[] input = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);

                BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                log.finest("received response: "+response);

                return true;
            } catch (MalformedURLException e) {
                log.severe("executeHttpPostJsonQuery: MalformedURLException: "+e.getMessage());

                return false;
            } catch (IOException e) {
                log.severe("executeHttpPostJsonQuery: IOException: " + e.getMessage());

                return false;
            }
        }
        else {
            log.severe("executeHttpPostJsonQuery: Unable to execute POST query - no valid URL");

            return false;
        }
    }

    /**
     * Updates proxy with data read from AlarmPi
     * @return Future Boolean indicating the success of the synchronization
     */
    Future<Boolean> synchronize(android.os.Handler handler) {
        return threadExecutor.submit(new Synchronize(handler));
    }

    /**
     * returns the list of alarms
     * @return alarm list
     */
    List<Alarm> getAlarmList() {
        return alarmList;
    }

    /**
     * returns the list of available sounds
     * @return list of available sounds
     */
    ArrayList<String> getSoundList() {
        return soundList;
    }

    /**
     * returns the number of lights controlled by this AlarmPi
     * @return number of lights
     */
    int getLightCount() {
        return lightCount;
    }
    /**
     * returns the  brightness in %
     * @param  lightId    light ID
     * @return brightness in percent (0 means LED is off)
     */
    int getBrightness(int lightId) {
        if(lightId<brightness.length) {
            return brightness[lightId];
        }
        else {
            return 0;
        }
    }

    /**
     * returns the actual Volume in percent (0 is off)
     * @return actual Volume
     */
    int getActiveVolume() {
        return activeVolume;
    }

    /**
     * returns the index of the actual playing Sound (or null if sound is off)
     * @return actual playing Sound (or null if sound is off)
     */
    Integer getActiveSound() {
        return activeSound;
    }

    /**
     * returns the active 'sound off' timer setting
     * @return the active 'sound off' timer setting in seconds from last query
     */
    Integer getActiveTimer() {
        return activeTimer;
    }

    /**
     * Updates an alarm on AlarmPi
     * @param alarm alarm to update
     * @return Future of Boolean with the success of the update
     */
    Future<Boolean> updateAlarm(Alarm alarm, android.os.Handler handler) {
        log.fine("updateAlarm called for alarm ID="+alarm.getId());

        return threadExecutor.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                JSONArray jsonArray   = new JSONArray();
                JSONObject jsonObject = new JSONObject();

                JSONObject jsonAlarmObject = alarm.toJasonObject();
                if(jsonAlarmObject!=null) {
                    try {
                        jsonArray.put(jsonAlarmObject);
                        jsonObject.put("alarms",jsonArray);

                        if(executeHttpPostJsonQuery(jsonObject)==true) {
                            log.fine("Alarm updates successfully: ID="+alarm.getId());
                            alarm.resetHasChanged();

                            handler.sendEmptyMessage(Constants.MESSAGE_PROXY_ALARM_UPDATED);
                            return true;
                        }
                        else {
                            handler.sendEmptyMessage(Constants.MESSAGE_PROXY_ALARM_UPDATED);
                            return false;
                        }
                    } catch (JSONException e) {
                        log.severe("updateAlarm: JSON Exception: "+e.getMessage());
                    }
                }

                handler.sendEmptyMessage(Constants.MESSAGE_PROXY_ALARM_UPDATED);
                return false;
            }
        });
    }

    /**
     * Updates the LED brightness on AlarmPi
     * @param lightId    index of light to update
     * @param brightness in percent (0 means off)
     * @return Future of Boolean with the success of the update
     */
    Future<Boolean> updateBrightness(int lightId,int brightness) {
        log.fine("updateBrightness  called for light ID="+lightId);

        return threadExecutor.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                JSONArray jsonArray   = new JSONArray();
                JSONObject jsonObject = new JSONObject();
                JSONObject jsonLightObject = new JSONObject();

                jsonLightObject.put("id",lightId+1);
                jsonLightObject.put("brightness",brightness);

                try {
                    jsonArray.put(jsonLightObject);
                    jsonObject.put("lights",jsonArray);

                     return executeHttpPostJsonQuery(jsonObject);
                } catch (JSONException e) {
                    log.severe("updateBrightness: JSON Exception: "+e.getMessage());
                }

                return false;
            }
        });
    }

    /**
     * Updates the sound volume on AlarmPi
     * @param volume in percent (0 means off)
     * @return Future of Boolean with the success of the update
     */
    Future<Boolean> updateVolume(int volume) {
        log.fine("updateVolume called, volume="+volume);
        activeVolume=volume;

        return threadExecutor.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                JSONObject jsonObject = new JSONObject();
                JSONObject jsonSoundObject = new JSONObject();

                try {
                    jsonSoundObject.put("activeVolume",volume);
                    jsonObject.put("soundStatus",jsonSoundObject);

                    return executeHttpPostJsonQuery(jsonObject);
                } catch (JSONException e) {
                    log.severe("updateVolume: JSON Exception: "+e.getMessage());
                }

                return false;
            }
        });
    }

    /**
     * plays a sound on AlarmPi
     * @param soundId Index of sound in sound list
     * @return Future of Boolean with the success of the operation
     */
    Future<Boolean> playSound(int soundId) {
        log.fine("updateSound called, soundID="+soundId);
        activeSound = soundId;

        return threadExecutor.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                JSONObject jsonObject = new JSONObject();
                JSONObject jsonSoundObject = new JSONObject();

                try {
                    jsonSoundObject.put("activeSound",soundList.get(soundId));
                    jsonObject.put("soundStatus",jsonSoundObject);

                    return executeHttpPostJsonQuery(jsonObject);
                } catch (JSONException e) {
                    log.severe("playSound: JSON Exception: "+e.getMessage());
                }

                return false;
            }
        });
    }

    /**
     * Updates the sound timer
     * @param secondsFromNow seconds from now on until sound shall be switched off
     * @return Future of Boolean with the success of the operation
     */
    Future<Boolean> updateTimer(int secondsFromNow) {
        log.fine("updatTimer called, timer="+secondsFromNow);
        //String cmd = secondsFromNow==0 ? "sound timer off" : String.format("sound timer %d", secondsFromNow);
        // TODO: implement updateTimer
        return new Future<Boolean>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public Boolean get() throws ExecutionException, InterruptedException {
                return false;
            }

            @Override
            public Boolean get(long timeout, TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
                return false;
            }
        };
    }

    //
    // private data
    //
    /**
     * nested class implementing Callable for the synchronize method
     */
    private class Synchronize implements Callable<Boolean> {

        Synchronize(android.os.Handler handler) {
            this.handler = handler;
        }

        @Override
        public Boolean call() {

            SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_KEY, MODE_PRIVATE);
            int active = prefs.getInt("active", -1);
            if (active == -1) {
                Log.e(Constants.LOG, "No active AlarmPi set in SharedPreferences");
                return false;
            }

            String addr = prefs.getString("hostname" + active, "");

            if (addr.isEmpty()) {
                Log.e(Constants.LOG, "No hostname found in SharedPrefs for AlarmPi index " + active);
                return false;
            }

            String urlString = "http://" + addr + ":" + Constants.JSON_PORT;
            Log.d(Constants.LOG, "executing HTTP GET query for URL " + urlString);

            HttpURLConnection con = null;
            try {
                URL url = new URL(urlString);
                con = (HttpURLConnection) url.openConnection();
                Log.d(Constants.LOG, "connection opened");

                con.setRequestMethod("GET");
                con.setRequestProperty("Content-Type", "application/json");
                con.setRequestProperty("Accept", "application/json");

                con.setDoInput(true);

                BufferedReader br;

                br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                Log.d(Constants.LOG, "response: " + response.toString());

                JSONObject jsonObject = new JSONObject(response.toString());

                // process sound list
                soundList.clear();
                soundName2ListIndexMap.clear();

                JSONArray jsonArray = jsonObject.getJSONArray("sounds");
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonSound = jsonArray.getJSONObject(i);
                    Log.v(Constants.LOG, "parsed sound: " + jsonSound.getString("name"));
                    soundList.add(jsonSound.getString("name"));
                    soundName2ListIndexMap.put(jsonSound.getString("name"), i);
                }
                Log.d(Constants.LOG, "processed sound list. Found " +jsonArray.length()+" sounds" );

                // process sound status
                JSONObject jsonSoundStatus = jsonObject.getJSONObject("soundStatus");
                String activeSoundName = jsonSoundStatus.getString("activeSound");
                if(activeSoundName==null || activeSoundName.isEmpty()) {
                    activeSound = -1;
                }
                else {
                    activeSound = soundName2ListIndexMap.get(activeSoundName);
                    if(activeSoundName==null) {
                        Log.e(Constants.LOG,"invalid sound name for active sound: "+activeSoundName);
                        activeSound = -1;
                    }
                }
                activeVolume = jsonSoundStatus.getInt("activeVolume");
                activeTimer  = jsonSoundStatus.getInt("activeTimer");
                Log.d(Constants.LOG, "processed sound status" );

                // process alarm list
                alarmList.clear();

                jsonArray = jsonObject.getJSONArray("alarms");
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonAlarm = jsonArray.getJSONObject(i);
                    Alarm alarm = Alarm.parseFromJsonObject(jsonAlarm,soundName2ListIndexMap);
                    if(alarm!=null) {
                        alarmList.add(alarm);
                    }
                }
                Log.d(Constants.LOG, "processed alarm list. Found " +jsonArray.length()+" alarms" );

                // process lights
                jsonArray = jsonObject.getJSONArray("lights");
                lightCount = jsonArray.length();
                brightness = new int[lightCount];
                for (int i = 0 ; i < lightCount ; i++) {
                    JSONObject jsonLight = jsonArray.getJSONObject(i);
                    brightness[i] = jsonLight.getInt("brightness");
                }
                Log.d(Constants.LOG, "processed light list. Found " +lightCount+" lights" );

            } catch (MalformedURLException e) {
                Log.e(Constants.LOG, "malformed URL Exception in synchronze: " + e.getMessage());

                return false;
            } catch (IOException e) {
                Log.e(Constants.LOG, "IOException in synchronize: " + e.getMessage());
                if (con != null) {
                    try {
                        Log.e(Constants.LOG, con.getResponseMessage());
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
                return false;
            } catch (JSONException e) {
                Log.e(Constants.LOG, "JSON exception in synchronize: " + e.getMessage());
            }


            Log.d(Constants.LOG, "sending synchonized complete message");
            handler.obtainMessage(Constants.MESSAGE_PROXY_SYNCHRONIZED).sendToTarget();

            //handler.sendEmptyMessage(Constants.MESSAGE_PROXY_SYNCHRONIZED);

            return true;
        }

        android.os.Handler handler;
    }


    //
    // private members
    //
    private final Logger   log     = Logger.getLogger( this.getClass().getSimpleName() );

    private static Proxy          object = null;         // singleton object
    private final Context         context;                      // Android application context
    private final ExecutorService threadExecutor = Executors.newSingleThreadExecutor();


    private URL                   url;

    // thread safe alarm list
    private final CopyOnWriteArrayList<Alarm> alarmList   = new CopyOnWriteArrayList<>();

    // read-only list with possible sounds (radio stations or playlists)
    private final ArrayList<String>           soundList              = new ArrayList<>();
    private final Map<String,Integer>         soundName2ListIndexMap = new HashMap<>();     // helper tp map sound names to index in array list

    private int     lightCount;                  // number of lights controlled by this AlarmPi
    private int[]   brightness;                  // LED brightness in percent
    private int     activeVolume;                // active activeVolume in percent (0 = off)
    private Integer activeSound;                 // active activeSound or null
    private Integer activeTimer;                 // active 'sound off' timer (in seconds) or null
}
