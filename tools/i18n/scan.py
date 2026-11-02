#!/usr/bin/env python3
"""
Lists what still needs an English line in app/src/main/assets/i18n/en.tsv.

  scan.py stats                  counts per source file: literals, covered, pending
  scan.py pending <file> [out]   pending keys of a source file (path under kg/forester/sg), numbered; ids kept in out
  scan.py next <out> <chars> [prefix]   the next pending keys over all files, up to a budget of characters
  scan.py merge <out> < lines    "id|English" or "=key<TAB>English" lines: appended to en.tsv

Keys are written the way tr() looks them up: `#` a number, `{}` any text, \n a line break.
The Kotlin twin of this scan lives in app/src/test/.../I18nCoverageTest.kt.
"""
import collections, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
SRC = os.path.join(REPO, 'app/src/main/java/kg/forester/sg')
TSV = os.path.join(REPO, 'app/src/main/assets/i18n/en.tsv')
ALLOW = os.path.join(REPO, 'app/src/test/resources/i18n_allowlist.txt')
CYR = re.compile('[А-Яа-яЁё]')
# never on screen: voice grammar and its spoken form (Russian only), kept out on purpose
SKIP_FILES = {'service/Commands.kt', 'service/SpeechText.kt'}
HOLE = ''
NUM_FORMAT = re.compile(r'%(?:\d+\$)?[-+ 0,#(]*\d*(?:\.\d+)?([sdfegxXc%])')
LOG_CALL = re.compile(r'(?:\bLog\.[a-z]\(|SsmTrace\.\w+\(|\btrace\(|\btraceLine\(|GapLog\.\w+\(|\bgapLog\(|\bPerfLog\.\w+\(|\blogLine\(|Regex\(|\.toRegex\()\s*$')


def read_lit(src, i):
    """src[i] opens a string. Returns (text with holes, nested literals, end index)."""
    n = len(src)
    raw = src.startswith('"""', i)
    i += 3 if raw else 1
    buf, nested = [], []
    while i < n:
        if raw and src.startswith('"""', i):
            i += 3
            break
        if not raw and src[i] == '"':
            i += 1
            break
        c = src[i]
        if not raw and c == '\\':
            e = src[i + 1]
            if e == 'u':
                buf.append(chr(int(src[i + 2:i + 6], 16)))
                i += 6
                continue
            buf.append({'n': '\n', 't': '\t', 'r': '\r', 'b': '\b'}.get(e, e))
            i += 2
            continue
        if c == '$' and i + 1 < n and src[i + 1] == '{':
            j = skip_block(src, i + 2)
            nested += find_literals(src[i + 2:j - 1])
            buf.append(HOLE)
            i = j
            continue
        if c == '$' and i + 1 < n and (src[i + 1].isalpha() or src[i + 1] == '_'):
            j = i + 1
            while j < n and (src[j].isalnum() or src[j] == '_'):
                j += 1
            buf.append(HOLE)
            i = j
            continue
        buf.append(c)
        i += 1
    return ''.join(buf), nested, i


def skip_block(src, i):
    """Past the } closing a ${ block that has been entered."""
    d, n = 1, len(src)
    while i < n and d:
        c = src[i]
        if c == '"':
            _, _, i = read_lit(src, i)
            continue
        if c == "'":
            i = skip_char(src, i)
            continue
        if c == '{':
            d += 1
        elif c == '}':
            d -= 1
        i += 1
    return i


def skip_char(src, i):
    j = i + 1
    if j < len(src) and src[j] == '\\':
        j += 2
    else:
        j += 1
    k = src.find("'", j)
    return k + 1 if 0 <= k - j < 8 else i + 1


def skip_expr(src, i):
    """A term of a + chain starting at i: stops at a top-level + , ) } ] or a line end that continues nothing. -1 when it holds a string."""
    n, d = len(src), 0
    while i < n:
        c = src[i]
        if c in '([{':
            d += 1
        elif c in ')]}':
            if d == 0:
                return i
            d -= 1
        elif c == '"' and d == 0:
            return -1
        elif c == '"':
            _, _, i = read_lit(src, i)
            continue
        elif d == 0 and c in '+,\n':
            return i
        elif d == 0 and src.startswith(' else ', i):
            return i
        elif d == 0 and src.startswith(' ?: ', i):
            return i
        elif d == 0 and c in ';':
            return i
        i += 1
    return i


