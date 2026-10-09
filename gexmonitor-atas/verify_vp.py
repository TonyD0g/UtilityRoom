# -*- coding: utf-8 -*-
import json
import sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

d = json.load(open(r'data\gex_vp_ETH.json', encoding='utf-8'))
print('updated:', d['updated_at'], 'spot:', d['spot'])
rows = [r for r in d['rows'] if 2480 <= r[0] <= 2800]
print('strike / callGex / putGex / netGex')
for r in rows:
    print(f"{r[0]:6.0f}  call={r[1]:12.1f}  put={r[2]:12.1f}  net={r[3]:12.1f}")
