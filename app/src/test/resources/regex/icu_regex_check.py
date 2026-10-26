#!/usr/bin/env python3
"""Compile every regex literal in Minim's Kotlin/Java source with the real ICU regex engine
(the one Android's java.util.regex uses), with the flags Android adds. Exit 1 on any failure.

Usage: icu_regex_check.py <source dir> [--list]
"""
import ctypes, ctypes.util, glob, os, re, sys

UREGEX_CASE_INSENSITIVE = 2
UREGEX_DOTALL = 32
UREGEX_MULTILINE = 8
UREGEX_COMMENTS = 4
UREGEX_ERROR_ON_UNKNOWN_ESCAPES = 512  # Android's native Pattern always sets this


def load_icu():
    for path in sorted(glob.glob('/usr/lib/*/libicui18n.so.*') + glob.glob('/usr/lib/libicui18n.so.*'), reverse=True):
        m = re.search(r'libicui18n\.so\.(\d+)$', path)
        if not m:
            continue
        lib = ctypes.CDLL(path)
        ver = m.group(1)
        return lib, getattr(lib, 'uregex_open_' + ver), getattr(lib, 'uregex_close_' + ver), ver
    raise SystemExit('ICU not found')


class UParseError(ctypes.Structure):
    _fields_ = [('line', ctypes.c_int32), ('offset', ctypes.c_int32),
                ('pre', ctypes.c_uint16 * 16), ('post', ctypes.c_uint16 * 16)]


def kotlin_unescape(s):
    out, i = [], 0
    simple = {'n': '\n', 't': '\t', 'r': '\r', 'b': '\b', '\\': '\\', '"': '"', "'": "'", '$': '$'}
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i + 1]
            if n == 'u':
                out.append(chr(int(s[i + 2:i + 6], 16))); i += 6; continue
            out.append(simple.get(n, n)); i += 2; continue
        out.append(c); i += 1
    return ''.join(out)


TEMPLATE = re.compile(r'\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*')


def read_literal(src, i):
    """Literal (possibly '+'-joined) starting at src[i]; returns (text, end) or (None, i)."""
    parts = []
    while True:
        while i < len(src) and src[i] in ' \t\r\n':
            i += 1
        if src.startswith('"""', i):
            j = src.index('"""', i + 3)
            while src.startswith('"', j + 3):  # """" ending
                j += 1
            raw = src[i + 3:j]
            parts.append(TEMPLATE.sub('1', raw.replace('${\'$\'}', '$')))
            i = j + 3
        elif src.startswith('"', i):
            j = i + 1
            while src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            raw = src[i + 1:j]
            # templates are unescaped $; escaped \$ stays literal
            raw = re.sub(r'(?<!\\)(\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*)', '1', raw)
            parts.append(kotlin_unescape(raw))
            i = j + 1
        else:
            break
        k = i
        while k < len(src) and src[k] in ' \t\r\n':
            k += 1
        if k < len(src) and src[k] == '+':
            i = k + 1
            continue
        break
    return (''.join(parts), i) if parts else (None, i)


CALL = re.compile(r'\bRegex\(|\bPattern\.compile\(|\bRegex\.fromLiteral\(|\bSafeRegex\.of\(|\bSafeRegex\.pattern\(')
LITERAL_THEN_CALL = re.compile(r'("""(?:.|\n)*?"""|"(?:\\.|[^"\\])*")\s*\.(toRegex|toPattern)\(')


def flags_after(src, i):
    depth, j = 1, i
    while j < len(src) and depth:
        if src[j] == '(':
            depth += 1
        elif src[j] == ')':
            depth -= 1
        j += 1
    tail = src[i:j]
    f = 0
    if 'IGNORE_CASE' in tail or 'CASE_INSENSITIVE' in tail: f |= UREGEX_CASE_INSENSITIVE
    if 'DOT_MATCHES_ALL' in tail or 'DOTALL' in tail: f |= UREGEX_DOTALL
    if 'MULTILINE' in tail: f |= UREGEX_MULTILINE
    if 'COMMENTS' in tail: f |= UREGEX_COMMENTS
    return f


def extract(path):
    src = open(path, encoding='utf-8').read()
    found = []
    for m in CALL.finditer(src):
        line = src.count('\n', 0, m.start()) + 1
        if 'fromLiteral' in m.group(0):
            continue
        text, end = read_literal(src, m.end())
        if text is None:
            found.append((line, None, 0))
        else:
            found.append((line, text, flags_after(src, end)))
    for m in LITERAL_THEN_CALL.finditer(src):
        line = src.count('\n', 0, m.start()) + 1
        text, _ = read_literal(src, m.start())
        found.append((line, text, 0))
    return found


def main():
    root = sys.argv[1]
    listing = '--list' in sys.argv
    lib, uopen, uclose, ver = load_icu()
    uopen.restype = ctypes.c_void_p
    uopen.argtypes = [ctypes.c_char_p, ctypes.c_int32, ctypes.c_uint32, ctypes.POINTER(UParseError), ctypes.POINTER(ctypes.c_int)]
    uclose.argtypes = [ctypes.c_void_p]
    total, bad, dynamic = 0, 0, 0
    for path in sorted(glob.glob(os.path.join(root, '**', '*.kt'), recursive=True) +
                       glob.glob(os.path.join(root, '**', '*.java'), recursive=True)):
        if path.endswith('SafeRegex.kt'):
            continue
        for line, pat, flags in extract(path):
            rel = os.path.relpath(path, root)
            if pat is None:
                dynamic += 1
                print(f'DYNAMIC {rel}:{line} (not a literal, check by hand)')
                continue
            total += 1
            u16 = pat.encode('utf-16-le')
            pe = UParseError()
            st = ctypes.c_int(0)
            h = uopen(u16, len(u16) // 2, flags | UREGEX_ERROR_ON_UNKNOWN_ESCAPES, ctypes.byref(pe), ctypes.byref(st))
            if h:
                uclose(h)
            if st.value > 0:
                bad += 1
                print(f'ICU FAIL {rel}:{line} status={st.value} offset={pe.offset}  {pat!r}')
            elif listing:
                print(f'ok {rel}:{line}  {pat!r}')
    print(f'ICU {ver}: {total} patterns, {bad} rejected, {dynamic} not literal')
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