def find_literals(src, base_line=1):
    """All display candidates: (text, line, context before), comments skipped, + chains merged into one text."""
    out, i, n, line = [], 0, len(src), base_line
    while i < n:
        c = src[i]
        if c == '\n':
            line += 1
            i += 1
        elif src.startswith('//', i):
            j = src.find('\n', i)
            i = j if j >= 0 else n
        elif src.startswith('/*', i):
            d, j = 1, i + 2
            while j < n and d:
                if src.startswith('/*', j):
                    d += 1
                    j += 2
                elif src.startswith('*/', j):
                    d -= 1
                    j += 2
                else:
                    j += 1
            line += src.count('\n', i, j)
            i = j
        elif c == "'":
            i = skip_char(src, i)
        elif c == '"':
            before = src[max(0, i - 70):i]
            start_line = line
            text, nested = '', []
            k = i
            m = re.search(r'\+\s*$', before)
            if m and not before[:m.start()].rstrip().endswith('='):
                text = HOLE
            plus = re.compile(r'\s*\+\s*')
            while True:
                t, ne, k = read_lit(src, k)
                text += t
                nested += ne
                again = False
                while True:
                    m = plus.match(src, k)
                    if not m:
                        break
                    j = m.end()
                    if j < n and src[j] == '"':
                        k = j
                        again = True
                        break
                    e = skip_expr(src, j)
                    if e < 0:
                        # a term like x.format("..."): the rest is one hole
                        text += HOLE
                        break
                    if e == j:
                        break
                    nested += find_literals(src[j:e])
                    text += HOLE
                    k = e
                if not again:
                    break
            out.append((text, start_line, before))
            out += [(a, start_line, b) for a, _, b in nested]
            line += src.count('\n', i, k)
            i = k
        else:
            i += 1
    return out


def to_key(s):
    """A literal (with holes) as a dictionary key."""
    def fmt(m):
        k = m.group(1)
        return {'s': HOLE, '%': '%', 'd': '#', 'f': '#', 'e': '#', 'g': '#', 'x': '#', 'X': '#', 'c': HOLE}[k]
    s = NUM_FORMAT.sub(fmt, s)
    has_hole = HOLE in s or '#' in s
    s = s.replace(HOLE, '{}')
    s = re.sub(r'(\{\}\s*){2,}', lambda m: '{} ' if m.group(0).endswith(' ') else '{}', s)
    if has_hole:
        s = numbers_to_marks(s)
    s = s.replace('\r', '').replace('\t', ' ')
    return s.replace('\n', '\\n')


NUM_RUN = re.compile(r'(?<![0-9A-Za-zА-Яа-яЁё.,])\d+(?:[.,:]\d+)*(?![0-9A-Za-z])')


def numbers_to_marks(s):
    return NUM_RUN.sub('#', s)


def load_keys():
    keys = {}
    if os.path.exists(TSV):
        for ln in open(TSV, encoding='utf8'):
            ln = ln.rstrip('\n')
            if not ln or ln.startswith('//') or '\t' not in ln:
                continue
            k, v = ln.split('\t', 1)
            keys[k] = v
    return keys


def shape(k):
    """The form that makes a literal and an entry the same: every hole alike, no outer space or stop."""
    k = k.replace('{}', '').replace('#', '')
    k = NUM_RUN.sub('', k)
    k = re.sub(r'(\s*)+', '', k)
    return k.strip('  :,.…—·;-\\n')


def trimmed(k):
    return k.strip('  :,.…—·;')


