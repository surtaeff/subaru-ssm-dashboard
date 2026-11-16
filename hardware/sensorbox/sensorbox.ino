/*
 * SensorBox: extra sensors for the Subaru SSM dashboard (kg.forester.sg), plugged into the head unit's USB.
 *
 * The box knows nothing about the sensors: it sends what its inputs read (ADC counts, the supply voltage,
 * a thermocouple's °C, a frequency) and the app turns them into oil °C, bar, AFR with the profile picked for
 * each input. A new sensor is a setting in the app, never a reflash.
 *
 * Boards: Arduino Nano / Nano V3 and Uno (ATmega328P), Pro Micro 5 V / Leonardo / Micro (ATmega32U4), Mega 2560,
 * ESP32-S3 on its own USB (USB CDC On Boot). A Nano's USB chip (CH340, FTDI) is often the same as a KKL cable's, an
 * ESP32-S3 has the colour screen's ids: the app tells them apart by the answer to «?».
 * MAX31855: SCK and SO on the board's SPI pins (Nano/Uno D13/D12, Pro Micro D15/D14, Mega D52/D50, ESP32-S3 12/13), CS on 10.
 *
 * ESP32-S3: 3.3 V pins, so a 5 V signal comes in through a divider (100k from the signal, 150k to ground: 5 V reads
 * 3.0 V), the 5 V rail through one more on GPIO1 (the sensors' supply, for the ratio). A0..A3 are the chip's own ADC
 * (GPIO 2, 4, 5, 6); an ADS1115 on I2C (16 bits, found at start) adds A4..A7. The counts sent are the same as an
 * Arduino's: 0..1023 of the 5 V rail.
 *
 * Protocol, text lines at 115200 8N1 (\n at the end), see README.md:
 *   host → box   ?          who are you          → I SENSORBOX 2 board=promicro id=1A2B fw=5 rate=20 disp=none ch=A0,A1,A2,A3,TC0,F0
 *                R <1..50>  frames per second, kept in EEPROM
 *                S 0|1      stop / start the stream (it starts by itself after the first «?»)
 *   box → host   D t=<ms> vcc=<mV> A0=<0..1023.0> ... TC0=<°C>|TC0=!<fault> [TC1..TC3] F0=<Hz> Q=<outputs> fs=<0|1>
 *                (fault 1 open, 2 shorted to ground, 4 shorted to supply, 8 no module on that CS)
 *
 * Outputs (relays, MOSFETs, LEDs; firmware 3+, docs/knowledge/sensorbox-relays.md). The app decides when; the box
 * keeps the hardware safe on its own: minimum on/off times, a maximum on time, a lock against chatter, and when the
 * app goes silent (frozen head unit, unplugged) each output goes to its own "lost" state.
 *   C <ch> <pin> <L|H> <minOn ms> <minOff ms> <maxOn s> <lost: 0|1|K|T> [<hold s>]   set up channel 1..8 (C <ch> - : free it)
 *   O <ch> <0|1>      on / off (0 also clears a lock)        P <ch> <ms>        on for ms, then off
 *   B <ch> <on> <off> blink (LEDs, not relays)               W <ch> <0..255>    PWM duty (MOSFET, LED)
 *   H <ms>            heartbeat timeout, armed; H alone keeps alive, H 0 disarms   X   everything off now
 *   replies: K <cmd> <ch>, E <cmd> <ch> <why>, F 1 / F 0 (failsafe entered / left)
 *   Q= one char per channel: 0 off, 1 on, w waiting for a guard time, p pulse, b blink, ~ PWM, x locked, - not set up
 * At power-up every output pin is an input (an active-low opto module stays off) until the app sets it up.
 *
 * Displays (firmware 5+): an OLED 128x64 (SSD1306) or a text LCD with an I2C backpack (PCF8574), found on I2C at
 * start and named in the I line (disp=oled / disp=lcd); a 4-digit TM1637 on two pins the app names.
 *   L <row> <scale> <text>   OLED: text from page <row> (0..7) at size 1..3, the rest of the line cleared;
 *                            LCD: row 0..3, scale ignored. Russian capitals come as cp1251 0xC0..0xDF, '~' is °
 *   M <row> <text>           the text LCD's row when an OLED shares the bus (disp=oled+lcd)
 *   Y L <cols> <rows>        the LCD's size (16 2 or 20 4)
 *   Y O <64|32> <S|H>        the OLED's height and controller: S SSD1306/SSD1309, H SH1106 (1.3")
 *   Y T <clk> <dio>          a TM1637 on these pins (Y T - frees them)
 *   Y B <0..7>               TM1637 brightness
 *   Y K <cs1|-> <cs2|-> <cs3|->   thermocouples TC1..TC3 (more MAX31855 on the SPI bus, each its own CS)
 *   Y S                      look for an I2C display again (one plugged in later): answers with the I line
 *   N <text>                 TM1637: up to 4 characters, a '.' lights the dot of the one before
 *   Z                        clear every display
 *   Y C <S|7|8> <cs> <dc>    a colour screen on SPI (SCK/MOSI of the board, RES to its RST): SSD1351 128x128,
 *                            ST7789 240x240, ST7789 320x240 (Y C - forgets it)
 *   Q <x> <y> <w> <h> <c>    colour screen: a box in RGB565 (hex)
 *   T <x> <y> <s> <fg> <bg> <text>   colour screen: text by pixel, size 1..8, colours RGB565 (hex)
 *   box → host: Y R          the app was silent for 5 s (the displays said НЕТ СВЯЗИ): draw everything again
 *
 * CAN (firmware 6+, ESP32-S3 only: its TWAI controller and a TJA1051T/3 or SN65HVD230 transceiver). The I line says
 * can=twai. Classic CAN, receive only: the box never sends a frame of its own; in normal mode it only acknowledges.
 *   G O <kbit> <L|N> <tx> <rx> <s|->   open the bus: 33, 50, 62, 83, 100, 125, 250, 500, 800 or 1000 kbit/s; L listen
 *                            only (the car's bus: no ACK, no error frames, and the transceiver's S pin held high, its
 *                            transmitter off), N normal (the driver's own sensor bus, which needs someone to ACK);
 *                            TX, RX and S on free output pins (S: - when not wired)
 *   G F [X] [<id> <mask> [<id2> <mask2>]]   acceptance filter in the controller, hex; mask bits 1 must match. Nothing:
 *                            every frame. X: one filter for extended (29-bit) ids, else one or two for 11-bit ids
 *   G C                      close the bus, free its pins
 *   replies: K G O, K G F, K G C, E G O <pin|kbit|drv>
 *   box → host: frames as slcan (Lawicel) text, one a line: t<id:3><len><data>, T<id:8><len><data>, r/R remote
 *   frames; D lines carry can=<state>,<frames>,<dropped on USB>,<missed by the controller>,<rx errors>,<tx errors>
 *   (state: l listening, n normal, b bus off, r recovering, s stopped)
 */
#include <Arduino.h>
#include <EEPROM.h>
#include <SPI.h>
#include <Wire.h>
#if defined(ARDUINO_ARCH_ESP32)
#include "driver/twai.h"  // CAN: the chip's own controller (firmware 6+)
#endif

// The firmware's own version, sent as "fw=" in the I line: the app offers an update when the box runs an older one.
#define FW_VERSION 6
#define STR_(x) #x
#define STR(x) STR_(x)

// ---- what is fitted -------------------------------------------------------------------------------------------
#define USE_MAX31855 1   // K-type thermocouple (exhaust gas) board on SPI; found at start, left out of «ch» if absent
#define TC_CS 10         // its chip select

#if defined(__AVR_ATmega32U4__)
  #define BOARD "promicro"
  const uint8_t AIN[] = {A0, A1, A2, A3};
  const char *const AIN_NAME[] = {"A0", "A1", "A2", "A3"};
  #define FREQ_PIN 7       // INT6
  // native USB: D0/D1 are free; not D7 (frequency), D10 (CS), D14-D16 (SPI)
  const uint8_t OUT_PINS[] = {0, 1, 2, 3, 4, 5, 6, 8, 9};
#elif defined(__AVR_ATmega2560__)
  #define BOARD "mega"
  const uint8_t AIN[] = {A0, A1, A2, A3, A4, A5, A6, A7};
  const char *const AIN_NAME[] = {"A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7"};
  #define FREQ_PIN 2       // INT4
  const uint8_t OUT_PINS[] = {3, 4, 5, 6, 7, 8, 9, 11, 12, 22, 23, 24, 25, 26, 27, 28, 29};
#elif defined(ARDUINO_ARCH_ESP32)
  #define BOARD "esp32s3"
  #define ESP_BOX 1
  // the chip's own ADC (ADC1, it works next to Wi-Fi too) as A0..A3; an ADS1115 on the bus adds A4..A7
  const uint8_t AIN[] = {2, 4, 5, 6};
  const char *const AIN_NAME[] = {"A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7"};
  #define VSENSE_PIN 1     // the 5 V rail through the divider
  #define DIV_K 0.6f       // 150k / (100k + 150k)
  #define FREQ_PIN 14
  // not 0, 3, 45, 46 (strapping), 19/20 (USB), 26..37 (flash and PSRAM), 38/48 (the RGB LED on many boards),
  // 8/9 (I2C), 10..13 (CS, SPI), 1..7 (analog), 14 (frequency)
  const uint8_t OUT_PINS[] = {15, 16, 17, 18, 21, 39, 40, 41, 42, 47};
