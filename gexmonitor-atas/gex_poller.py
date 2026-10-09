# -*- coding: utf-8 -*-
"""
GEX Monitor -> ATAS realtime bridge (multi-asset)
Polls public GEX Monitor endpoints every N seconds for each configured asset,
extracts the structural price lines shown on the website kline chart
(P1/P2/N1/N2, Flip/HVL, optional Max Pain and 1-day expected move, spot) and
writes them atomically to per-asset local JSON files consumed by the ATAS
custom indicator. Per-day archives and history CSVs are maintained per asset.

Public endpoints (no auth; gateway rate limit ~60 requests/min):
  /api/price?asset=ETH&realtime=1
  /api/gex-latest?asset=ETH          (full: profiles.*.walls + meta)

Only the Python standard library is required.
"""

import csv
import json
import os
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime, timezone

class _NullWriter:
    """Safe stand-in when the runtime provides no console
    (pythonw.exe / PyInstaller windowed build set sys.stdout to None)."""
    encoding = "utf-8"
    errors = "replace"

    def write(self, *args, **kwargs):
        return 0

    def flush(self):
        pass

    def reconfigure(self, *args, **kwargs):
        pass


# Must run BEFORE any print(): a None stdout would make print() raise
# AttributeError and silently kill a windowed build.
if sys.stdout is None:
    sys.stdout = _NullWriter()
if sys.stderr is None:
    sys.stderr = _NullWriter()

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# ----------------------------- configuration -----------------------------
# Comma-separated asset list; each asset maps to its own data files.
ASSETS = [a.strip().upper() for a in
          os.environ.get("GEX_ASSETS", "BTC,ETH,SOL").split(",") if a.strip()]
INTERVAL = float(os.environ.get("GEX_INTERVAL", "10"))        # seconds (near realtime)
# Shared data directory used by the ATAS indicator(s).
# Default: %AppData%\ATAS X\GexData ; override with GEX_DATA_DIR.
DATA_DIR = os.environ.get(
    "GEX_DATA_DIR",
    os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")),
                 "ATAS X", "GexData"),
)

# Per-day archive: today's entry refreshes every cycle; past days stay frozen.
# The ATAS indicator draws each day as its own horizontal segment from this file.
MAX_DAYS = int(os.environ.get("GEX_MAX_DAYS", "30"))
DAY_KEYS = ["FLIP", "P1", "P2", "N1", "N2", "MP", "EM_HIGH", "EM_LOW", "MAXGEX"]

# High-frequency time series archive (one file per asset). Every poll appends
# the current level values keyed by unix time; unchanged values extend the last
# point instead of duplicating it (change-point compression), so each level
# becomes a right-continuous step series. Consumed by the GEX Levels Plus
# indicator to draw the right-angle stair-step history of every line.
SERIES_HOURS = int(os.environ.get("GEX_SERIES_HOURS", "720"))
SERIES_KEYS = ["FLIP", "P1", "P2", "N1", "N2", "MP",
               "EM_HIGH", "EM_LOW", "SPOT"]

API_ROOT = "https://gexmonitor.com"
HTTP_TIMEOUT = 30
USER_AGENT = "GexAtasBridge/1.0"

# Include the optional lines (Max Pain, 1D expected move band) in the JSON.
INCLUDE_OPTIONAL = os.environ.get("GEX_INCLUDE_OPTIONAL", "1") != "0"


def levels_file(asset):
    return os.path.join(DATA_DIR, f"gex_levels_{asset}.json")


def history_file(asset):
    return os.path.join(DATA_DIR, f"gex_history_{asset}.csv")


def days_file(asset):
    return os.path.join(DATA_DIR, f"gex_days_{asset}.json")


def vp_file(asset):
    # NET GEX profile consumed by the GEX Volume Profile indicator.
    return os.path.join(DATA_DIR, f"gex_vp_{asset}.json")


def series_file(asset):
    # Step-series history consumed by the GEX Levels Plus indicator.
    return os.path.join(DATA_DIR, f"gex_series_{asset}.json")


def http_get_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT,
                                                "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
        raw = resp.read()
    return json.loads(raw.decode("utf-8"))


