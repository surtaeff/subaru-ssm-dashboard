/*
 * The big panels, one block per board. A pin map goes in only when two sources agree; the sources are named on each block.
 * build-big.sh builds one firmware per block (big_board.h names it).
 *
 * SET_BRIGHT(b) takes 0..255. The Waveshare S3 boards can only switch their backlight, through the expander.
 * BOOT_PIN: the board's BOOT button for turning pages, -1 when there is none to read (on the S3 RGB boards GPIO0 carries G3).
 */
#pragma once
#include <Arduino_GFX_Library.h>

#if defined(SCREEN_WS_S3_LCD_43) || defined(SCREEN_WS_S3_LCD_5) || defined(SCREEN_WS_S3_LCD_7)
  /*
   * Waveshare ESP32-S3-Touch-LCD-4.3, -5 (the 800x480 one, not 5B), -7: ESP32-S3 N16R8, 800x480 RGB, the same wiring.
   * Pins: waveshare.com/wiki/ESP32-S3-Touch-LCD-4.3, -5, -7 («Pin connection» tables), and Espressif's board headers
   * ESP32_Display_Panel/src/board/supported/waveshare/BOARD_WAVESHARE_ESP32_S3_TOUCH_LCD_{4_3,5,7}.h, which also give the timing.
   * Expander (CH422G on I2C SDA 8, SCL 9): TP_RST EXIO1, backlight EXIO2, LCD_RST EXIO3, SD_CS EXIO4, USB_SEL EXIO5
   * (low: GPIO19/20 reach the USB port rather than CAN; on the -5 that line is not a selector and low is harmless). Its
   * commands: 0x24 mode (0x01 outputs), 0x38 the output byte (ESP32_IO_Expander esp_io_expander_ch422g.c, ESPHome ch422g.cpp).
   */
  #include <Wire.h>
  #if defined(SCREEN_WS_S3_LCD_43)
    #define SCREEN_ID "ws_s3_lcd_43"
  #elif defined(SCREEN_WS_S3_LCD_5)
    #define SCREEN_ID "ws_s3_lcd_5"
  #else
    #define SCREEN_ID "ws_s3_lcd_7"
  #endif
  #define BOOT_PIN -1
  static Arduino_ESP32RGBPanel *rgbpanel = new Arduino_ESP32RGBPanel(
      5 /* DE */, 3 /* VSYNC */, 46 /* HSYNC */, 7 /* PCLK */,
      1 /* R3 */, 2 /* R4 */, 42 /* R5 */, 41 /* R6 */, 40 /* R7 */,
      39 /* G2 */, 0 /* G3 */, 45 /* G4 */, 48 /* G5 */, 47 /* G6 */, 21 /* G7 */,
      14 /* B3 */, 38 /* B4 */, 18 /* B5 */, 17 /* B6 */, 10 /* B7 */,
      0 /* hsync_polarity */, 8 /* hsync_front_porch */, 4 /* hsync_pulse_width */, 8 /* hsync_back_porch */,
      0 /* vsync_polarity */, 8 /* vsync_front_porch */, 4 /* vsync_pulse_width */, 8 /* vsync_back_porch */,
      1 /* pclk_active_neg */, 16000000 /* prefer_speed */, false /* useBigEndian */,
      0 /* de_idle_high */, 0 /* pclk_idle_high */, 8000 /* bounce_buffer_size_px: width * 10, as Espressif's header */);
  static Arduino_RGB_Display *gfx = new Arduino_RGB_Display(800, 480, rgbpanel, 0, true);
  static inline void chOut(uint8_t v) { Wire.beginTransmission(0x38); Wire.write(v); Wire.endTransmission(); }
  static uint8_t chBits = 0;
  // outputs on; touch and panel held in reset for a moment, then everything up but USB_SEL
  #define BOARD_INIT() { Wire.begin(8, 9); \
    Wire.beginTransmission(0x24); Wire.write(0x01); Wire.endTransmission(); \
    chOut(0x10); delay(20); chBits = 0x1A; chOut(chBits); delay(20); }
  #define SET_BRIGHT(b) { chBits = (b) > 0 ? (chBits | 0x04) : (chBits & ~0x04); chOut(chBits); }