#elif defined(ARDUINO_AVR_UNO)
  #define BOARD "uno"
  const uint8_t AIN[] = {A0, A1, A2, A3, A4, A5};
  const char *const AIN_NAME[] = {"A0", "A1", "A2", "A3", "A4", "A5"};
  #define FREQ_PIN 2       // INT0
  // not D0/D1 (USB serial), D2 (frequency), D10-D13 (CS, SPI; D13 blinks in the bootloader)
  const uint8_t OUT_PINS[] = {3, 4, 5, 6, 7, 8, 9};
#else
  #define BOARD "nano"
  // A4/A5 stay free for I2C; A6/A7 are analog only on the Nano
  const uint8_t AIN[] = {A0, A1, A2, A3, A6, A7};
  const char *const AIN_NAME[] = {"A0", "A1", "A2", "A3", "A6", "A7"};
  #define FREQ_PIN 2       // INT0
  const uint8_t OUT_PINS[] = {3, 4, 5, 6, 7, 8, 9};
#endif
const uint8_t NOUT_PINS = sizeof(OUT_PINS);

// ---- displays -----------------------------------------------------------------------------------------------------
uint8_t oledAddr = 0, lcdAddr = 0, lcdCols = 16, lcdRows = 2;
uint8_t oledPages = 8;  // 8 for 64 rows, 4 for a 0.91" 128x32
bool oledSh = false;    // an SH1106 (most 1.3" ones)
int8_t tmClk = -1, tmDio = -1;
uint8_t tmBright = 4;
bool i2cOn = false;
// the app went quiet: old numbers must not look live
bool dispUsed = false, dispStale = false;
// a colour screen on SPI: 'S' SSD1351 128x128 OLED, '7' ST7789 240x240, '8' ST7789 320x240; its CS and DC pins
char colKind = 0;
int8_t colCs = -1, colDc = -1;
uint16_t colW = 0, colH = 0;
// FONT BEGIN (tools/sensorbox/gen_font.py)
const uint8_t FONT_N = 87;
const uint8_t FONT_CODE[] PROGMEM = {0x20,0x30,0x31,0x32,0x33,0x34,0x35,0x36,0x37,0x38,0x39,0x41,0x42,0x43,0x44,0x45,0x46,0x47,0x48,0x49,0x4A,0x4B,0x4C,0x4D,0x4E,0x4F,0x50,0x51,0x52,0x53,0x54,0x55,0x56,0x57,0x58,0x59,0x5A,0x2E,0x2C,0x3A,0x2D,0x2B,0x25,0x2F,0x7E,0x28,0x29,0x3C,0x3E,0x3D,0x21,0x3F,0x5F,0x2A,0x23,0xC0,0xC1,0xC2,0xC3,0xC4,0xC5,0xC6,0xC7,0xC8,0xC9,0xCA,0xCB,0xCC,0xCD,0xCE,0xCF,0xD0,0xD1,0xD2,0xD3,0xD4,0xD5,0xD6,0xD7,0xD8,0xD9,0xDA,0xDB,0xDC,0xDD,0xDE,0xDF};
const uint8_t FONT[] PROGMEM = {
  0x00,0x00,0x00,0x00,0x00,  //  
  0x3E,0x51,0x49,0x45,0x3E,  // 0
  0x00,0x42,0x7F,0x40,0x00,  // 1
  0x42,0x61,0x51,0x49,0x46,  // 2
  0x21,0x41,0x45,0x4B,0x31,  // 3
  0x18,0x14,0x12,0x7F,0x10,  // 4
  0x27,0x45,0x45,0x45,0x39,  // 5
  0x3C,0x4A,0x49,0x49,0x30,  // 6
  0x01,0x71,0x09,0x05,0x03,  // 7
  0x36,0x49,0x49,0x49,0x36,  // 8
  0x06,0x49,0x49,0x29,0x1E,  // 9
  0x7E,0x09,0x09,0x09,0x7E,  // A
  0x7F,0x49,0x49,0x49,0x36,  // B
  0x3E,0x41,0x41,0x41,0x22,  // C
  0x7F,0x41,0x41,0x41,0x3E,  // D
  0x7F,0x49,0x49,0x49,0x41,  // E
  0x7F,0x09,0x09,0x09,0x01,  // F
  0x3E,0x41,0x49,0x49,0x7A,  // G
  0x7F,0x08,0x08,0x08,0x7F,  // H
  0x00,0x41,0x7F,0x41,0x00,  // I
  0x20,0x40,0x41,0x3F,0x01,  // J
  0x7F,0x08,0x14,0x22,0x41,  // K
  0x7F,0x40,0x40,0x40,0x40,  // L
  0x7F,0x02,0x0C,0x02,0x7F,  // M
  0x7F,0x04,0x08,0x10,0x7F,  // N
  0x3E,0x41,0x41,0x41,0x3E,  // O
  0x7F,0x09,0x09,0x09,0x06,  // P
  0x3E,0x41,0x51,0x21,0x5E,  // Q
  0x7F,0x09,0x19,0x29,0x46,  // R
  0x46,0x49,0x49,0x49,0x31,  // S
  0x01,0x01,0x7F,0x01,0x01,  // T
  0x3F,0x40,0x40,0x40,0x3F,  // U
  0x1F,0x20,0x40,0x20,0x1F,  // V
  0x3F,0x40,0x38,0x40,0x3F,  // W
  0x63,0x14,0x08,0x14,0x63,  // X
  0x03,0x04,0x78,0x04,0x03,  // Y
  0x61,0x51,0x49,0x45,0x43,  // Z
  0x00,0x60,0x60,0x00,0x00,  // .
  0x00,0x50,0x30,0x00,0x00,  // ,
  0x00,0x36,0x36,0x00,0x00,  // :
  0x08,0x08,0x08,0x08,0x08,  // -
  0x08,0x08,0x3E,0x08,0x08,  // +
  0x23,0x13,0x08,0x64,0x62,  // %
  0x20,0x10,0x08,0x04,0x02,  // /
  0x06,0x09,0x09,0x06,0x00,  // ~
  0x00,0x1C,0x22,0x41,0x00,  // (
  0x00,0x41,0x22,0x1C,0x00,  // )
  0x08,0x14,0x22,0x41,0x00,  // <
  0x00,0x41,0x22,0x14,0x08,  // >
  0x14,0x14,0x14,0x14,0x14,  // =
  0x00,0x00,0x5F,0x00,0x00,  // !
  0x02,0x01,0x51,0x09,0x06,  // ?
  0x40,0x40,0x40,0x40,0x40,  // _
  0x14,0x08,0x3E,0x08,0x14,  // *
  0x14,0x7F,0x14,0x7F,0x14,  // #
  0x7E,0x09,0x09,0x09,0x7E,  // А
  0x7F,0x49,0x49,0x49,0x31,  // Б
  0x7F,0x49,0x49,0x49,0x36,  // В
  0x7F,0x01,0x01,0x01,0x01,  // Г
  0x60,0x3E,0x21,0x3F,0x60,  // Д
  0x7F,0x49,0x49,0x49,0x41,  // Е
  0x63,0x14,0x7F,0x14,0x63,  // Ж
  0x22,0x41,0x49,0x49,0x36,  // З
  0x7F,0x10,0x08,0x04,0x7F,  // И
  0x7C,0x21,0x12,0x09,0x7C,  // Й
  0x7F,0x08,0x14,0x22,0x41,  // К
  0x40,0x3E,0x01,0x01,0x7F,  // Л
  0x7F,0x02,0x0C,0x02,0x7F,  // М
  0x7F,0x08,0x08,0x08,0x7F,  // Н
  0x3E,0x41,0x41,0x41,0x3E,  // О
  0x7F,0x01,0x01,0x01,0x7F,  // П
  0x7F,0x09,0x09,0x09,0x06,  // Р
  0x3E,0x41,0x41,0x41,0x22,  // С
  0x01,0x01,0x7F,0x01,0x01,  // Т
  0x27,0x48,0x48,0x48,0x3F,  // У
  0x1C,0x22,0x7F,0x22,0x1C,  // Ф
  0x63,0x14,0x08,0x14,0x63,  // Х
  0x3F,0x20,0x20,0x3F,0x60,  // Ц
  0x07,0x08,0x08,0x08,0x7F,  // Ч
  0x7F,0x40,0x7C,0x40,0x7F,  // Ш
  0x3F,0x20,0x3F,0x20,0x60,  // Щ
  0x01,0x7F,0x48,0x48,0x30,  // Ъ
  0x7F,0x48,0x30,0x00,0x7F,  // Ы
  0x7F,0x48,0x48,0x48,0x30,  // Ь
  0x22,0x41,0x49,0x49,0x3E,  // Э
  0x7F,0x08,0x3E,0x41,0x3E,  // Ю
  0x46,0x29,0x19,0x09,0x7F,  // Я
};
// FONT END

