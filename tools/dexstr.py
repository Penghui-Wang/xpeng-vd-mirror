import re, sys, pathlib
path = pathlib.Path(sys.argv[1])
data = path.read_bytes()
# 提取 UTF-8 可打印串（dex 里字符串是 MUTF-8）
pat = re.compile(rb'[\x20-\x7e\xc2-\xf4][\x20-\x7e\x80-\xbf]{3,}')
seen = []
s = set()
for m in pat.finditer(data):
    try:
        t = m.group().decode('utf-8', 'ignore')
    except Exception:
        continue
    if len(t) < 4:
        continue
    if t in s:
        continue
    s.add(t)
    seen.append(t)
kw = sys.argv[2:] if len(sys.argv) > 2 else None
if kw:
    low = [k.lower() for k in kw]
    out = [t for t in seen if any(k in t.lower() for k in low)]
else:
    out = seen
print('\n'.join(out))
