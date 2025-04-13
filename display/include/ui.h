#ifndef UI_H
#define UI_H

#include <PubSubClient.h>

/**
 * AlarmPi Display
 * UI functions
 */
class UI {
  public:

    // constructor
    UI(PubSubClient& mqttClient) : mqttClient(mqttClient) {}

    // initialize touch screen
    void initTouchScreen();

    // sets the backlight brightness in percent
    void setBacklight(u_int8_t brightness);

    // handle touch screen events. Returns true if a touch event was detected
    bool handleTouchScreen();

    // set the default display
    void setDefaultDisplay();

    // set the display to set the alarm time
    void setAlarmTimeDisplay();

    // updates time of day
    void displayTime(u_int8_t time_h, u_int8_t time_m);

    // updates temperature
    void displayTemperature(int8_t temperature);

    // updates alarm time
    void displayAlarmTime(u_int8_t alarm_h, u_int8_t alarm_m);

    // clears the alarm time display
    void clearAlarmTime();

    // updates waste collection display
    void displayWasteCollection(const char* wasteCollection);

    // clears the waste collection display
    void clearWasteCollection();

  private:

    // MQTT client
    PubSubClient& mqttClient;

    // current mode
    enum Mode {
      DEFAULT_SCREEN,
      ALARM_TIME_SCREEN
    } mode = DEFAULT_SCREEN;

    // backlight control
    const u_int8_t  PWM_CHANNEL        = 0;    // ESP32 has 16 channels which can generate 16 independent waveforms
    const u_int32_t PWM_FREQ           = 5000;
    const u_int8_t  PWM_RESOLUTION     = 8;    // 8 bit resolution
    const u_int8_t  LCD_BACK_LIGHT_PIN = 21;

    // The max duty cycle value based on PWM resolution
    const u_int32_t MAX_DUTY_CYCLE = (u_int32_t)(pow(2, PWM_RESOLUTION) - 1); 

    // x/y coordinates to display the labels in the  default screen
    // 3 rows, 4 columns
    const u_int16_t xCol1 = 10;
    const u_int16_t xCol2 = 110;
    const u_int16_t xCol3 = 180;
    const u_int16_t xCol4 = 256;

    const u_int16_t yRowTime = 5;
    const u_int16_t yRow1    = 100;
    const u_int16_t yRow2    = 135;
    const u_int16_t yRow3    = 170;
    const u_int16_t yRow4    = 205;

    static const u_int16_t BUTTON_WIDTH  = 60;
    static const u_int16_t BUTTON_HEIGHT = 30;

    // x/y coordinates to display the labels in the alarm time screen
    static const uint16_t colAlarmCount = 3;
    static const uint16_t rowCountAlarmHours   = 2;
    static const uint16_t rowCountAlarmMinutes = 2;
    const uint16_t        colAlarm[colAlarmCount] = { 35, 130, 225 };
    const uint16_t        rowAlarm[7] = { 0, 30, 65, 100, 130, 165, 210 };

    const uint16_t alarmHours[rowCountAlarmHours*colAlarmCount]     = { 4, 5, 6, 7, 8, 9 };
    const uint16_t alarmMinutes[rowCountAlarmMinutes*colAlarmCount] = { 0, 10, 20, 30, 40, 50 };

    // selected alarm hour/minute button. or -1 if none selected
    int16_t selectedAlarmHourCol   = -1;
    int16_t selectedAlarmHourRow   = -1;
    int16_t selectedAlarmMinuteCol = -1;
    int16_t selectedAlarmMinuteRow = -1;

    // creates a button
    void createButton(int16_t x, int16_t y, const char* label, boolean selected=false, uint16_t width=BUTTON_WIDTH, uint16_t height=BUTTON_HEIGHT);

    // touch handlers for the different screens
    void handleTouchDefaultScreen(int16_t x, int16_t y);
    void handleTouchAlarmTimeScreen(int16_t x, int16_t y);

    // buffer for sprintfs
    char buffer[512];
};

#endif