// the outputs (defined up here: the IDE puts its function prototypes before the first function)
#define NCH 8
struct Out {
  int8_t pin;          // -1: not set up
  bool activeLow;
  uint32_t minOn, minOff;
  uint16_t maxOnS;
  char lost;           // '0' off, '1' on, 'K' keep, 'T' keep on for holdS then off
  uint16_t holdS;
  uint8_t mode;        // 0 level, 1 pulse, 2 blink, 3 PWM
  bool want, on, locked;
  uint8_t duty;
  uint32_t changedAt, onSince, until, blinkOn, blinkOff, blinkStart, windowStart;
  uint8_t changes;
};
Out outs[NCH];
bool hbArmed = false, failsafe = false;
uint32_t hbTimeout = 3000, lastLine = 0, failsafeAt = 0;
const uint8_t NAIN = sizeof(AIN) / sizeof(AIN[0]);
uint8_t adsAddr = 0;  // an ADS1115 on I2C (ESP32-S3 only)

#if defined(ESP_BOX)
  // EEPROM is a flash page there: written only when a byte changes
  void eeUpdate(int a, uint8_t v) { if (EEPROM.read(a) != v) { EEPROM.write(a, v); EEPROM.commit(); } }
  portMUX_TYPE fMux = portMUX_INITIALIZER_UNLOCKED;
  #define ISR_ATTR IRAM_ATTR
#else
  void eeUpdate(int a, uint8_t v) { EEPROM.update(a, v); }
  #define ISR_ATTR
#endif

#if defined(ESP_BOX)
// ---- CAN (TWAI) -------------------------------------------------------------------------------------------------
int8_t canTx = -1, canRx = -1, canS = -1;  // the transceiver's TXD, RXD and S (silent) pins while the bus is open
bool canOn = false, canListen = true;
uint16_t canKbit = 0;
uint8_t canNf = 0;                          // acceptance filters: 0 every frame, 1 or 2 (11-bit ids only)
bool canExtF = false;                       // the one filter is for extended ids
uint32_t canId[2], canMask[2];
uint32_t canFrames = 0, canDrop = 0;
uint32_t canCheckAt = 0;
#define CAN_RESERVE 384                     // room left in the USB buffer for a whole D line
#endif

// ---- EEPROM: a magic byte, the box id (the app keeps the profiles per box), the rate -------------------------
const uint8_t EE_MAGIC = 0x5B;
uint16_t boxId;
uint8_t rateHz = 20;

bool streaming = false;
bool tcPresent = false;
// up to three more MAX31855 (an EGT per cylinder): each its own CS on a free output pin, set by the app (Y K)
int8_t tcCs[4] = {-1, -1, -1, -1};  // [0] unused: TC0 is on TC_CS

// ---- frequency input: the period between rising edges, averaged over a frame ----------------------------------
volatile uint32_t fLast = 0, fSum = 0;
volatile uint16_t fN = 0;
float fHz = 0;
uint32_t fSeenMs = 0;

void ISR_ATTR onEdge() {
  uint32_t t = micros();
  uint32_t d = t - fLast;
  fLast = t;
  if (d > 1000000UL) return;  // the first edge after a pause
  fSum += d;
  fN++;
}

// ---- supply voltage: the 1.1 V bandgap against AVcc. Only for sensors with their own reference (a wideband
// controller's 0–5 V output); a sender fed from the box's own 5 V is read as a ratio and needs no voltage at all.
#if defined(ESP_BOX)
// the 5 V rail, through the divider on VSENSE_PIN; not wired (reads nothing like 5 V): taken as 5.00 V
long readVccMv() {
  uint32_t s = 0;
  for (uint8_t i = 0; i < 8; i++) s += analogReadMilliVolts(VSENSE_PIN);
  long mv = lround(s / 8.0f / DIV_K);
  return (mv < 3500 || mv > 6000) ? 5000 : mv;
}

// ADS1115: one single-shot conversion of AINx against ground at ±4.096 V, 860 a second; millivolts at the pin
float adsMv(uint8_t ch) {
  uint16_t cfg = 0x8000 | ((4 + ch) << 12) | (1 << 9) | (1 << 8) | (7 << 5) | 3;
  Wire.beginTransmission(adsAddr); Wire.write(1); Wire.write(cfg >> 8); Wire.write(cfg & 0xFF);
  if (Wire.endTransmission() != 0) return 0;
  delayMicroseconds(1300);
  for (uint8_t i = 0; i < 10; i++) {  // the OS bit comes back when the conversion is done
    Wire.requestFrom(adsAddr, (uint8_t)2);
    if (Wire.available() == 2 && (Wire.read() & 0x80)) { Wire.read(); break; }
    delayMicroseconds(200);
  }
  Wire.beginTransmission(adsAddr); Wire.write(0); Wire.endTransmission();
  Wire.requestFrom(adsAddr, (uint8_t)2);
  if (Wire.available() != 2) return 0;
  int16_t raw = (Wire.read() << 8) | Wire.read();
  return raw * 0.125f;
}

void findAds() {
  for (uint8_t a = 0x48; a <= 0x4B; a++) { Wire.beginTransmission(a); if (Wire.endTransmission() == 0) { adsAddr = a; return; } }
}

// the analog inputs as named in the I line: the chip's own A0..A3, then the ADS1115's A4..A7 when it is there
// (the names stay put, so a sensor set up on one keeps its input when the module comes or goes)
uint8_t nCh() { return NAIN + (adsAddr ? 4 : 0); }
const char *chName(uint8_t i) { return AIN_NAME[i]; }
bool chSkip(uint8_t i) { return false; }

// a reading as an Arduino gives it: 0..1023 of the 5 V rail ([vcc] mV), the divider taken out
float chRead(uint8_t i, long vcc) {
  float mv;
  if (i >= NAIN) mv = (adsMv(i - NAIN) + adsMv(i - NAIN)) / 2;
  else {
    uint8_t pin = AIN[i];
    uint32_t s = 0;
    for (uint8_t k = 0; k < 16; k++) s += analogReadMilliVolts(pin);
    mv = s / 16.0f;
  }
  float r = mv / DIV_K / vcc * 1023.0f;
  return r < 0 ? 0 : r > 1023 ? 1023 : r;
}
#else
long readVccMv() {
#if defined(__AVR_ATmega32U4__) || defined(__AVR_ATmega2560__)
  ADCSRB &= ~_BV(MUX5);
  ADMUX = _BV(REFS0) | _BV(MUX4) | _BV(MUX3) | _BV(MUX2) | _BV(MUX1);
#else
  ADMUX = _BV(REFS0) | _BV(MUX3) | _BV(MUX2) | _BV(MUX1);
#endif
  delay(2);  // the bandgap settles
  ADCSRA |= _BV(ADSC);
  while (bit_is_set(ADCSRA, ADSC)) {}
  ADCSRA |= _BV(ADSC);  // the first conversion after a mux change is off: take the second
  while (bit_is_set(ADCSRA, ADSC)) {}
  long r = ADC;
  return r ? 1125300L / r : 0;  // 1.1 V * 1023 * 1000
}

// 16 reads averaged: a tenth of a count, and the ignition noise smoothed
float readAin(uint8_t pin) {
  analogRead(pin);  // the mux and sample cap settle on the new channel
  uint16_t s = 0;
  for (uint8_t i = 0; i < 16; i++) s += analogRead(pin);
  return s / 16.0f;
}

uint8_t nCh() { return NAIN; }
const char *chName(uint8_t i) { return AIN_NAME[i]; }
bool chSkip(uint8_t i) { return i2cOn && (AIN[i] == SDA || AIN[i] == SCL); }  // the display's pins (the Uno's A4/A5)
float chRead(uint8_t i, long) { return readAin(AIN[i]); }
#endif

#if USE_MAX31855
uint32_t tcRaw(uint8_t cs = TC_CS) {
  SPI.beginTransaction(SPISettings(4000000, MSBFIRST, SPI_MODE0));
  digitalWrite(cs, LOW);
  delayMicroseconds(1);
  uint32_t v = 0;
  for (uint8_t i = 0; i < 4; i++) v = (v << 8) | SPI.transfer(0);
  digitalWrite(cs, HIGH);
  SPI.endTransaction();
  return v;
}
#endif

// ---- outputs ----------------------------------------------------------------------------------------------------

void writePin(Out &o, bool on) {
  digitalWrite(o.pin, (on != o.activeLow) ? HIGH : LOW);
}

bool pinAllowed(int p) {
  // a display on I2C takes its pins (the Pro Micro's D2/D3), a TM1637 its two
  if (i2cOn && (p == SDA || p == SCL)) return false;
  if (p == tmClk || p == tmDio) return false;
  if (p == colCs || p == colDc) return false;
  for (uint8_t i = 1; i < 4; i++) if (p == tcCs[i]) return false;
#if defined(ESP_BOX)
  if (p == canTx || p == canRx || p == canS) return false;
#endif
  for (uint8_t i = 0; i < NOUT_PINS; i++) if (OUT_PINS[i] == p) return true;
  return false;
}

// ---- SSD1306 OLED, 128x64, text straight into the controller's memory (no frame buffer: the Nano has 2 KB) ----
void oledCmd(uint8_t c) { Wire.beginTransmission(oledAddr); Wire.write(0x00); Wire.write(c); Wire.endTransmission(); }

/**
 * SSD1306 (0.96" 128x64, 0.91" 128x32), SSD1309 (2.42") and SH1106 (1.3", 132 columns of which 2..129 show): all
 * written page by page, which every one of them knows. Which it is and how tall the app says (Y O).
 */