def iso_utc_now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def extract_levels(gex):
    """Return ordered dict of level key -> {price, kind, note}."""
    total = (gex.get("profiles") or {}).get("total") or {}
    walls = total.get("walls") or {}
    meta = total.get("meta") or {}

    levels = {}

    def wall(key, name):
        w = walls.get(key)
        if w is not None and w.get("strike") is not None:
            levels[name] = {"price": float(w["strike"]), "kind": "wall",
                            "gex": float(w.get("value", 0.0)),
                            "distance_pct": float(w.get("distance", 0.0))}

    # Names match the labels on the website chart.
    wall("p1", "P1")
    wall("p2", "P2")
    wall("n1", "N1")
    wall("n2", "N2")

    flip = meta.get("flip", gex.get("flip_point"))
    if flip is not None:
        levels["FLIP"] = {"price": float(flip), "kind": "flip",
                          "note": "Flip Point / HVL"}

    # Max absolute GEX strike (最大GEX行权价) from the per-strike profile.
    chart_data = total.get("chartData") or []
    best = None
    for row in chart_data:
        try:
            ag = abs(float(row.get("absGex") or 0.0))
            strike = float(row.get("strike"))
        except (TypeError, ValueError):
            continue
        if best is None or ag > best[1]:
            best = (strike, ag)
    if best is not None:
        levels["MAXGEX"] = {"price": best[0], "kind": "max_gex_strike",
                            "abs_gex": best[1], "note": "Max GEX strike"}

    if INCLUDE_OPTIONAL:
        mp = meta.get("mp")
        if mp is not None:
            levels["MP"] = {"price": float(mp), "kind": "max_pain"}

        em = meta.get("expectedMove1d") or {}
        if em.get("max") is not None:
            levels["EM_HIGH"] = {"price": float(em["max"]),
                                 "kind": "expected_move_1d"}
        if em.get("min") is not None:
            levels["EM_LOW"] = {"price": float(em["min"]),
                                "kind": "expected_move_1d"}

    return levels, meta