#elif defined(SCREEN_WS_P4_7B)
  /*
   * Waveshare ESP32-P4-WIFI6-Touch-LCD-7B: EK79007 1024x600 on 2-lane MIPI-DSI. The cable goes in the «USB 1.1 FS» port.
   * Pins: Waveshare's BSP (Waveshare-ESP32-components bsp/esp32_p4_wifi6_touch_lcd_7b: BSP_LCD_RST 33, BSP_LCD_BACKLIGHT 32
   * with output_invert). Timing: Espressif's esp_lcd_ek79007.h, EK79007_1024_600_PANEL_60HZ_CONFIG (52 MHz, 10/160/160,
   * 1/23/12), lanes 900 Mbps from EK79007_PANEL_BUS_DSI_2CH_CONFIG; the init list is Arduino_GFX's port of that component.
   */
  #define SCREEN_ID "ws_p4_7b"
  #define BOOT_PIN -1
  #define SCREEN_BL 32
  static Arduino_ESP32DSIPanel *dsipanel = new Arduino_ESP32DSIPanel(
      10 /* hsync_pulse_width */, 160 /* hsync_back_porch */, 160 /* hsync_front_porch */,
      1 /* vsync_pulse_width */, 23 /* vsync_back_porch */, 12 /* vsync_front_porch */,
      52000000 /* prefer_speed */, 900 /* lane_bit_rate */);
  static Arduino_DSI_Display *gfx = new Arduino_DSI_Display(1024, 600, dsipanel, 0, true,
      33 /* RST */, ek79007_init_operations, sizeof(ek79007_init_operations) / sizeof(lcd_init_cmd_t));
  #define BOARD_INIT() { ledcAttach(SCREEN_BL, 5000, 10); ledcOutputInvert(SCREEN_BL, true); }
  #define SET_BRIGHT(b) ledcWrite(SCREEN_BL, ((b) * 1023) / 255)

#elif defined(SCREEN_WS_P4_101)
  /*
   * Waveshare ESP32-P4-WIFI6-Touch-LCD-10.1: JD9365 800x1280 on 2-lane MIPI-DSI. The cable goes in the «USB OTG» port
   * (high speed); this board has no direct full-speed port, its other one is USB-to-UART, for flashing from a computer.
   * Pins: Waveshare's BSP (bsp/esp32_p4_wifi6_touch_lcd_x: BSP_LCD_RST 27, BSP_LCD_BACKLIGHT 26) and Arduino_GFX's
   * WAVESHARE_ESP32_P4_WIFI6_TOUCH_LCD_10_1 entry, the same. Timing and init list: only that Arduino_GFX entry.
   */
  #define SCREEN_ID "ws_p4_101"
  #define BOOT_PIN -1
  #define SCREEN_BL 26
  static Arduino_ESP32DSIPanel *dsipanel = new Arduino_ESP32DSIPanel(
      20 /* hsync_pulse_width */, 20 /* hsync_back_porch */, 40 /* hsync_front_porch */,
      4 /* vsync_pulse_width */, 12 /* vsync_back_porch */, 30 /* vsync_front_porch */,
      60000000 /* prefer_speed */);
  static Arduino_DSI_Display *gfx = new Arduino_DSI_Display(800, 1280, dsipanel, 0, true,
      27 /* RST */, jd9365_type2_init_operations, sizeof(jd9365_type2_init_operations) / sizeof(lcd_init_cmd_t));
  #define BOARD_INIT() { ledcAttach(SCREEN_BL, 5000, 10); }
  #define SET_BRIGHT(b) ledcWrite(SCREEN_BL, ((b) * 1023) / 255)

#else
  #error "big_board.h names no board: see big_boards.h"
#endif