void oledInit() {
  static const uint8_t init[] PROGMEM = {0xAE, 0xD5, 0x80, 0xD3, 0x00, 0x40, 0x8D, 0x14, 0x20, 0x02, 0xA1, 0xC8,
                                          0x81, 0xCF, 0xD9, 0xF1, 0xDB, 0x40, 0xA4, 0xA6};
  for (uint8_t i = 0; i < sizeof(init); i++) oledCmd(pgm_read_byte(&init[i]));
  oledCmd(0xA8); oledCmd(oledPages * 8 - 1);  // multiplex: 64 or 32 rows
  oledCmd(0xDA); oledCmd(oledPages == 8 ? 0x12 : 0x02);
  if (oledSh) { oledCmd(0xAD); oledCmd(0x8B); }  // the SH1106's own DC-DC
  oledCmd(0xAF);
}

/** The 5 columns of [c], or of '?' when the font hasn't got it. */
void glyph(uint8_t c, uint8_t out[5]) {
  if (c >= 'a' && c <= 'z') c -= 32;
  if (c >= 0xE0) c -= 0x20;  // cp1251 lowercase to capitals
  int16_t k = -1, q = -1;
  for (uint8_t i = 0; i < FONT_N; i++) {
    uint8_t fc = pgm_read_byte(&FONT_CODE[i]);
    if (fc == c) { k = i; break; }
    if (fc == '?') q = i;
  }
  if (k < 0) k = q;
  for (uint8_t x = 0; x < 5; x++) out[x] = pgm_read_byte(&FONT[k * 5 + x]);
}

/** [text] from page [row] at [scale] (1..3), across the whole width so what was there before goes. */
void oledLine(uint8_t row, uint8_t scale, const char *text) {
  scale = constrain(scale, 1, 3);
  if (row + scale > oledPages) return;
  uint8_t cw = 6 * scale;
  uint8_t chars = 128 / cw;
  for (uint8_t page = 0; page < scale; page++) {
    uint8_t col = 0, c0 = oledSh ? 2 : 0;
    oledCmd(0xB0 | (row + page)); oledCmd(c0 & 0x0F); oledCmd(0x10 | (c0 >> 4));
    Wire.beginTransmission(oledAddr); Wire.write(0x40);
    uint8_t inTx = 0;
    bool end = false;
    for (uint8_t i = 0; i < chars; i++) {
      uint8_t g[5] = {0, 0, 0, 0, 0};
      if (!text[i]) end = true;  // past the end: blanks
      if (!end) glyph((uint8_t)text[i], g);
      for (uint8_t x = 0; x < 6; x++) {
        uint8_t b = x < 5 ? g[x] : 0, o = 0;
        // the glyph's rows stretched over [scale] pages: output bit r of this page shows source row (8*page+r)/scale
        for (uint8_t r = 0; r < 8; r++) { uint8_t src = (8 * page + r) / scale; if (src < 8 && (b >> src & 1)) o |= 1 << r; }
        for (uint8_t k = 0; k < scale; k++) {
          Wire.write(o); col++;
          if (++inTx == 16) { Wire.endTransmission(); Wire.beginTransmission(oledAddr); Wire.write(0x40); inTx = 0; }
        }
      }
    }
    while (col < 128) {
      Wire.write(0); col++;
      if (++inTx == 16) { Wire.endTransmission(); Wire.beginTransmission(oledAddr); Wire.write(0x40); inTx = 0; }
    }
    Wire.endTransmission();
  }
}

void oledClear() { for (uint8_t p = 0; p < oledPages; p++) oledLine(p, 1, ""); }

// ---- HD44780 LCD behind a PCF8574 (P0 RS, P1 RW, P2 E, P3 backlight, P4..P7 data) ----
void lcdWrite4(uint8_t v) {
  Wire.beginTransmission(lcdAddr); Wire.write(v | 0x08 | 0x04); Wire.endTransmission();
  delayMicroseconds(1);
  Wire.beginTransmission(lcdAddr); Wire.write((v | 0x08) & ~0x04); Wire.endTransmission();
  delayMicroseconds(50);
}

void lcdSend(uint8_t v, uint8_t rs) { lcdWrite4((v & 0xF0) | rs); lcdWrite4((v << 4) | rs); }

void lcdInit() {
  delay(50);
  lcdWrite4(0x30); delay(5); lcdWrite4(0x30); delayMicroseconds(150); lcdWrite4(0x30); lcdWrite4(0x20);
  lcdSend(0x28, 0); lcdSend(0x0C, 0); lcdSend(0x01, 0); delay(2); lcdSend(0x06, 0);
}

void lcdLine(uint8_t row, const char *text) {
  static const uint8_t off[] = {0x00, 0x40, 0x14, 0x54};
  if (row >= lcdRows) return;
  lcdSend(0x80 | off[row], 0);
  bool end = false;
  for (uint8_t i = 0; i < lcdCols; i++) {
    if (!text[i]) end = true;
    lcdSend(end ? ' ' : (uint8_t)text[i], 1);
  }
}

// ---- TM1637, 4 digits on two pins ----
void tmStart() { pinMode(tmDio, OUTPUT); digitalWrite(tmDio, LOW); delayMicroseconds(5); }
void tmStop() { pinMode(tmDio, OUTPUT); digitalWrite(tmDio, LOW); delayMicroseconds(5); pinMode(tmClk, INPUT); delayMicroseconds(5); pinMode(tmDio, INPUT); }
void tmByte(uint8_t b) {
  // open drain: LOW drives, INPUT lets the module's pull-up raise the line
  for (uint8_t i = 0; i < 8; i++) {
    pinMode(tmClk, OUTPUT); delayMicroseconds(5);
    if (b & 1) pinMode(tmDio, INPUT); else pinMode(tmDio, OUTPUT);
    delayMicroseconds(5);
    pinMode(tmClk, INPUT); delayMicroseconds(5);
    b >>= 1;
  }
  pinMode(tmClk, OUTPUT); pinMode(tmDio, INPUT); delayMicroseconds(5);  // the ACK
  pinMode(tmClk, INPUT); delayMicroseconds(5); pinMode(tmClk, OUTPUT);
}

uint8_t tmSeg(char c) {
  static const uint8_t digits[] = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};
  if (c >= '0' && c <= '9') return digits[c - '0'];
  switch (c) {
    case '-': return 0x40; case '_': return 0x08; case '~': return 0x63;  // degree
    case 'A': case 'a': return 0x77; case 'B': case 'b': return 0x7C; case 'C': return 0x39; case 'c': return 0x58;
    case 'D': case 'd': return 0x5E; case 'E': case 'e': return 0x79; case 'F': case 'f': return 0x71; case 'H': return 0x76;
    case 'h': return 0x74; case 'L': case 'l': return 0x38; case 'n': return 0x54; case 'O': return 0x3F; case 'o': return 0x5C;
    case 'P': case 'p': return 0x73; case 'r': return 0x50; case 't': return 0x78; case 'U': return 0x3E; case 'u': return 0x1C;
    default: return 0;
  }
}

void tmShow(const char *t) {
  uint8_t seg[4] = {0, 0, 0, 0}, n = 0;
  for (uint8_t i = 0; t[i] && n < 4; i++) {
    if (t[i] == '.' && n > 0) { seg[n - 1] |= 0x80; continue; }
    seg[n++] = tmSeg(t[i]);
  }
  if (t[0] && t[strlen(t) - 1] == '.' && n == 4) seg[3] |= 0x80;
  tmStart(); tmByte(0x40); tmStop();
  tmStart(); tmByte(0xC0); for (uint8_t i = 0; i < 4; i++) tmByte(seg[i]); tmStop();
  tmStart(); tmByte(0x88 | tmBright); tmStop();
}

// ---- a colour screen on SPI (shares SCK with the MAX31855, its own CS): the app places text and boxes by pixel ----
void colBegin() { SPI.beginTransaction(SPISettings(8000000, MSBFIRST, SPI_MODE0)); digitalWrite(colCs, LOW); }
void colEnd() { digitalWrite(colCs, HIGH); SPI.endTransaction(); }
void colCmd(uint8_t c) { digitalWrite(colDc, LOW); SPI.transfer(c); digitalWrite(colDc, HIGH); }

/** The drawing window [x0..x1] x [y0..y1]; pixels follow as RGB565, high byte first. Call inside colBegin(). */
void colWindow(uint16_t x0, uint16_t y0, uint16_t x1, uint16_t y1) {
  if (colKind == 'S') {
    colCmd(0x15); SPI.transfer(x0); SPI.transfer(x1);
    colCmd(0x75); SPI.transfer(y0); SPI.transfer(y1);
    colCmd(0x5C);
  } else {
    colCmd(0x2A); SPI.transfer(x0 >> 8); SPI.transfer(x0); SPI.transfer(x1 >> 8); SPI.transfer(x1);
    colCmd(0x2B); SPI.transfer(y0 >> 8); SPI.transfer(y0); SPI.transfer(y1 >> 8); SPI.transfer(y1);
    colCmd(0x2C);
  }
}