def atomic_write_json(path, payload):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def append_history(asset, spot, gex, levels):
    header = ["timestamp", "asset", "spot", "total_gex", "call_gex", "put_gex",
              "flip", "p1", "p2", "n1", "n2", "mp", "em_high", "em_low"]
    path = history_file(asset)
    exists = os.path.exists(path)
    with open(path, "a", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        if not exists:
            w.writerow(header)

        def p(k):
            lv = levels.get(k)
            return lv["price"] if lv else ""

        w.writerow([iso_utc_now(), asset, spot,
                    gex.get("total_gex", ""), gex.get("call_gex", ""),
                    gex.get("put_gex", ""), p("FLIP"), p("P1"), p("P2"),
                    p("N1"), p("N2"), p("MP"), p("EM_HIGH"), p("EM_LOW")])


def update_days_archive(asset, levels, spot):
    """Upsert today's level snapshot into the per-day archive (UTC date keys)."""
    day_payload = {k: levels[k]["price"] for k in DAY_KEYS if k in levels}
    if spot is not None:
        day_payload["SPOT"] = float(spot)

    path = days_file(asset)
    days = {}
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                days = json.load(f).get("days") or {}
        except Exception:
            days = {}

    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    if day_payload:
        days[today] = day_payload
    elif today not in days:
        days[today] = {}

    keep = sorted(days)[-MAX_DAYS:]
    days = {k: days[k] for k in keep}

    atomic_write_json(path, {
        "asset": asset,
        "updated_at": iso_utc_now(),
        "today": today,
        "days": days,
    })
    return today


def write_vp(asset, gex, spot):
    """Write NET GEX profile: every strike with call/put/net gamma."""
    total = (gex.get("profiles") or {}).get("total") or {}
    rows = []
    for r in total.get("chartData") or []:
        try:
            strike = float(r["strike"])
        except (KeyError, TypeError, ValueError):
            continue

        def num(k, row=r):
            v = row.get(k)
            return float(v) if v is not None else 0.0

        rows.append([strike, num("callGex"), num("putGex"), num("netGex")])

    atomic_write_json(vp_file(asset), {
        "asset": asset,
        "updated_at": iso_utc_now(),
        "spot": spot,
        "rows": rows,   # [strike, callGex >= 0, putGex <= 0, netGex]
    })


def update_series_archive(asset, levels, spot):
    """Append this cycle's values to the change-point-compressed step series.

    Each level is stored as [[unix_time, price], ...]; a value equal to the
    last one extends the previous point's timestamp instead of duplicating it,
    so the points describe a right-continuous staircase. Points older than
    SERIES_HOURS are pruned, keeping one older anchor point so the first
    horizontal step still starts inside the visible window.
    """
    path = series_file(asset)
    series = {}
    updated_at = iso_utc_now()
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                old = json.load(f)
            series = old.get("series") or {}
            updated_at = old.get("updated_at", updated_at)
        except Exception:
            series = {}

    now_epoch = int(time.time())
    cutoff = now_epoch - SERIES_HOURS * 3600

    def current_value(key):
        if key == "SPOT":
            return float(spot) if spot is not None else None
        lv = levels.get(key)
        return float(lv["price"]) if lv else None

    for key in SERIES_KEYS:
        v = current_value(key)
        if v is None:
            continue

        pts = series.get(key) or []

        # Prune points before the cutoff, keeping one anchor point (idx-1)
        # so the horizontal step leading into the window is not lost.
        idx = 0
        while idx < len(pts) and pts[idx][0] < cutoff:
            idx += 1
        if idx > 1:
            pts = pts[idx - 1:]
        elif idx == 1:
            pts = pts[:]  # keep everything (single old point is the anchor)

        # Change-point compression: extend the last point on equal value,
        # otherwise start a new step.
        if pts and pts[-1][1] == v:
            pts[-1][0] = now_epoch
        else:
            pts.append([now_epoch, v])

        series[key] = pts

    atomic_write_json(path, {
        "asset": asset,
        "updated_at": iso_utc_now(),
        "interval": int(INTERVAL),
        "hours": SERIES_HOURS,
        "series": series,
    })


def one_cycle(asset):
    price = http_get_json(f"{API_ROOT}/api/price?asset={asset}&realtime=1")
    gex = http_get_json(f"{API_ROOT}/api/gex-latest?asset={asset}")

    # Prefer the realtime price endpoint; fall back to price inside gex payload.
    spot = price.get("price")
    if spot is None:
        spot = price.get("last") or gex.get("asset_price")
    spot = float(spot) if spot is not None else None

    levels, meta = extract_levels(gex)

    total_gex = gex.get("total_gex")
    regime = "positive" if (total_gex or 0) >= 0 else "negative"

    payload = {
        "asset": asset,
        "updated_at": iso_utc_now(),
        "data_timestamp": gex.get("timestamp"),
        "availability": gex.get("availability"),
        "stale": gex.get("stale"),
        "spot": spot,
        "total_gex": total_gex,
        "call_gex": gex.get("call_gex"),
        "put_gex": gex.get("put_gex"),
        "regime": regime,
        "dvol": gex.get("dvol"),
        "iv_percentile": gex.get("iv_percentile"),
        "levels": levels,
    }

    os.makedirs(DATA_DIR, exist_ok=True)
    atomic_write_json(levels_file(asset), payload)
    append_history(asset, spot, gex, levels)
    day_key = update_days_archive(asset, levels, spot)
    write_vp(asset, gex, spot)
    update_series_archive(asset, levels, spot)

    flip = levels.get("FLIP", {}).get("price")
    print(f"[{payload['updated_at']}] {asset} spot={spot} regime={regime} "
          f"flip={flip} levels={len(levels)} day={day_key}")


def main():
    os.makedirs(DATA_DIR, exist_ok=True)
    print(f"GEX->ATAS bridge started | assets={ASSETS} interval={INTERVAL}s")
    print(f"data dir: {DATA_DIR}")

    # Fetch all assets concurrently: effective cycle time is bounded by the
    # slowest asset instead of the sum of all three.
    from concurrent.futures import ThreadPoolExecutor

    def run_asset(asset):
        try:
            one_cycle(asset)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as e:
            print(f"[{iso_utc_now()}] {asset} network error: {e}; keeping last file")
        except Exception as e:
            print(f"[{iso_utc_now()}] {asset} unexpected error: "
                  f"{type(e).__name__}: {e}")

    while True:
        with ThreadPoolExecutor(max_workers=len(ASSETS)) as pool:
            list(pool.map(run_asset, ASSETS))
        time.sleep(INTERVAL)


if __name__ == "__main__":
    main()
