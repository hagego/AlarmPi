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

    // handle touch screen events
    void handleTouchScreen();

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

    // backlight control
    const u_int8_t  PWM_CHANNEL        = 0;    // ESP32 has 16 channels which can generate 16 independent waveforms
    const u_int32_t PWM_FREQ           = 5000;
    const u_int8_t  PWM_RESOLUTION     = 8;    // 8 bit resolution
    const u_int8_t  LCD_BACK_LIGHT_PIN = 21;

    // The max duty cycle value based on PWM resolution
    const u_int32_t MAX_DUTY_CYCLE = (u_int32_t)(pow(2, PWM_RESOLUTION) - 1); 

    // x/y coordinates to display the various labels
    // 3 rows, 4 columns
    const u_int16_t xCol1 = 10;
    const u_int16_t xCol2 = 110;
    const u_int16_t xCol3 = 180;
    const u_int16_t xCol4 = 256;

    const u_int16_t yRow1 = 120;
    const u_int16_t yRow2 = 165;
    const u_int16_t yRow3 = 210;

    const u_int16_t BUTTON_WIDTH  = 60;
    const u_int16_t BUTTON_HEIGHT = 30;

    // buffer for sprintfs
    char buffer[512];
};

#endif