void colFill(int16_t x, int16_t y, int16_t w, int16_t h, uint16_t c) {
  if (!colKind || x >= (int16_t)colW || y >= (int16_t)colH || w <= 0 || h <= 0) return;
  if (x + w > (int16_t)colW) w = colW - x;
  if (y + h > (int16_t)colH) h = colH - y;
  colBegin();
  colWindow(x, y, x + w - 1, y + h - 1);
  for (uint32_t n = (uint32_t)w * h; n; n--) { SPI.transfer(c >> 8); SPI.transfer(c); }
  colEnd();
}

/** [text] from (x, y) in the box's font, [scale] times larger, [fg] on [bg]; a cell is 6x8 pixels before scaling. */
void colText(int16_t x, int16_t y, uint8_t scale, uint16_t fg, uint16_t bg, const char *text) {
  scale = constrain(scale, 1, 8);
  uint8_t cw = 6 * scale, ch = 8 * scale;
  for (; *text && x + cw <= (int16_t)colW; text++, x += cw) {
    uint8_t g[5];
    glyph((uint8_t)*text, g);
    if (*text == ' ') memset(g, 0, 5);
    colBegin();
    colWindow(x, y, x + cw - 1, y + ch - 1);
    for (uint8_t r = 0; r < ch; r++) {
      uint8_t bit = r / scale;
      for (uint8_t cx = 0; cx < cw; cx++) {
        uint8_t col = cx / scale;
        uint16_t c = (col < 5 && (g[col] >> bit & 1)) ? fg : bg;
        SPI.transfer(c >> 8); SPI.transfer(c);
      }
    }
    colEnd();
  }
}

void colInit() {
  pinMode(colCs, OUTPUT); digitalWrite(colCs, HIGH);
  pinMode(colDc, OUTPUT); digitalWrite(colDc, HIGH);
  SPI.begin();
  colBegin();
  if (colKind == 'S') {
    static const uint8_t init[] PROGMEM = {  // command, count, parameters (Adafruit SSD1351 sequence)
      0xFD, 1, 0x12, 0xFD, 1, 0xB1, 0xAE, 0, 0xB3, 1, 0xF1, 0xCA, 1, 0x7F, 0xA2, 1, 0x00, 0xA1, 1, 0x00,
      0xA0, 1, 0x74, 0xB5, 1, 0x00, 0xAB, 1, 0x01, 0xB1, 1, 0x32, 0xBE, 1, 0x05, 0xA6, 0, 0xC1, 3, 0xC8, 0x80, 0xC8,
      0xC7, 1, 0x0F, 0xB4, 3, 0xA0, 0xB5, 0x55, 0xB6, 1, 0x01, 0xAF, 0};
    for (uint8_t i = 0; i < sizeof(init);) {
      colCmd(pgm_read_byte(&init[i++]));
      for (uint8_t n = pgm_read_byte(&init[i++]); n; n--) SPI.transfer(pgm_read_byte(&init[i++]));
    }
  } else {
    colCmd(0x01); colEnd(); delay(150); colBegin();  // software reset: RES may sit on the board's own RST
    colCmd(0x11); colEnd(); delay(120); colBegin();  // out of sleep
    colCmd(0x3A); SPI.transfer(0x55);                // 16 bits a pixel
    colCmd(0x36); SPI.transfer(colKind == '8' ? 0x60 : 0x00);  // 320x240 lies on its side
    colCmd(0x21);                                    // IPS panels want inverted colours
    colCmd(0x13); colCmd(0x29);
  }
  colEnd();
  colFill(0, 0, colW, colH, 0);
}

/** What answers on I2C at the usual addresses: an OLED (0x3C/0x3D) or an LCD backpack (0x27/0x3F, 0x20/0x38). */
void findDisplays() {
  Wire.begin();
#if defined(ESP_BOX)
  Wire.setTimeOut(20);
  if (!adsAddr) findAds();
#else
  Wire.setWireTimeout(3000, true);  // a sensor holding the line low must not hang the box
#endif
  static const uint8_t oled[] = {0x3C, 0x3D}, lcd[] = {0x27, 0x3F, 0x20, 0x38};
  for (uint8_t a : oled) { Wire.beginTransmission(a); if (Wire.endTransmission() == 0) { oledAddr = a; break; } }
  // an OLED and a text LCD can share the bus: their addresses differ
  for (uint8_t a : lcd) { Wire.beginTransmission(a); if (Wire.endTransmission() == 0) { lcdAddr = a; break; } }
  if (oledAddr) { oledInit(); oledClear(); }
  if (lcdAddr) lcdInit();
  // nothing there: the pins go back to the inputs (the Uno's A4/A5); the ESP32's ADS1115 keeps the bus
  if (!oledAddr && !lcdAddr) { if (!adsAddr) Wire.end(); return; }
  i2cOn = true;
}

/** No line from the app for 5 s: every display says so instead of showing numbers that no longer change. */
void displaysTick(uint32_t now) {
  if (!dispUsed || dispStale || now - lastLine < 5000) return;
  dispStale = true;
  if (oledAddr) { oledClear(); oledLine(oledPages == 4 ? 1 : 3, 2, "\xCD\xC5\xD2 \xD1\xC2\xDF\xC7\xC8"); }  // НЕТ СВЯЗИ
  if (lcdAddr) { lcdSend(0x01, 0); delay(2); lcdLine(0, "NO LINK"); }
  if (tmClk >= 0) tmShow("----");
  if (colKind) { colFill(0, 0, colW, colH, 0); colText(colW / 2 - 54, colH / 2 - 8, 2, 0xF800, 0, "\xCD\xC5\xD2 \xD1\xC2\xDF\xC7\xC8"); }
}

void handleDisplay(char *s) {
  char cmd = s[0];
  if (cmd == 'Z') {
    if (oledAddr) oledClear();
    if (lcdAddr) { lcdSend(0x01, 0); delay(2); }
    if (tmClk >= 0) tmShow("");
    if (colKind) colFill(0, 0, colW, colH, 0);
    return;
  }
  if (cmd == 'Q' || cmd == 'T') {
    // Q x y w h c: a box; T x y scale fg bg text: text by pixel (colours as RGB565 in hex)
    dispUsed = true;
    char *p = s + 1;
    int16_t x = strtol(p, &p, 10), y = strtol(p, &p, 10);
    if (cmd == 'Q') { int16_t w = strtol(p, &p, 10), h = strtol(p, &p, 10); colFill(x, y, w, h, strtoul(p, &p, 16)); return; }
    uint8_t sc = strtol(p, &p, 10);
    uint16_t fg = strtoul(p, &p, 16), bg = strtoul(p, &p, 16);
    if (*p == ' ') p++;
    colText(x, y, sc, fg, bg, p);
    return;
  }
  if (cmd == 'N') { dispUsed = true; if (tmClk >= 0) tmShow(s[1] ? s + 2 : ""); return; }
  if (cmd == 'L') {
    dispUsed = true;
    char *p = s + 1;
    uint8_t row = strtol(p, &p, 10), scale = strtol(p, &p, 10);
    if (*p == ' ') p++;
    if (oledAddr) oledLine(row, scale, p);
    else if (lcdAddr) lcdLine(row, p);  // firmware 5 apps send the LCD's lines as L too
    return;
  }
  if (cmd == 'M') {
    // M <row> <text>: the text LCD, when an OLED is on the bus as well
    dispUsed = true;
    char *p = s + 1;
    uint8_t row = strtol(p, &p, 10);
    if (*p == ' ') p++;
    if (lcdAddr) lcdLine(row, p);
    return;
  }
  // Y: set-up
  char *t = strtok(s + 1, " ");
  if (!t) return;
  if (t[0] == 'O') {
    // Y O <64|32> <S|H>: the OLED's height and controller (SSD1306/SSD1309 or SH1106)
    char *h = strtok(NULL, " "), *k = strtok(NULL, " ");
    oledPages = (h && atoi(h) == 32) ? 4 : 8;
    oledSh = k && (k[0] == 'H' || k[0] == 'h');
    if (oledAddr) { oledInit(); oledClear(); }
  } else if (t[0] == 'L') {
    char *c = strtok(NULL, " "), *r = strtok(NULL, " ");
    lcdCols = constrain(c ? atoi(c) : 16, 8, 20); lcdRows = constrain(r ? atoi(r) : 2, 1, 4);
    if (lcdAddr) { lcdSend(0x01, 0); delay(2); }
  } else if (t[0] == 'T') {
    char *c = strtok(NULL, " "), *d = strtok(NULL, " ");
    if (c && c[0] == '-') { tmClk = tmDio = -1; return; }
    int ck = c ? atoi(c + ((c[0] == 'D' || c[0] == 'd') ? 1 : 0)) : -1, dt = d ? atoi(d + ((d[0] == 'D' || d[0] == 'd') ? 1 : 0)) : -1;
    tmClk = tmDio = -1;  // checked as free outputs, not as its own pins
    for (uint8_t i = 0; i < NCH; i++) if (outs[i].pin == ck || outs[i].pin == dt) { Serial.println(F("E Y T busy")); return; }
    if (!pinAllowed(ck) || !pinAllowed(dt) || ck == dt) { Serial.println(F("E Y T pin")); return; }
    tmClk = ck; tmDio = dt;
    pinMode(tmClk, INPUT); pinMode(tmDio, INPUT);
    digitalWrite(tmClk, LOW); digitalWrite(tmDio, LOW);
    tmShow("----");
    Serial.println(F("K Y T"));
  } else if (t[0] == 'B') {
    char *b = strtok(NULL, " ");
    tmBright = constrain(b ? atoi(b) : 4, 0, 7);
  } else if (t[0] == 'C') {
    // Y C <S|7|8> <cs> <dc> / Y C -: a colour screen on SPI
    char *k = strtok(NULL, " "), *c = strtok(NULL, " "), *d = strtok(NULL, " ");
    colKind = 0; colCs = colDc = -1;
    if (!k || k[0] == '-') return;
    int cs = c ? atoi(c + ((c[0] == 'D' || c[0] == 'd') ? 1 : 0)) : -1, dc = d ? atoi(d + ((d[0] == 'D' || d[0] == 'd') ? 1 : 0)) : -1;
    for (uint8_t i = 0; i < NCH; i++) if (outs[i].pin == cs || outs[i].pin == dc) { Serial.println(F("E Y C busy")); return; }
    if (!pinAllowed(cs) || !pinAllowed(dc) || cs == dc) { Serial.println(F("E Y C pin")); return; }
    colKind = k[0]; colCs = cs; colDc = dc;
    colW = colKind == 'S' ? 128 : colKind == '8' ? 320 : 240;
    colH = colKind == 'S' ? 128 : 240;
    colInit();
    Serial.println(F("K Y C"));
  } else if (t[0] == 'K') {
#if USE_MAX31855
    // Y K <cs1|-> <cs2|-> <cs3|->: the CS pins of thermocouples TC1..TC3
    for (uint8_t i = 1; i < 4; i++) tcCs[i] = -1;
    for (uint8_t i = 1; i < 4; i++) {
      char *c = strtok(NULL, " ");
      if (!c || c[0] == '-') continue;
      int p = atoi(c + ((c[0] == 'D' || c[0] == 'd') ? 1 : 0));
      bool busy = false;
      for (uint8_t k = 0; k < NCH; k++) if (outs[k].pin == p) busy = true;
      if (busy || !pinAllowed(p)) { Serial.println(F("E Y K pin")); continue; }
      tcCs[i] = p;
      pinMode(p, OUTPUT);
      digitalWrite(p, HIGH);
    }
    Serial.println(F("K Y K"));
#endif
  } else if (t[0] == 'S') {
    // look again: a display plugged in after the start, without pulling the USB
    oledAddr = lcdAddr = 0;  // both looked for again: one may have come, the other gone
    i2cOn = false;
    findDisplays();
    printInfo();
  }
}

