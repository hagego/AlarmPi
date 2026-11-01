#ifndef UI_HPP
#define UI_HPP

#include "Arduino_GFX_Library.h"

/**
 * AlarmPi Display
 * UI functions
 */
class UI {
  public:

    // constructor
    UI(Arduino_RGB_Display *gfx) : gfx(gfx) {}

    //    UI(PubSubClient& mqttClient) : mqttClient(mqttClient) {}


    // updates time of day
    void updateTime(u_int8_t time_h, u_int8_t time_m);

  private:
  
    Arduino_RGB_Display *gfx;
    //PubSubClient& mqttClient;
};

#endif // UI_HPP