def literals():
    res = collections.OrderedDict()
    for dp, _, fn in os.walk(SRC):
        for f in sorted(fn):
            if not f.endswith('.kt'):
                continue
            p = os.path.join(dp, f)
            rel = os.path.relpath(p, SRC)
            if rel in SKIP_FILES:
                continue
            lits = []
            for t, line, before in find_literals(open(p, encoding='utf8').read()):
                if not CYR.search(t.replace(HOLE, '')) or LOG_CALL.search(before):
                    continue
                lits.append((to_key(t), line, before))
            res[rel] = lits
    return res


def covered(k, shapes, keys):
    return k in keys or shape(k) in shapes


def allowed():
    if not os.path.exists(ALLOW):
        return set()
    return {l.rstrip('\n') for l in open(ALLOW, encoding='utf8') if l.strip() and not l.startswith('//')}


def main():
    cmd = sys.argv[1]
    keys = load_keys()
    shapes = {shape(k) for k in keys}
    allow = allowed()
    lits = literals()
    if cmd == 'stats':
        tot = cov = 0
        seen = set()
        for f, ls in sorted(lits.items(), key=lambda x: -len(x[1])):
            u = {l[0] for l in ls}
            pend = {k for k in u if not covered(k, shapes, keys) and k not in allow}
            if u:
                print(f'{len(u):5d} {len(pend):5d} {sum(map(len, pend)):7d} {f}')
            tot += len(u - seen)
            cov += len({k for k in u - seen if k not in pend})
            seen |= u
        print('unique', tot, 'done', cov, 'chars left', sum(len(k) for k in seen if not covered(k, shapes, keys) and k not in allow))
    elif cmd == 'pending':
        f = sys.argv[2]
        out = {}
        i = 0
        seen = set()
        for k, line, before in lits[f]:
            if k in seen or covered(k, shapes, keys) or k in allow:
                continue
            seen.add(k)
            i += 1
            out[str(i)] = k
            ctx = before.split('\n')[-1].strip()[-26:] if len(sys.argv) > 4 else ''
            print(f'{i}|{ctx + " >> " if ctx else ""}{k}')
        if len(sys.argv) > 3:
            json.dump(dict(file=f, keys=out), open(sys.argv[3], 'w'), ensure_ascii=False)
    elif cmd == 'next':
        # the next pending keys over all files, up to a budget of characters: <out> <budget> [file prefix]
        budget = int(sys.argv[3])
        pre = sys.argv[4] if len(sys.argv) > 4 else ''
        out, seen, i, names = {}, set(), 0, []
        for f, ls in sorted(lits.items(), key=lambda x: (not x[0].startswith('ui/'), -len(x[1]))):
            if not f.startswith(pre):
                continue
            shown = False
            for k, line, before in ls:
                if k in seen or covered(k, shapes, keys) or k in allow:
                    continue
                if budget < 0:
                    break
                seen.add(k)
                budget -= len(k)
                i += 1
                out[str(i)] = k
                if not shown:
                    print('## ' + f)
                    names.append(f)
                    shown = True
                print(f'{i}|{k}')
            if budget < 0:
                break
        json.dump(dict(file=' '.join(names), keys=out), open(sys.argv[2], 'w'), ensure_ascii=False)
    elif cmd == 'merge':
        batch = json.load(open(sys.argv[2], encoding='utf8'))
        os.makedirs(os.path.dirname(TSV), exist_ok=True)
        added, bad = 0, []
        with open(TSV, 'a', encoding='utf8') as w:
            w.write('// ' + batch['file'] + '\n')
            for ln in sys.stdin.read().split('\n'):
                if not ln.strip():
                    continue
                a, _, v = ln.partition('\t')
                if not v and '|' in a:
                    a, _, v = a.partition('|')
                a = a.strip()
                if a.startswith('='):  # "=key<TAB>value": an entry written by hand
                    k = a[1:]
                elif a in batch['keys']:
                    k = batch['keys'][a]
                else:
                    bad.append(ln[:50])
                    continue
                if k in keys:
                    continue
                keys[k] = v
                w.write(k + '\t' + v + '\n')
                added += 1
        left = [i for i, k in batch['keys'].items() if k not in keys]
        print('added', added, 'bad', bad, 'left', left)


main()
