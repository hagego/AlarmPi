/**
 * AlarmPi Display
 * UI functions
 */

#include <SPI.h>


/*  Install the "TFT_eSPI" library by Bodmer to interface with the TFT Display - https://github.com/Bodmer/TFT_eSPI
    *** IMPORTANT: User_Setup.h available on the internet will probably NOT work with the examples available at Random Nerd Tutorials ***
    *** YOU MUST USE THE User_Setup.h FILE PROVIDED IN THE LINK BELOW IN ORDER TO USE THE EXAMPLES FROM RANDOM NERD TUTORIALS ***
    FULL INSTRUCTIONS AVAILABLE ON HOW CONFIGURE THE LIBRARY: https://RandomNerdTutorials.com/cyd/ or https://RandomNerdTutorials.com/esp32-tft/   */
#include <TFT_eSPI.h>

// XPT2046_Touchscreen library by Paul Stoffregen to use the Touchscreen - https://github.com/PaulStoffregen/XPT2046_Touchscreen
#include <XPT2046_Touchscreen.h>

#include "ui.h"
#include "mqtt.h"

// Touchscreen pins
#define XPT2046_IRQ  36   // T_IRQ
#define XPT2046_MOSI 32   // T_DIN
#define XPT2046_MISO 39   // T_OUT
#define XPT2046_CLK  25   // T_CLK
#define XPT2046_CS   33   // T_CS

#define SCREEN_WIDTH 320
#define SCREEN_HEIGHT 240

TFT_eSPI tft = TFT_eSPI();
SPIClass touchscreenSPI = SPIClass(VSPI);
XPT2046_Touchscreen touchscreen(XPT2046_CS, XPT2046_IRQ);


// initialize display and touch screen
void UI::initTouchScreen() {
      // Start the SPI for the touchscreen and init the touchscreen
  touchscreenSPI.begin(XPT2046_CLK, XPT2046_MISO, XPT2046_MOSI, XPT2046_CS);
  touchscreen.begin(touchscreenSPI);
  // Set the Touchscreen rotation in landscape mode
  // Note: in some displays, the touchscreen might be upside down, so you might need to set the rotation to 3: touchscreen.setRotation(3);
  touchscreen.setRotation(1);

  // Start the tft display
  tft.init();

  // Set the TFT display rotation in landscape mode
  tft.setRotation(1);

  // Clear the screen before writing to it
  tft.fillScreen(TFT_BLACK);
  
  // create the static labels
  tft.setTextColor(TFT_BLUE, TFT_BLACK, false);
  tft.drawString("Wecker:", xCol1, yRow1, 4);

  tft.setTextColor(TFT_BLUE, TFT_SKYBLUE, true);
  tft.fillRoundRect(xCol3+1,yRow1-4, BUTTON_WIDTH-2, BUTTON_HEIGHT-2, 3, TFT_SKYBLUE);
  tft.drawCentreString("skip",xCol3+BUTTON_WIDTH/2, yRow1, 4);
  tft.drawSmoothRoundRect(xCol3,yRow1-5,6,3,BUTTON_WIDTH,BUTTON_HEIGHT,TFT_VIOLET,TFT_VIOLET);

  tft.setTextColor(TFT_BLUE, TFT_BLACK, false);
  tft.drawString("Licht", xCol1, yRow2, 4);

  tft.setTextColor(TFT_BLUE, TFT_SKYBLUE, true);
  tft.fillRoundRect(xCol3+1,yRow2-4, BUTTON_WIDTH-2, BUTTON_HEIGHT-2, 3, TFT_SKYBLUE);
  tft.drawCentreString("an", xCol3+BUTTON_WIDTH/2, yRow2, 4);
  tft.drawSmoothRoundRect(xCol3,yRow2-5,6,3,BUTTON_WIDTH,BUTTON_HEIGHT,TFT_VIOLET,TFT_VIOLET);

  tft.setTextColor(TFT_BLUE, TFT_SKYBLUE, true);
  tft.fillRoundRect(xCol4+1,yRow2-4, BUTTON_WIDTH-2, BUTTON_HEIGHT-2, 3, TFT_SKYBLUE);
  tft.drawCentreString("aus", xCol4+BUTTON_WIDTH/2, yRow2, 4);
  tft.drawSmoothRoundRect(xCol4,yRow2-5,6,3,BUTTON_WIDTH,BUTTON_HEIGHT,TFT_VIOLET,TFT_VIOLET);
  

  tft.setTextColor(TFT_BLUE, TFT_BLACK, false);
  tft.drawString("Temp:", xCol1, yRow3, 4);

  // initialize backlight control 
  ledcSetup(PWM_CHANNEL, PWM_FREQ, PWM_RESOLUTION);
  ledcAttachPin(LCD_BACK_LIGHT_PIN, PWM_CHANNEL);

  // set initial backlight
  setBacklight(30);
}

