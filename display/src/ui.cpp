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

  // initialize backlight control 
  ledcSetup(PWM_CHANNEL, PWM_FREQ, PWM_RESOLUTION);
  ledcAttachPin(LCD_BACK_LIGHT_PIN, PWM_CHANNEL);
}

// set backlight brightness
void UI::setBacklight(u_int8_t brightness) {
  if(brightness>100) {
    brightness = 100;
  }
  ledcWrite(PWM_CHANNEL, (u_int32_t)((double)brightness/100.0*(double)MAX_DUTY_CYCLE));
}

// crates a button
void UI::createButton(int16_t x, int16_t y, const char* label, boolean selected, u_int16_t width, u_int16_t height) {
  uint32_t colorFg = selected ? TFT_SKYBLUE : TFT_BLUE;
  uint32_t colorBg = selected ? TFT_BLUE : TFT_SKYBLUE;

  tft.setTextColor(colorFg, colorBg, true);
  tft.fillRoundRect(x+1,y-4, width-2, height-2, 3, colorBg);
  tft.drawCentreString(label,x+width/2, y, 4);
  tft.drawSmoothRoundRect(x,y-5,6,3,width,height,TFT_VIOLET,TFT_VIOLET);
}

// set the default display
void UI::setDefaultDisplay() {
  // Clear the screen before writing to it
  tft.fillScreen(TFT_BLACK);
  
  // create the static labels
  tft.setTextColor(TFT_BLUE, TFT_BLACK, false);
  tft.drawString("Wecker:", xCol1, yRow1, 4);

  tft.setTextColor(TFT_BLUE, TFT_SKYBLUE, true);
  tft.fillRoundRect(xCol3+1,yRow1-4, BUTTON_WIDTH-2, BUTTON_HEIGHT-2, 3, TFT_SKYBLUE);
  tft.drawCentreString("skip",xCol3+BUTTON_WIDTH/2, yRow1, 4);
  tft.drawSmoothRoundRect(xCol3,yRow1-5,6,3,BUTTON_WIDTH,BUTTON_HEIGHT,TFT_VIOLET,TFT_VIOLET);

  createButton(xCol4, yRow1, "set");

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
  tft.drawString("Radio", xCol1, yRow3, 4);

  tft.setTextColor(TFT_BLUE, TFT_SKYBLUE, true);
  tft.fillRoundRect(xCol3+1,yRow3-4, BUTTON_WIDTH-2, BUTTON_HEIGHT-2, 3, TFT_SKYBLUE);
  tft.drawCentreString("an", xCol3+BUTTON_WIDTH/2, yRow3, 4);
  tft.drawSmoothRoundRect(xCol3,yRow3-5,6,3,BUTTON_WIDTH,BUTTON_HEIGHT,TFT_VIOLET,TFT_VIOLET);

  tft.setTextColor(TFT_BLUE, TFT_SKYBLUE, true);
  tft.fillRoundRect(xCol4+1,yRow3-4, BUTTON_WIDTH-2, BUTTON_HEIGHT-2, 3, TFT_SKYBLUE);
  tft.drawCentreString("aus", xCol4+BUTTON_WIDTH/2, yRow3, 4);
  tft.drawSmoothRoundRect(xCol4,yRow3-5,6,3,BUTTON_WIDTH,BUTTON_HEIGHT,TFT_VIOLET,TFT_VIOLET);

  tft.setTextColor(TFT_BLUE, TFT_BLACK, false);
  tft.drawString("Temp:", xCol1, yRow4, 4);

  mode = DEFAULT_SCREEN;
}

