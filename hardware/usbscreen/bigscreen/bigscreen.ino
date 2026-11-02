/*
 * CarPulse: a big colour panel on the head unit's USB (an ESP32-S3 RGB or ESP32-P4 MIPI-DSI board). The small boards keep
 * their own sketch (../usbscreen.ino); this one speaks the same protocol and adds what a big panel needs.
 *
 * The app draws the picture itself and sends only the parts that changed; this firmware puts them on the panel.
 *
 * host → screen   ?                        who are you → I USBSCREEN 1 board=<id> w=<W> h=<H> round=0 fw=<N> id=<MAC> maxpx=<N> jpeg=1
 *                 P <x> <y> <w> <h> <n>\n  then n bytes: packed RGB565 as in usbscreen.ino; answer K (or E)
 *                 J <x> <y> <w> <h> <n>\n  then n bytes: a baseline JPEG exactly w x h; answer K (or E)
 *                 B <0..255>, Z, H         brightness, black, «still here»
 * screen → host   N / L                    the BOOT button short / long, on boards where it can be read
 *                 Y R                      no line from the app for 5 s («NO LINK» was shown): draw everything again
 * JPEG is decoded by the P4's hardware, by JPEGDEC (software with SIMD) on the S3.
 */
#include "big_board.h"
#include "big_boards.h"

#if CONFIG_IDF_TARGET_ESP32P4
  #include "driver/jpeg_decode.h"
#else
  #include <JPEGDEC.h>
#endif

#define FW_VERSION 1
#define STR_(x) #x
#define STR(x) STR_(x)
#define MAX_PX 131072
#define RAW_BYTES (MAX_PX * 2 + MAX_PX / 128 + 8)

static uint16_t *px;
static uint8_t *raw;
static char line[96];
static uint8_t lineLen = 0;
static uint32_t lastLine = 0, pressedAt = 0;
static bool drew = false, stale = false, pressed = false;

static void centred(const char *t, int16_t y, uint8_t size, uint16_t colour) {
  gfx->setTextSize(size);
  gfx->setTextColor(colour);
  int16_t w = strlen(t) * 6 * size;
  gfx->setCursor((gfx->width() - w) / 2, y);
  gfx->print(t);
}

static void hello() {
  gfx->fillScreen(0);
  int16_t y = gfx->height() / 2 - 24;
  centred("CarPulse", y, 4, 0x2E7F);
  centred("waiting for the app", y + 44, 2, 0x8410);
}

static void printInfo() {
  Serial.print(F("I USBSCREEN 1 board=" SCREEN_ID " w="));
  Serial.print(gfx->width());
  Serial.print(F(" h="));
  Serial.print(gfx->height());
  Serial.print(F(" round=0 fw=" STR(FW_VERSION) " id="));
  Serial.printf("%012llX", ESP.getEfuseMac() & 0xFFFFFFFFFFFFULL);
  Serial.print(F(" maxpx=" STR(MAX_PX) " jpeg=1"));
  Serial.println();
}

/** [n] packed bytes into exactly [count] pixels; false when they do not add up. */
static bool unpack(const uint8_t *s, uint32_t n, uint16_t *out, uint32_t count) {
  uint32_t i = 0, o = 0;
  while (i < n && o < count) {
    uint8_t h = s[i++];
    uint32_t k = (h & 0x7F) + 1;
    if (o + k > count) return false;
    if (h & 0x80) {
      if (i + 2 > n) return false;
      uint16_t c = s[i] | (s[i + 1] << 8);
      i += 2;
      while (k--) out[o++] = c;
    } else {
      if (i + 2 * k > n) return false;
      while (k--) { out[o++] = s[i] | (s[i + 1] << 8); i += 2; }
    }
  }
  return o == count && i == n;
}

#if CONFIG_IDF_TARGET_ESP32P4
static jpeg_decoder_handle_t jpegEngine = nullptr;
static uint8_t *jpegOut = nullptr;
static size_t jpegOutCap = 0;

static void jpegBegin() {
  jpeg_decode_engine_cfg_t c = {};
  c.intr_priority = 0;
  c.timeout_ms = 300;
  jpeg_new_decoder_engine(&c, &jpegEngine);
  jpeg_decode_memory_alloc_cfg_t m = {};
  m.buffer_direction = JPEG_DEC_ALLOC_OUTPUT_BUFFER;
  // a rectangle of MAX_PX, its sides rounded up to the 16-pixel blocks the decoder writes
  jpegOut = (uint8_t *)jpeg_alloc_decoder_mem(MAX_PX * 2 * 2, &m, &jpegOutCap);
}

