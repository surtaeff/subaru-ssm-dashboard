/**
 * Receiver of the CarPulse log upload: a Google Apps Script web app.
 *
 * FIRST THING TO CHECK after deploying (the app side was only tested against a local fake, and what Apps Script really
 * does to a POST body and a redirect can only be seen on a deployment). Put the web app address in URL and run:
 *
 *   printf 'hello' | gzip -c | base64 > /tmp/t.b64
 *   p() { L=$(curl -s -i -X POST --data-binary "$1" "$URL?$2" | tr -d '\r' | grep -i '^location:' | cut -d' ' -f2); curl -s "$L"; echo; }
 *   p @/tmp/t.b64 "id=ABCD2345&key=00112233445566778899aabbccddeeff"   -> ok
 *   p @/tmp/t.b64 "id=abcd0000"                                        -> bad id   (lower case, 0 are not in the alphabet)
 *   p AAAA "id=ABCD2345"                                               -> not gzip
 *   head -c 600000 /dev/zero | base64 > /tmp/big.b64; p @/tmp/big.b64 "id=ABCD2345"  -> bad size
 * A POST answers 302 with a Location, and the reply is read with a GET of it, as the app does. `curl -L -X POST` would
 * POST to the Location too and get an HTML error page though the log was taken: don't test with it.
 * Then in Drive, "CarPulse logs/ABCD2345": download the file, `gunzip` it, it must say: hello. If the text is damaged,
 * the base64 route is wrong and the app must not be released with the upload switched on.
 * Also post 21 files with one id: the folder must keep the newest 20. The same address without the key must land in
 * "ABCD2345/unverified". tools/log-upload/test.js runs all the rules here against a mock of Google (node tools/log-upload/test.js).
 *
 * Deploy (the owner), under a DEDICATED Google account, not the main one: the address is public (it is in latest.json),
 * anyone can post to it, and the Drive of this account is what gets filled.
 *   1. script.google.com -> New project, paste this file. Services (+): add "Drive API" (the advanced service).
 *   2. Deploy -> New deployment -> Web app. Execute as: Me. Who has access: Anyone.
 *   3. Run installDailyCleanup() once (asks for permission): it sets the 30-day clean-up trigger.
 *   4. Put the web app address into latest.json on main as "logUrl". Until it is there the app sends nothing.
 *
 * No shared secret is in the app or the repository (both public). What the script itself refuses: a body over 512 KB, an id
 * that is not 8 symbols of the id alphabet, a body that is not gzip, more than the limits below.
 *
 * HOW TO READ THE FOLDERS. Each install has its own random key, sent with every upload; only its SHA-256 is stored here.
 *   - "CarPulse logs/<ID>/": logs from the install that first uploaded to this id (its key hash is the folder's description).
 *   - "CarPulse logs/<ID>/unverified/": anything else under this id: another key or no key. Forged logs land here and cannot
 *     push out the real ones (each folder keeps its own newest 20).
 *   - The file name is log-<UTC stamp>-<tag>.txt.gz; the tag is the first 6 characters of the key's hash ("no-key" without a
 *     key). A second tag under one id means a cloned unit, a reinstall (a reinstall makes a new key, so after it the real unit
 *     lands in "unverified" under its new tag) or a forgery; the tag tells which logs belong together, not whose they are.
 *     To accept a reinstalled unit as the real one, put its full hash in the folder's description. A first upload can
 *     claim an id nobody has used yet: the id is not secret once it was told to someone, so read logs with that in mind.
 *
 * LIMITS. Verified uploads (registered id, matching key) 200 a day; everything else, first uploads included, 100 a day,
 * and each id 30 a day. So forged sends can't use up the day of the real units. Honest limit: someone who registers many ids
 * in advance can then spend the verified budget with their own keys; what remains safe is the logs already there, not the day's
 * quota. The per-id count lives in CacheService, which forgets after 6 hours without an upload, so after a quiet spell an id can
 * get more than 30 in one calendar day. Registered folders stay when empty, so that a registration doesn't lapse.
 *
 * The app sends a POST with the BASE64 of the gzip as plain text, and ?id=XXXXXXXX&key=<32 hex> in the address. The reply is
 * exactly "ok" when the log was taken (the unverified folder answers the same); Apps Script gives it behind a 302, which the
 * app follows with a GET.
 */
var FOLDER_NAME = 'CarPulse logs';
var MAX_BODY = 512 * 1024;
var MAX_FILES_PER_ID = 20;
var MAX_VERIFIED_PER_DAY = 200;
var MAX_OTHER_PER_DAY = 100;
var MAX_PER_ID_PER_DAY = 30;
var UNVERIFIED = 'unverified';
var KEY_RE = /^[0-9a-f]{32}$/;
var HASH_RE = /[0-9a-f]{64}/;
var KEEP_DAYS = 30;
// the same 32 symbols as DeviceId.ALPHABET in the app: no 0, O, 1, I
var ID_RE = /^[2-9A-HJ-NP-Z]{8}$/;