void reply(char k, char cmd, uint8_t ch, const __FlashStringHelper *why) {
  Serial.print(k); Serial.print(' '); Serial.print(cmd); Serial.print(' '); Serial.print(ch);
  if (why) { Serial.print(' '); Serial.print(why); }
  Serial.println();
}

/** Switches a level/pulse output, minding its guards; true if it changed. */
bool drive(Out &o, uint8_t ch, bool target, uint32_t now) {
  if (target == o.on) return false;
  if (o.on && now - o.changedAt < o.minOn) return false;
  if (!o.on && now - o.changedAt < o.minOff) return false;
  if (now - o.windowStart > 60000UL) { o.windowStart = now; o.changes = 0; }
  if (++o.changes > 30) {  // a relay must not chatter: off and locked until the app says O ch 0
    o.locked = true; o.on = false; writePin(o, false);
    reply('E', 'O', ch + 1, F("chatter"));
    return true;
  }
  o.on = target; o.changedAt = now;
  if (target) o.onSince = now;
  writePin(o, target);
  return true;
}

void outputsTick(uint32_t now) {
  // the app went silent: every output to its own "lost" state
  if (hbArmed && !failsafe && now - lastLine > hbTimeout) {
    failsafe = true; failsafeAt = now;
    for (uint8_t i = 0; i < NCH; i++) {
      Out &o = outs[i];
      if (o.pin < 0) continue;
      if (o.lost == 'K') continue;
      // a PWM output leaves its duty: off first, so "on" really drives the pin fully
      if (o.mode == 3 || o.mode == 2) { writePin(o, false); o.on = false; o.duty = 0; o.changedAt = now - 600000UL; }
      o.mode = 0;
      o.want = o.lost == '1' || (o.lost == 'T' && o.on);
    }
    Serial.println(F("F 1"));
  }
  for (uint8_t i = 0; i < NCH; i++) {
    Out &o = outs[i];
    if (o.pin < 0) continue;
    if (failsafe && o.lost == 'T' && o.want && now - failsafeAt > (uint32_t)o.holdS * 1000UL) o.want = false;
    if (o.locked) { if (o.on) { o.on = false; writePin(o, false); } continue; }
    if (o.mode == 2) {  // blink: LEDs, no guard times
      bool b = ((now - o.blinkStart) % (o.blinkOn + o.blinkOff)) < o.blinkOn;
      if (b != o.on) { o.on = b; writePin(o, b); }
      continue;
    }
    if (o.mode == 3) continue;  // PWM: set when commanded
    if (o.mode == 1 && now - o.until < 0x80000000UL) { o.mode = 0; o.want = false; }
    drive(o, i, o.mode == 1 ? true : o.want, now);
    if (o.on && o.maxOnS && now - o.onSince > (uint32_t)o.maxOnS * 1000UL) {
      o.locked = true; o.on = false; o.want = false; writePin(o, false);
      reply('E', 'O', i + 1, F("maxon"));
    }
  }
}

void outputsOff() {
  for (uint8_t i = 0; i < NCH; i++) {
    Out &o = outs[i];
    if (o.pin < 0) continue;
    o.mode = 0; o.want = false;
    if (o.on || o.duty) { o.on = false; o.duty = 0; writePin(o, false); o.changedAt = millis(); }
  }
}

void printOutputs() {
  int8_t last = -1;
  for (uint8_t i = 0; i < NCH; i++) if (outs[i].pin >= 0) last = i;
  if (last < 0) return;
  Serial.print(F(" Q="));
  for (uint8_t i = 0; i <= (uint8_t)last; i++) {
    Out &o = outs[i];
    char c = '-';
    if (o.pin >= 0) {
      if (o.locked) c = 'x';
      else if (o.mode == 1) c = 'p';
      else if (o.mode == 2) c = 'b';
      else if (o.mode == 3) c = '~';
      else if (o.want != o.on) c = 'w';
      else c = o.on ? '1' : '0';
    }
    Serial.print(c);
  }
  Serial.print(F(" fs="));
  Serial.print(failsafe ? 1 : 0);
}

/** The output commands; [s] is the whole line, its letter first. */
void handleOut(char *s) {
  char cmd = s[0];
  char *tok = strtok(s + 1, " ");
  int ch = tok ? atoi(tok) : 0;
  if (cmd == 'X') { outputsOff(); Serial.println(F("K X")); return; }
  if (cmd == 'H') {
    if (!tok) return;  // a keep-alive: lastLine is already fresh
    long ms = atol(tok);
    hbArmed = ms > 0;
    hbTimeout = constrain(ms, 500, 30000);
    if (failsafe) { failsafe = false; Serial.println(F("F 0")); }
    return;
  }
  if (ch < 1 || ch > NCH) { reply('E', cmd, ch, F("ch")); return; }
  Out &o = outs[ch - 1];
  char *a = strtok(NULL, " ");
  if (cmd == 'C') {
    if (a && a[0] == '-') {
      if (o.pin >= 0) { writePin(o, false); pinMode(o.pin, INPUT); }
      o.pin = -1; reply('K', 'C', ch, NULL); return;
    }
    // only a pin number counts: a garbled token must not become pin 0
    char *num = a ? a + ((a[0] == 'D' || a[0] == 'd') ? 1 : 0) : NULL;
    int pin = (num && *num >= '0' && *num <= '9') ? atoi(num) : -1;
    if (!pinAllowed(pin)) { reply('E', 'C', ch, F("pin")); return; }
    for (uint8_t i = 0; i < NCH; i++) if (i != ch - 1 && outs[i].pin == pin) { reply('E', 'C', ch, F("busy")); return; }
    char *lvl = strtok(NULL, " "), *mn = strtok(NULL, " "), *mf = strtok(NULL, " "), *mx = strtok(NULL, " "), *ls = strtok(NULL, " "), *hd = strtok(NULL, " ");
    bool same = o.pin == pin;
    if (o.pin >= 0 && !same) { writePin(o, false); pinMode(o.pin, INPUT); }
    o.pin = pin;
    o.activeLow = lvl && (lvl[0] == 'L' || lvl[0] == 'l');
    o.minOn = mn ? atol(mn) : 0;
    o.minOff = mf ? atol(mf) : 0;
    o.maxOnS = mx ? atoi(mx) : 0;
    o.lost = ls ? ls[0] : '0';
    o.holdS = hd ? atoi(hd) : 0;
    // the same pin with a new polarity: the pin follows at once, or an "off" output would switch a module on
    if (same && o.mode == 0) writePin(o, o.on);
    if (!same) {
      // the inactive level first, then output: an active-low module must not click at setup
      o.on = false; o.want = false; o.mode = 0; o.duty = 0; o.locked = false; o.changedAt = millis() - 600000UL;
      writePin(o, false);
      pinMode(pin, OUTPUT);
    }
    reply('K', 'C', ch, NULL);
    return;
  }
  if (o.pin < 0) { reply('E', cmd, ch, F("setup")); return; }
  switch (cmd) {
    case 'O':
      if (a && atoi(a) == 0) o.locked = false;
      // leaving PWM or blinking: the pin is off now, so a following "on" really switches it fully on
      if (o.mode == 3 || o.mode == 2) { writePin(o, false); o.on = false; o.duty = 0; o.changedAt = millis() - 600000UL; }
      o.mode = 0; o.want = a && atoi(a) != 0;
      break;
    case 'P':
      o.mode = 1; o.until = millis() + constrain(atol(a ? a : "0"), 1, 60000);
      break;
    case 'B': {
      char *off = strtok(NULL, " ");
      o.blinkOn = constrain(atol(a ? a : "0"), 20, 10000); o.blinkOff = constrain(atol(off ? off : "0"), 20, 10000);
      o.blinkStart = millis(); o.mode = 2;
      break;
    }
    case 'W': {
#if !defined(ESP_BOX)  // any ESP32 pin can do PWM
      if (digitalPinToTimer(o.pin) == NOT_ON_TIMER) { reply('E', 'W', ch, F("pwm")); return; }
#endif
      uint8_t d = constrain(atoi(a ? a : "0"), 0, 255);
      o.mode = 3; o.duty = d; o.on = d > 0;
      analogWrite(o.pin, o.activeLow ? 255 - d : d);
      break;
    }
  }
}