void UI::handleTouchDefaultScreen(int16_t x, int16_t y) {
  // Check if the touch was inside the "skip" button
  if (x > xCol3-10 && x < xCol3+BUTTON_WIDTH+10 && y > yRow1-5 && y < yRow1+BUTTON_HEIGHT+5) {
    Serial.println("skip alarm button pressed");
    mqttClient.publish(mqttTopicPublishButtonSkipAlarm, "x");
  }

  // Check if the touch was inside the "set" button
  if (x > xCol4-10 && x < xCol4+BUTTON_WIDTH+10 && y > yRow1-5 && y < yRow1+BUTTON_HEIGHT+5) {
    Serial.println("set alarm button pressed");
    setAlarmTimeDisplay();
  }

  // Check if the touch was inside the "light on" button
  if (x > xCol3-10 && x < xCol3+BUTTON_WIDTH+10 && y > yRow2-5 && y < yRow2+BUTTON_HEIGHT+5) {
    Serial.println("light on button pressed");
    mqttClient.publish(mqttTopicPublishButtonLightOn, "x");
  }

  // Check if the touch was inside the "light off" button
  if (x > xCol4-10 && x < xCol4+BUTTON_WIDTH+10 && y > yRow2-5 && y < yRow2+BUTTON_HEIGHT+5) {
    Serial.println("light off button pressed");
    mqttClient.publish(mqttTopicPublishButtonLightOff, "x");
  }

  // Check if the touch was inside the "radio on" button
  if (x > xCol3-10 && x < xCol3+BUTTON_WIDTH+10 && y > yRow3-5 && y < yRow3+BUTTON_HEIGHT+5) {
    Serial.println("radio on button pressed");
    mqttClient.publish(mqttTopicPublishButtonRadioOn, "x");
  }

  // Check if the touch was inside the "radio off" button
  if (x > xCol4-10 && x < xCol4+BUTTON_WIDTH+10 && y > yRow3-5 && y < yRow3+BUTTON_HEIGHT+5) {
    Serial.println("radio off button pressed");
    mqttClient.publish(mqttTopicPublishButtonRadioOff, "x");
  }
}


// set the display to set the alarm time
void UI::setAlarmTimeDisplay() {
    mode = ALARM_TIME_SCREEN;

    // reset selection
    int16_t selectedAlarmHourCol   = -1;
    int16_t selectedAlarmHourRow   = -1;
    int16_t selectedAlarmMinuteCol = -1;
    int16_t selectedAlarmMinuteRow = -1;

    // Clear the screen before writing to it
    tft.fillScreen(TFT_BLACK);
    
    // create hour buttons
    tft.setTextColor(TFT_BLUE, TFT_BLACK, false);
    tft.drawString("Stunde:", colAlarm[0], rowAlarm[0], 4);

    for(u_int16_t y=0 ; y<rowCountAlarmHours ; y++) {
      for(u_int16_t x=0 ; x<colAlarmCount ; x++) {
        sprintf(buffer,"%d",alarmHours[y*colAlarmCount+x]);
        createButton(colAlarm[x], rowAlarm[y+1], buffer);
      }
    }

    // create minute buttons
    tft.setTextColor(TFT_BLUE, TFT_BLACK, false);
    tft.drawString("Minute:", colAlarm[0], rowAlarm[3], 4);

    for(u_int16_t y=0 ; y<rowCountAlarmMinutes ; y++) {
      for(u_int16_t x=0 ; x<colAlarmCount ; x++) {
        sprintf(buffer,"%d",alarmMinutes[y*colAlarmCount+x]);
        createButton(colAlarm[x], rowAlarm[y+4], buffer);
      }
    }

    // OK/cancel buttons
    createButton(40, rowAlarm[6], "OK", false, 100, BUTTON_HEIGHT);
    createButton(180, rowAlarm[6], "Abbruch", false, 100, BUTTON_HEIGHT);
}