function doPost(e) {
  try {
    var id = (e && e.parameter && e.parameter.id) || '';
    if (!ID_RE.test(id)) return reply_('bad id');
    var text = (e.postData && e.postData.contents) || '';
    // the length of the text first: nothing is decoded that is too long
    if (text.length === 0 || text.length > MAX_BODY) return reply_('bad size');
    var bytes = Utilities.base64Decode(text.replace(/\s+/g, ''));
    // gzip magic: 1F 8B
    if (bytes.length < 2 || (bytes[0] & 0xFF) !== 0x1F || (bytes[1] & 0xFF) !== 0x8B) return reply_('not gzip');

    var key = (e.parameter && e.parameter.key) || '';
    var hash = KEY_RE.test(key) ? sha256_(key) : '';
    var lock = LockService.getScriptLock();
    lock.waitLock(20000);
    try {
      if (!idUnderLimit_(id)) return reply_('id limit');
      var root = folder_();
      var existing = findFolder_(root, id);
      var owner = existing ? ownerHash_(existing) : '';
      var verified = hash !== '' && hash === owner;
      if (!countToday_(verified)) return reply_('day limit');
      countId_(id);
      var dir = existing || root.createFolder(id);
      var target = dir;
      if (!verified) {
        if (hash !== '' && owner === '') dir.setDescription(hash); // the first key to this id is its owner
        else target = subfolder_(dir, UNVERIFIED);
      }
      var stamp = Utilities.formatDate(new Date(), 'UTC', 'yyyy-MM-dd_HH-mm-ss');
      var tag = hash !== '' ? hash.substring(0, 6) : 'no-key';
      target.createFile(Utilities.newBlob(bytes, 'application/gzip', 'log-' + stamp + '-' + tag + '.txt.gz'));
      keepNewest_(target);
    } finally {
      lock.releaseLock();
    }
    return reply_('ok');
  } catch (err) {
    return reply_('error');
  }
}

// a GET only says it is alive
function doGet() { return reply_('CarPulse log receiver'); }

function reply_(text) { return ContentService.createTextOutput(text); }

// a budget of its own for verified uploads and for everything else, counted in the script's properties; false when used up
function countToday_(verified) {
  var props = PropertiesService.getScriptProperties();
  var day = Utilities.formatDate(new Date(), 'UTC', 'yyyy-MM-dd');
  var name = verified ? 'countVerified' : 'countOther';
  var limit = verified ? MAX_VERIFIED_PER_DAY : MAX_OTHER_PER_DAY;
  var today = props.getProperty('day') === day;
  var n = today ? parseInt(props.getProperty(name) || '0', 10) : 0;
  if (n >= limit) return false;
  var change = { day: day };
  change[name] = String(n + 1);
  if (!today) change[verified ? 'countOther' : 'countVerified'] = '0';
  props.setProperties(change);
  return true;
}

function idCacheKey_(id) { return 'id-' + Utilities.formatDate(new Date(), 'UTC', 'yyyy-MM-dd') + '-' + id; }

// checked before anything touches Drive; an entry lives at most 6 hours, the most CacheService allows
function idUnderLimit_(id) {
  var n = parseInt(CacheService.getScriptCache().get(idCacheKey_(id)) || '0', 10);
  return n < MAX_PER_ID_PER_DAY;
}

function countId_(id) {
  var cache = CacheService.getScriptCache();
  var k = idCacheKey_(id);
  cache.put(k, String(parseInt(cache.get(k) || '0', 10) + 1), 21600);
}

// hex of SHA-256; the digest comes as signed bytes
function sha256_(text) {
  var d = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, text, Utilities.Charset.UTF_8);
  var out = '';
  for (var i = 0; i < d.length; i++) out += ('0' + (d[i] & 0xFF).toString(16)).slice(-2);
  return out;
}

// the key hash that owns this id's folder, '' when none yet
function ownerHash_(dir) {
  var m = HASH_RE.exec(dir.getDescription() || '');
  return m ? m[0] : '';
}

function findFolder_(parent, name) {
  var it = parent.getFoldersByName(name);
  return it.hasNext() ? it.next() : null;
}

function folder_() {
  var it = DriveApp.getFoldersByName(FOLDER_NAME);
  return it.hasNext() ? it.next() : DriveApp.createFolder(FOLDER_NAME);
}

function subfolder_(parent, name) {
  return findFolder_(parent, name) || parent.createFolder(name);
}

// one folder only (an id's, or its unverified one): the newest MAX_FILES_PER_ID stay, the oldest are deleted
function keepNewest_(dir) {
  var all = [], files = dir.getFiles();
  while (files.hasNext()) all.push(files.next());
  if (all.length <= MAX_FILES_PER_ID) return;
  all.sort(function (a, b) { return a.getDateCreated().getTime() - b.getDateCreated().getTime(); });
  for (var i = 0; i < all.length - MAX_FILES_PER_ID; i++) remove_(all[i]);
}

// gone for good where the Drive advanced service is on (a trashed file still counts against the storage)
function remove_(file) {
  try { Drive.Files.remove(file.getId()); } catch (err) { file.setTrashed(true); }
}

// files older than the limit in one folder; returns how many are left
function cleanFiles_(dir, limit) {
  var files = dir.getFiles(), left = 0;
  while (files.hasNext()) {
    var f = files.next();
    if (f.getDateCreated().getTime() < limit) remove_(f); else left++;
  }
  return left;
}

// only from the daily trigger, never from a POST: files older than KEEP_DAYS, and the folders left empty. A registered id's
// folder stays: its description holds the owner's key hash, and without it a forged first upload could claim the id.
function dailyCleanup() {
  var limit = new Date().getTime() - KEEP_DAYS * 24 * 3600 * 1000;
  var dirs = folder_().getFolders();
  while (dirs.hasNext()) {
    var d = dirs.next();
    var left = cleanFiles_(d, limit);
    var u = findFolder_(d, UNVERIFIED);
    if (u) {
      var inU = cleanFiles_(u, limit);
      if (inU === 0) remove_(u); else left += inU;
    }
    if (left === 0 && ownerHash_(d) === '') remove_(d);
  }
}

function installDailyCleanup() {
  ScriptApp.getProjectTriggers().forEach(function (t) { ScriptApp.deleteTrigger(t); });
  ScriptApp.newTrigger('dailyCleanup').timeBased().everyDays(1).create();
}