#if defined(ESP_BOX)
// ---- CAN: the TWAI controller behind a TJA1051T/3 (or SN65HVD230). Receive only; frames go to the app as slcan lines.

/** The bit timing for [kbit]: 20 time quanta of the 80 MHz clock, sampled at 80 %; false for a speed not offered. */
bool canTiming(uint16_t kbit, twai_timing_config_t &t) {
  twai_timing_config_t base = TWAI_TIMING_CONFIG_100KBITS();
  t = base;
  uint32_t brp = 0;
  switch (kbit) {
    case 33: brp = 120; break;  // 33.333 (GMLAN, through a single-wire transceiver of its own)
    case 50: { twai_timing_config_t c = TWAI_TIMING_CONFIG_50KBITS(); t = c; return true; }
    case 62: brp = 64; break;   // 62.5 (old VW comfort)
    case 83: brp = 48; break;   // 83.333 (Mercedes CAN-B)
    case 100: return true;
    case 125: { twai_timing_config_t c = TWAI_TIMING_CONFIG_125KBITS(); t = c; return true; }
    case 250: { twai_timing_config_t c = TWAI_TIMING_CONFIG_250KBITS(); t = c; return true; }
    case 500: { twai_timing_config_t c = TWAI_TIMING_CONFIG_500KBITS(); t = c; return true; }
    case 800: { twai_timing_config_t c = TWAI_TIMING_CONFIG_800KBITS(); t = c; return true; }
    case 1000: { twai_timing_config_t c = TWAI_TIMING_CONFIG_1MBITS(); t = c; return true; }
    default: return false;
  }
  t.quanta_resolution_hz = 0;  // brp counts instead
  t.brp = brp;
  return true;
}

/**
 * The acceptance filter in the controller (SJA1000 layout). Single filter: an 11-bit id sits at bits 31..21, a 29-bit
 * one at 31..3. Dual filter (11-bit ids): the first at 31..21, the second at 15..5. A mask bit 1 there means "any".
 */
twai_filter_config_t canFilter() {
  twai_filter_config_t f = TWAI_FILTER_CONFIG_ACCEPT_ALL();
  if (canNf == 1 && canExtF) {
    f.acceptance_code = canId[0] << 3;
    f.acceptance_mask = ~(canMask[0] << 3);
  } else if (canNf == 1) {
    f.acceptance_code = canId[0] << 21;
    f.acceptance_mask = ~(canMask[0] << 21);
  } else if (canNf == 2) {
    f.single_filter = false;
    f.acceptance_code = (canId[0] << 21) | (canId[1] << 5);
    f.acceptance_mask = ~((canMask[0] << 21) | (canMask[1] << 5));
  }
  return f;
}

/** The filter once more, exactly: the controller's also lets through the other id length with matching top bits. */
bool canPass(const twai_message_t &m) {
  if (!canNf) return true;
  if ((bool)m.extd != canExtF) return false;
  for (uint8_t i = 0; i < canNf; i++) if ((m.identifier & canMask[i]) == (canId[i] & canMask[i])) return true;
  return false;
}

/** The driver stopped; the pins stay the bus's. */
void canStop() {
  if (!canOn) return;
  twai_stop();
  twai_driver_uninstall();
  canOn = false;
}

/** The bus closed and its pins free: TX back to an input (the transceiver's own pull-up keeps it recessive), S to the
 *  pull-up that holds the transceiver silent. */
void canRelease() {
  canStop();
  if (canTx >= 0) pinMode(canTx, INPUT);
  if (canRx >= 0) pinMode(canRx, INPUT);
  if (canS >= 0) pinMode(canS, INPUT);
  canTx = canRx = canS = -1;
  canKbit = 0;
}

/** Starts the driver with the settings held; false when it would not. */
bool canStart() {
  twai_timing_config_t t;
  if (!canTiming(canKbit, t)) return false;
  // listen only: the transceiver's transmitter off in hardware as well, so not even a fault can drive the car's bus
  if (canS >= 0) { pinMode(canS, OUTPUT); digitalWrite(canS, canListen ? HIGH : LOW); }
  twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT((gpio_num_t)canTx, (gpio_num_t)canRx, canListen ? TWAI_MODE_LISTEN_ONLY : TWAI_MODE_NORMAL);
  g.rx_queue_len = 1024;  // a quarter of a second of a busy 500 kbit bus while the loop reads the sensors or draws
  g.tx_queue_len = 1;
  twai_filter_config_t f = canFilter();
  if (twai_driver_install(&g, &t, &f) != ESP_OK) return false;
  if (twai_start() != ESP_OK) { twai_driver_uninstall(); return false; }
  canOn = true;
  canCheckAt = millis();
  return true;
}

int pinArg(const char *c) {
  if (!c || c[0] == '-') return -1;
  const char *n = c + ((c[0] == 'D' || c[0] == 'd') ? 1 : 0);
  return (*n >= '0' && *n <= '9') ? atoi(n) : -2;
}

void handleCan(char *s) {
  char *t = strtok(s + 1, " ");
  if (!t) return;
  if (t[0] == 'C') { canRelease(); Serial.println(F("K G C")); return; }
  if (t[0] == 'F') {
    canNf = 0; canExtF = false;
    char *a = strtok(NULL, " ");
    if (a && (a[0] == 'X' || a[0] == 'x')) { canExtF = true; a = strtok(NULL, " "); }
    for (uint8_t i = 0; i < 2 && a; i++) {
      char *m = strtok(NULL, " ");
      if (!m) break;
      uint32_t lim = canExtF ? 0x1FFFFFFFUL : 0x7FFUL;
      canId[i] = strtoul(a, NULL, 16) & lim;
      canMask[i] = strtoul(m, NULL, 16) & lim;
      canNf = i + 1;
      if (canExtF) break;  // one filter for 29-bit ids
      a = strtok(NULL, " ");
    }
    if (canOn) { canStop(); canStart(); }  // the controller takes a filter only while stopped
    Serial.println(F("K G F"));
    return;
  }
  if (t[0] == 'O') {
    char *k = strtok(NULL, " "), *md = strtok(NULL, " "), *a = strtok(NULL, " "), *b = strtok(NULL, " "), *c = strtok(NULL, " ");
    canRelease();
    int tx = pinArg(a), rx = pinArg(b), sp = pinArg(c);
    bool busy = false;
    for (uint8_t i = 0; i < NCH; i++) if (outs[i].pin >= 0 && (outs[i].pin == tx || outs[i].pin == rx || (sp >= 0 && outs[i].pin == sp))) busy = true;
    if (busy || !pinAllowed(tx) || !pinAllowed(rx) || tx == rx || sp == -2 || (sp >= 0 && (!pinAllowed(sp) || sp == tx || sp == rx))) {
      Serial.println(F("E G O pin")); return;
    }
    twai_timing_config_t tm;
    uint16_t kb = k ? atoi(k) : 0;
    if (!canTiming(kb, tm)) { Serial.println(F("E G O kbit")); return; }
    canKbit = kb;
    canListen = !(md && (md[0] == 'N' || md[0] == 'n'));
    canTx = tx; canRx = rx; canS = sp;
    if (!canStart()) { canRelease(); Serial.println(F("E G O drv")); return; }
    Serial.println(F("K G O"));
    return;
  }
  Serial.println(F("E G ?"));
}

const char HEXD[] = "0123456789ABCDEF";

/** Every frame waiting in the driver's queue to the app, one slcan line each; a line that would not fit whole in the
 *  USB buffer (the app reads too slowly, or nobody does) is dropped and counted, never cut. */