void UI::handleTouchAlarmTimeScreen(int16_t x, int16_t y) {
  Serial.println("handle touchscreen event for alarm time screen");
  
  // check if the touch was inside an hour button
  for(u_int16_t row=0 ; row<rowCountAlarmHours ; row++) {
    for(u_int16_t col=0 ; col<colAlarmCount ; col++) {
      if (x > colAlarm[col]-10 && x < colAlarm[col]+BUTTON_WIDTH+10 && y > rowAlarm[row+1]-5 && y < rowAlarm[row+1]+BUTTON_HEIGHT+5) {
        // alarm hour selected. Clear old selection (if any)
        if(selectedAlarmHourCol>=0 && selectedAlarmHourRow>=0) {
          sprintf(buffer,"%d",alarmHours[selectedAlarmHourRow*colAlarmCount+selectedAlarmHourCol]);
          createButton(colAlarm[selectedAlarmHourCol], rowAlarm[selectedAlarmHourRow+1], buffer, false);
        }
        sprintf(buffer,"%d",alarmHours[row*colAlarmCount+col]);
        createButton(colAlarm[col], rowAlarm[row+1], buffer, true);
        selectedAlarmHourCol = col;
        selectedAlarmHourRow = row;

        return;
      }
    }
  }

  // check if the touch was inside a minute button
  for(u_int16_t row=0 ; row<rowCountAlarmMinutes ; row++) {
    for(u_int16_t col=0 ; col<colAlarmCount ; col++) {
      if (x > colAlarm[col]-10 && x < colAlarm[col]+BUTTON_WIDTH+10 && y > rowAlarm[row+4]-5 && y < rowAlarm[row+4]+BUTTON_HEIGHT+5) {
        // alarm minute selected. Clear old selection (if any)
        if(selectedAlarmMinuteCol>=0 && selectedAlarmMinuteRow>=0) {
          sprintf(buffer,"%d",alarmMinutes[selectedAlarmMinuteRow*colAlarmCount+selectedAlarmMinuteCol]);
          createButton(colAlarm[selectedAlarmMinuteCol], rowAlarm[selectedAlarmMinuteRow+4], buffer, false);
        }
        sprintf(buffer,"%d",alarmMinutes[row*colAlarmCount+col]);
        createButton(colAlarm[col], rowAlarm[row+4], buffer, true);
        selectedAlarmMinuteCol = col;
        selectedAlarmMinuteRow = row;

        return;
      }
    }
  }

  // check if the touch was inside the OK or cancel button
  if(y > rowAlarm[6]-5 && y < rowAlarm[6]+BUTTON_HEIGHT+5) {
    if(x>40 && x<140) {
      // OK button pressed
      Serial.println("OK button pressed");
      if(selectedAlarmHourCol>=0 && selectedAlarmHourRow>=0 && selectedAlarmMinuteCol>=0 && selectedAlarmMinuteRow>=0) {
        sprintf(buffer,"%02d:%02d",alarmHours[selectedAlarmHourRow*colAlarmCount+selectedAlarmHourCol], alarmMinutes[selectedAlarmMinuteRow*colAlarmCount+selectedAlarmMinuteCol]);
        mqttClient.publish(mqttTopicPublishSetAlarm, buffer);
        
      }
      setDefaultDisplay();
    } else if(x>180 && x<280) {
      // cancel button pressed
      Serial.println("cancel button pressed");
      setDefaultDisplay();
    }
  }
}

// handle touch screen events
bool UI::handleTouchScreen() {
  if (touchscreen.tirqTouched() && touchscreen.touched()) {
    // Get Touchscreen points
    TS_Point p = touchscreen.getPoint();
    // Calibrate Touchscreen points with map function to the correct width and height
    long x = map(p.x, 200, 3700, 1, SCREEN_WIDTH);
    long y = map(p.y, 240, 3800, 1, SCREEN_HEIGHT);
    long z = p.z;

    sprintf(buffer,"touchscreen event: X = %d | Y = %d | Pressure = %d", x, y, z);
    Serial.println(buffer);

    switch (mode) {
      case DEFAULT_SCREEN:
        handleTouchDefaultScreen(x, y);
        break;
      case ALARM_TIME_SCREEN:
        handleTouchAlarmTimeScreen(x, y);
        break;
      default:
        break;
    }
    return true;
  }

  return false;
}

// updates time of day
void UI::displayTime(u_int8_t time_h, u_int8_t time_m) {
  if(mode==DEFAULT_SCREEN) {
    sprintf(buffer,"%02d:%02d",time_h,time_m);
    tft.setTextColor(TFT_RED, TFT_BLACK);
    tft.drawCentreString(buffer, SCREEN_WIDTH/2, yRowTime, 8);
  }
}

// updates temperature
void UI::displayTemperature(int8_t temperature) {
  if(mode==DEFAULT_SCREEN) {
    sprintf(buffer,"%d C  ",temperature);
    tft.setTextColor(TFT_BLUE, TFT_BLACK, false);
    tft.drawString(buffer,  xCol2, yRow4, 4);
  }
}

// updates alarm time
void UI::displayAlarmTime(u_int8_t alarm_h, u_int8_t alarm_m) {
  if(mode==DEFAULT_SCREEN) {
    sprintf(buffer,"%02d:%02d",alarm_h,alarm_m);
    tft.setTextColor(TFT_BLUE, TFT_BLACK, false);
    tft.drawString(buffer, xCol2, yRow1, 4);
  }
}

// clears the alarm time display
void UI::clearAlarmTime() {
  if(mode==DEFAULT_SCREEN) {
    tft.fillRect(xCol2, yRow1, xCol3-xCol2, 20, TFT_BLACK);
  }
}

// updates waste collection display
void UI::displayWasteCollection(const char* wasteCollection) {
  if(mode==DEFAULT_SCREEN) {
    tft.setTextColor(TFT_BLUE, TFT_BLACK, false);
    tft.drawString(wasteCollection, xCol3, yRow4, 4);
  }
}

// clears the waste collection display
void UI::clearWasteCollection() {
  if(mode==DEFAULT_SCREEN) {
    tft.fillRect(xCol3, yRow4, SCREEN_WIDTH-xCol3, 20, TFT_BLACK);
  }
}

