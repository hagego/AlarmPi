#include "UI.hpp"

void UI::updateTime(u_int8_t time_h, u_int8_t time_m) {
    gfx->fillScreen(0); // clear the screen
    gfx->setCursor(0, 0);
    gfx->setTextColor(RGB565_BLUE);
    gfx->setTextSize(14);
    gfx->printf("%02d:%02d", time_h, time_m);
}