void canPump() {
  if (!canOn) return;
  twai_message_t m;
  char b[32];
  while (twai_receive(&m, 0) == ESP_OK) {
    if (!canPass(m)) continue;
    canFrames++;
    if (!streaming) continue;
    uint8_t n = 0, dlc = m.data_length_code > 8 ? 8 : m.data_length_code;
    b[n++] = m.rtr ? (m.extd ? 'R' : 'r') : (m.extd ? 'T' : 't');
    for (int8_t sh = m.extd ? 28 : 8; sh >= 0; sh -= 4) b[n++] = HEXD[(m.identifier >> sh) & 0xF];
    b[n++] = '0' + dlc;
    if (!m.rtr) for (uint8_t i = 0; i < dlc; i++) { b[n++] = HEXD[m.data[i] >> 4]; b[n++] = HEXD[m.data[i] & 0xF]; }
    b[n++] = '\n';
    if (Serial.availableForWrite() < n + CAN_RESERVE) { canDrop++; continue; }
    Serial.write((const uint8_t *)b, n);
  }
}

/** Twice a second: a bus-off controller (normal mode, a wiring fault) recovers and starts again by itself. */
void canTick(uint32_t now) {
  if (!canOn || now - canCheckAt < 500) return;
  canCheckAt = now;
  twai_status_info_t st;
  if (twai_get_status_info(&st) != ESP_OK) return;
  if (st.state == TWAI_STATE_BUS_OFF) twai_initiate_recovery();
  else if (st.state == TWAI_STATE_STOPPED) twai_start();
}

/** " can=l,<frames>,<dropped>,<missed>,<rx errors>,<tx errors>" in the D line, while the bus is open. */
void printCan() {
  if (!canOn) return;
  twai_status_info_t st;
  if (twai_get_status_info(&st) != ESP_OK) return;
  char c = st.state == TWAI_STATE_RUNNING ? (canListen ? 'l' : 'n') : st.state == TWAI_STATE_BUS_OFF ? 'b' :
           st.state == TWAI_STATE_RECOVERING ? 'r' : 's';
  Serial.print(F(" can=")); Serial.print(c);
  Serial.print(','); Serial.print(canFrames);
  Serial.print(','); Serial.print(canDrop);
  Serial.print(','); Serial.print(st.rx_missed_count + st.rx_overrun_count);
  Serial.print(','); Serial.print(st.rx_error_counter);
  Serial.print(','); Serial.print(st.tx_error_counter);
}
#else
void canPump() {}
#endif

void printInfo() {
  Serial.print(F("I SENSORBOX 2 board=" BOARD " id="));
  if (boxId < 0x1000) Serial.print('0');
  if (boxId < 0x100) Serial.print('0');
  if (boxId < 0x10) Serial.print('0');
  Serial.print(boxId, HEX);
  // the version as text in flash: the app reads it from a .hex before uploading it
  Serial.print(F(" fw=" STR(FW_VERSION) " rate="));
  Serial.print(rateHz);
  Serial.print(F(" disp="));
  Serial.print(oledAddr && lcdAddr ? F("oled+lcd") : oledAddr ? F("oled") : lcdAddr ? F("lcd") : F("none"));
#if defined(ESP_BOX)
  Serial.print(F(" can=twai"));  // a CAN controller on the chip (firmware 6+): the G commands
#endif
  Serial.print(F(" ch="));
  bool first = true;
  for (uint8_t i = 0; i < nCh(); i++) {
    if (chSkip(i)) continue;
    if (!first) Serial.print(',');
    first = false;
    Serial.print(chName(i));
  }
  if (tcPresent) Serial.print(F(",TC0"));
  Serial.print(F(",F0 out="));
  for (uint8_t i = 0; i < NOUT_PINS; i++) {
    if (i) Serial.print(',');
    Serial.print('D');
    Serial.print(OUT_PINS[i]);
  }
  Serial.println();
}

char line[72];  // a colour screen's text line: "T x y s fg bg " and up to 53 characters
uint8_t lineLen = 0;

void handle(char *s) {
  switch (s[0]) {
    case '?':
      printInfo();
      streaming = true;
      break;
    case 'R': {
      int r = atoi(s + 1);
      if (r >= 1 && r <= 50) {
        rateHz = r;
        eeUpdate(3, rateHz);
      }
      printInfo();
      break;
    }
    case 'S':
      streaming = atoi(s + 1) != 0;
      break;
    case 'C': case 'O': case 'P': case 'B': case 'W': case 'H': case 'X':
      handleOut(s);
      break;
    case 'L': case 'M': case 'Y': case 'N': case 'Z': case 'Q': case 'T':
      handleDisplay(s);
      break;
#if defined(ESP_BOX)
    case 'G':
      handleCan(s);
      break;
#endif
    default:
      Serial.println(F("E ?"));
  }
}

void readCommands() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (lineLen) {
        line[lineLen] = 0;
        lastLine = millis();  // any line is a heartbeat
        if (dispStale) { dispStale = false; Serial.println(F("Y R")); }  // the app is back: ask it to draw again
        handle(line);
        lineLen = 0;
      }
    } else if (lineLen < sizeof(line) - 1) {
      line[lineLen++] = c;
    }
  }
}

void setup() {
  for (uint8_t i = 0; i < NCH; i++) outs[i].pin = -1;  // every output an input until the app sets it up
#if defined(ESP_BOX)
  Serial.setTxBufferSize(8192);  // CAN frames between the D lines: up to ~90 KB/s from a busy 500 kbit bus
#endif
  Serial.begin(115200);
#if defined(ESP_BOX)
  Serial.setTxTimeoutMs(0);  // nobody reading the USB: lines are dropped, the loop (and the outputs' guards) never waits
  EEPROM.begin(16);
  analogReadResolution(12);
#endif
  findDisplays();
  if (EEPROM.read(0) != EE_MAGIC) {
#if defined(ESP_BOX)
    randomSeed(esp_random());
#else
    randomSeed(analogRead(AIN[0]) ^ (analogRead(AIN[1]) << 4) ^ micros());
#endif
    boxId = random(1, 0xFFFF);
    eeUpdate(1, boxId >> 8);
    eeUpdate(2, boxId & 0xFF);
    eeUpdate(3, rateHz);
    eeUpdate(0, EE_MAGIC);
  }
  boxId = (EEPROM.read(1) << 8) | EEPROM.read(2);
  rateHz = constrain(EEPROM.read(3), 1, 50);

  pinMode(FREQ_PIN, INPUT_PULLUP);  // an open-collector sensor (flex fuel, hall speed) pulls it down
  attachInterrupt(digitalPinToInterrupt(FREQ_PIN), onEdge, RISING);

#if USE_MAX31855
  pinMode(TC_CS, OUTPUT);
  digitalWrite(TC_CS, HIGH);
  SPI.begin();
#if defined(ESP_BOX)
  gpio_pullup_en((gpio_num_t)MISO);  // pinMode would take the pin away from the SPI unit
#else
  pinMode(MISO, INPUT_PULLUP);  // no board on the bus reads all ones
#endif
  delay(250);                   // its first conversion
  uint32_t v = tcRaw();
  tcPresent = v != 0 && v != 0xFFFFFFFFUL;
#endif
}

uint32_t nextFrame = 0;

void loop() {
  readCommands();
  canPump();
  uint32_t now = millis();
  outputsTick(now);
  displaysTick(now);
#if defined(ESP_BOX)
  canTick(now);
#endif
  if (!streaming || (int32_t)(now - nextFrame) < 0) return;
  nextFrame = now + 1000 / rateHz;

  // read first, then print the line in one go: CAN lines may go out between two reads, never inside a D line
  long vcc = readVccMv();
  float val[8];
  for (uint8_t i = 0; i < nCh() && i < 8; i++) {
    val[i] = chSkip(i) ? 0 : chRead(i, vcc);
    canPump();  // an ADS1115 input takes a few ms: the CAN queue is emptied between inputs
  }
  Serial.print(F("D t="));
  Serial.print(now);
  Serial.print(F(" vcc="));
  Serial.print(vcc);
  for (uint8_t i = 0; i < nCh() && i < 8; i++) {
    if (chSkip(i)) continue;
    Serial.print(' ');
    Serial.print(chName(i));
    Serial.print('=');
    Serial.print(val[i], 1);
  }

#if USE_MAX31855
  for (uint8_t i = 0; i < 4; i++) {
    if (i == 0 ? !tcPresent : tcCs[i] < 0) continue;
    uint32_t v = tcRaw(i == 0 ? TC_CS : tcCs[i]);
    Serial.print(F(" TC"));
    Serial.print(i);
    Serial.print('=');
    if (v == 0 || v == 0xFFFFFFFFUL) {
      Serial.print(F("!8"));  // no module answers on this CS
    } else if (v & 0x10000UL) {
      Serial.print('!');
      Serial.print(v & 7);  // 1 open thermocouple, 2 shorted to ground, 4 shorted to supply
    } else {
      int32_t t = (int32_t)v >> 18;  // 14-bit signed, 0.25 °C a step
      Serial.print(t * 0.25f, 2);
    }
  }
#endif

#if defined(ESP_BOX)
  portENTER_CRITICAL(&fMux);
#else
  noInterrupts();
#endif
  uint32_t sum = fSum;
  uint16_t n = fN;
  fSum = 0;
  fN = 0;
#if defined(ESP_BOX)
  portEXIT_CRITICAL(&fMux);
#else
  interrupts();
#endif
  if (n) {
    fHz = n * 1000000.0f / sum;
    fSeenMs = now;
  } else if (now - fSeenMs > 1000) {
    fHz = 0;
  }
  Serial.print(F(" F0="));
  Serial.print(fHz, 1);
  printOutputs();
#if defined(ESP_BOX)
  printCan();
#endif
  Serial.println();
}