// set backlight brightness
void UI::setBacklight(u_int8_t brightness) {
  if(brightness>100) {
    brightness = 100;
  }
  ledcWrite(PWM_CHANNEL, (u_int32_t)((double)brightness/100.0*(double)MAX_DUTY_CYCLE));
}


// handle touch screen events
void UI::handleTouchScreen() {
    if (touchscreen.tirqTouched() && touchscreen.touched()) {
    // Get Touchscreen points
    TS_Point p = touchscreen.getPoint();
    // Calibrate Touchscreen points with map function to the correct width and height
    long x = map(p.x, 200, 3700, 1, SCREEN_WIDTH);
    long y = map(p.y, 240, 3800, 1, SCREEN_HEIGHT);
    long z = p.z;

    sprintf(buffer,"touchscreen event: X = %d | Y = %d | Pressure = %d", x, y, z);
    Serial.println(buffer);

    // Check if the touch was inside the "skip" button
    if (x > xCol3-10 && x < xCol3+BUTTON_WIDTH+10 && y > yRow1-5 && y < yRow1+BUTTON_HEIGHT+5) {
      Serial.println("skip alarm button pressed");
      mqttClient.publish(mqttTopicPublishButtonSkipAlarm, "");
    }

    // Check if the touch was inside the "light on" button
    if (x > xCol3-10 && x < xCol3+BUTTON_WIDTH+10 && y > yRow2-5 && y < yRow2+BUTTON_HEIGHT+5) {
      Serial.println("light on button pressed");
      mqttClient.publish(mqttTopicPublishButtonLightOn, "");
    }

    // Check if the touch was inside the "light off" button
    if (x > xCol4-10 && x < xCol4+BUTTON_WIDTH+10 && y > yRow2-5 && y < yRow2+BUTTON_HEIGHT+5) {
      Serial.println("light off button pressed");
      mqttClient.publish(mqttTopicPublishButtonLightOff, "");
    }
  }
}

// updates time of day
void UI::displayTime(u_int8_t time_h, u_int8_t time_m) {
  sprintf(buffer,"%02d:%02d",time_h,time_m);
  tft.setTextColor(TFT_RED, TFT_BLACK);
  tft.drawCentreString(buffer, SCREEN_WIDTH/2, 20, 8);

}

// updates temperature
void UI::displayTemperature(int8_t temperature) {
  sprintf(buffer,"%d C  ",temperature);
  tft.setTextColor(TFT_BLUE, TFT_BLACK, false);
  tft.drawString(buffer,  xCol2, yRow3, 4);
}

// updates alarm time
void UI::displayAlarmTime(u_int8_t alarm_h, u_int8_t alarm_m) {
  sprintf(buffer,"%02d:%02d",alarm_h,alarm_m);
  tft.setTextColor(TFT_BLUE, TFT_BLACK, false);
  tft.drawString(buffer, xCol2, yRow1, 4);
}

// clears the alarm time display
void UI::clearAlarmTime() {
  tft.fillRect(xCol2, yRow1, xCol3-xCol2, 20, TFT_BLACK);
}

// updates waste collection display
void UI::displayWasteCollection(const char* wasteCollection) {
  tft.setTextColor(TFT_BLUE, TFT_BLACK, false);
  tft.drawString(wasteCollection, xCol3, yRow3, 4);
}

// clears the waste collection display
void UI::clearWasteCollection() {
  tft.fillRect(xCol3, yRow3, SCREEN_WIDTH-xCol3, 20, TFT_BLACK);
}