/** The hardware needs its input in DMA-safe memory: raw is allocated so. Writes whole blocks, so the row step is rounded up. */
static bool drawJpeg(const uint8_t *data, uint32_t n, long x, long y, long w, long h) {
  if (!jpegEngine || !jpegOut) return false;
  jpeg_decode_picture_info_t info;
  if (jpeg_decoder_get_info(data, n, &info) != ESP_OK || (long)info.width != w || (long)info.height != h) return false;
  jpeg_decode_cfg_t cfg = {};
  cfg.output_format = JPEG_DECODE_OUT_FORMAT_RGB565;
  cfg.rgb_order = JPEG_DEC_RGB_ELEMENT_ORDER_BGR;
  cfg.conv_std = JPEG_YUV_RGB_CONV_STD_BT601;
  uint32_t got = 0;
  if (jpeg_decoder_process(jpegEngine, &cfg, data, n, jpegOut, jpegOutCap, &got) != ESP_OK) return false;
  long block = info.sample_method == JPEG_DOWN_SAMPLING_YUV444 || info.sample_method == JPEG_DOWN_SAMPLING_GRAY ? 8 : 16;
  long stride = (w + block - 1) / block * block;
  if (stride == w) {
    gfx->draw16bitRGBBitmap(x, y, (uint16_t *)jpegOut, w, h);
  } else {
    // the padding off, row by row, into px (at most MAX_PX pixels: the app keeps w x h under it)
    for (long r = 0; r < h; r++) memcpy(px + r * w, (uint16_t *)jpegOut + r * stride, w * 2);
    gfx->draw16bitRGBBitmap(x, y, px, w, h);
  }
  return true;
}
#else
static JPEGDEC jpeg;
static long jx, jy;

static int jpegRow(JPEGDRAW *d) {
  gfx->draw16bitRGBBitmap(jx + d->x, jy + d->y, d->pPixels, d->iWidth, d->iHeight);
  return 1;
}

static void jpegBegin() {}

static bool drawJpeg(const uint8_t *data, uint32_t n, long x, long y, long w, long h) {
  if (!jpeg.openRAM((uint8_t *)data, n, jpegRow)) return false;
  bool ok = false;
  if (jpeg.getWidth() == w && jpeg.getHeight() == h) {
    jx = x;
    jy = y;
    jpeg.setPixelType(RGB565_LITTLE_ENDIAN);
    ok = jpeg.decode(0, 0, 0) == 1;
  }
  jpeg.close();
  return ok;
}
#endif

static void blit(char *s, bool isJpeg) {
  char *p = s + 1;
  long x = strtol(p, &p, 10), y = strtol(p, &p, 10), w = strtol(p, &p, 10), h = strtol(p, &p, 10), n = strtol(p, &p, 10);
  if (w <= 0 || h <= 0 || w * h > MAX_PX || n <= 0 || n > RAW_BYTES ||
      x < 0 || y < 0 || x + w > gfx->width() || y + h > gfx->height()) { Serial.println('E'); return; }
  if ((long)Serial.readBytes(raw, n) != n) { Serial.println('E'); return; }
  if (isJpeg) {
    if (!drawJpeg(raw, n, x, y, w, h)) { Serial.println('E'); return; }
  } else {
    if (!unpack(raw, n, px, w * h)) { Serial.println('E'); return; }
    gfx->draw16bitRGBBitmap(x, y, px, w, h);
  }
  drew = true;
  Serial.println('K');
}

static void handle(char *s) {
  switch (s[0]) {
    case '?': printInfo(); break;
    case 'P': blit(s, false); break;
    case 'J': blit(s, true); break;
    case 'B': SET_BRIGHT(constrain(atoi(s + 1), 0, 255)); break;
    case 'Z': gfx->fillScreen(0); break;
    case 'H': break;
    default: Serial.println('E');
  }
}

void setup() {
  Serial.setRxBufferSize(32768);
  Serial.setTimeout(500);
  Serial.begin(115200);
  BOARD_INIT();
  px = (uint16_t *)heap_caps_aligned_alloc(64, MAX_PX * 2, MALLOC_CAP_SPIRAM);
  // the P4's JPEG engine reads this by DMA: aligned to its cache line
  raw = (uint8_t *)heap_caps_aligned_alloc(64, RAW_BYTES, MALLOC_CAP_SPIRAM);
  gfx->begin();
  SET_BRIGHT(200);
  jpegBegin();
  hello();
#if BOOT_PIN >= 0
  pinMode(BOOT_PIN, INPUT_PULLUP);
#endif
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (!lineLen) continue;
      line[lineLen] = 0;
      lineLen = 0;
      lastLine = millis();
      if (stale) { stale = false; Serial.println(F("Y R")); }
      handle(line);
    } else if (lineLen < sizeof(line) - 1) {
      line[lineLen++] = c;
    }
  }
  uint32_t now = millis();
  // old numbers must not look live
  if (drew && !stale && now - lastLine > 5000) {
    stale = true;
    gfx->fillScreen(0);
    centred("NO LINK", gfx->height() / 2 - 12, 4, 0xF800);
  }
#if BOOT_PIN >= 0
  bool down = digitalRead(BOOT_PIN) == LOW;
  if (down && !pressed) { pressed = true; pressedAt = now; }
  if (!down && pressed) {
    pressed = false;
    if (now - pressedAt > 30) Serial.println(now - pressedAt > 800 ? 'L' : 'N');
  }
#else
  (void)pressed; (void)pressedAt;
#endif
}
