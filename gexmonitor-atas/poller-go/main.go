// Command gexpoller polls the public GEX Monitor endpoints and writes the
// structural price lines to per-asset local JSON files consumed by the ATAS
// custom indicators.
//
// It is a drop-in replacement for GexPoller.exe (the PyInstaller-packaged
// Python poller): the command-line surface is env-var driven like the
// original, the output file names and JSON schemas are byte-compatible, and
// only the Go standard library is used.
//
// Public endpoints (no auth; gateway rate limit ~60 requests/min):
//
//	/api/price?asset=ETH&realtime=1
//	/api/gex-latest?asset=ETH          (full: profiles.*.walls + meta)
//
// Files written per asset (into the shared data directory):
//
//	gex_levels_<asset>.json   current levels snapshot (consumed by GEX Levels)
//	gex_days_<asset>.json     per-day archive, one segment per UTC day
//	gex_series_<asset>.json   change-point-compressed step series (Levels Plus)
//	gex_vp_<asset>.json       net GEX profile rows (GEX Volume Profile)
//	gex_history_<asset>.csv   append-only history log
//
// Two deliberate differences from gex_poller.py:
//
//  1. The default data directory follows the host platform and matches what the
//     ATAS indicator resolves via Environment.SpecialFolder.ApplicationData:
//
//     Windows : %APPDATA%\ATAS X\GexData
//     macOS   : ~/Library/Application Support/ATAS X/GexData
//     Linux   : ~/.config/ATAS X/GexData
//
//     The Python original fell back to the plain home directory on macOS
//     ("~/ATAS X/GexData"), which is NOT where the indicator looks, so the
//     indicator could never find the data on a Mac. Set GEX_DATA_DIR to
//     override.
//
//  2. Network hardening: requests are HTTPS-only, redirects are validated and
//     capped, the response body size is limited, and asset names are validated
//     before being interpolated into URLs and file paths.
package main

import (
	"bytes"
	"context"
	"encoding/csv"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// ---------------------------------------------------------------------------
// configuration
// ---------------------------------------------------------------------------

const (
	apiRoot      = "https://gexmonitor.com"
	httpTimeout  = 30 * time.Second
	userAgent    = "GexAtasBridge/1.0"
	maxBodyBytes = 32 << 20 // 32 MiB safety cap on a single response
	maxRedirects = 5
)

// level keys written to the per-day archive. MAXGEX has no series counterpart
// because the website exposes it only as a snapshot, not as a step series.
var dayKeys = []string{"FLIP", "P1", "P2", "N1", "N2", "MP", "EM_HIGH", "EM_LOW", "MAXGEX"}

// keys written to the change-point-compressed step series.
var seriesKeys = []string{"FLIP", "P1", "P2", "N1", "N2", "MP", "EM_HIGH", "EM_LOW", "SPOT"}

// asset names are interpolated into both a URL and a file name, so they must
// not contain path separators, query delimiters or traversal sequences.
var assetRe = regexp.MustCompile(`^[A-Z0-9._-]{1,24}$`)

type config struct {
	assets          []string
	interval        time.Duration
	dataDir         string
	maxDays         int
	seriesHours     int
	includeOptional bool
	once            bool
	dryRun          bool
	printVersion    bool
	verbose         bool
}

func envStr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envInt(key string, def int) int {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(strings.TrimSpace(v))
	if err != nil {
		return def
	}
	return n
}

// defaultDataDir mirrors what the ATAS indicator computes on each platform via
// Environment.GetFolderPath(SpecialFolder.ApplicationData).
func defaultDataDir() string {
	switch runtime.GOOS {
	case "windows":
		base := os.Getenv("APPDATA")
		if base == "" {
			if home, err := os.UserHomeDir(); err == nil {
				base = filepath.Join(home, "AppData", "Roaming")
			}
		}
		return filepath.Join(base, "ATAS X", "GexData")
	case "darwin":
		home, err := os.UserHomeDir()
		if err != nil {
			return filepath.Join("ATAS X", "GexData")
		}
		return filepath.Join(home, "Library", "Application Support", "ATAS X", "GexData")
	default:
		if xdg := os.Getenv("XDG_CONFIG_HOME"); xdg != "" {
			return filepath.Join(xdg, "ATAS X", "GexData")
		}
		home, err := os.UserHomeDir()
		if err != nil {
			return filepath.Join("ATAS X", "GexData")
		}
		return filepath.Join(home, ".config", "ATAS X", "GexData")
	}
}

func parseConfig() (*config, error) {
	var (
		assetsFlag   = flag.String("assets", "", "comma-separated asset list (default $GEX_ASSETS or BTC,ETH,SOL)")
		intervalFlag = flag.Duration("interval", 0, "poll interval (default $GEX_INTERVAL seconds or 10s)")
		dataDirFlag  = flag.String("data-dir", "", "shared data directory (default $GEX_DATA_DIR or platform path)")
		onceFlag     = flag.Bool("once", false, "poll every asset exactly once, then exit")
		dryRunFlag   = flag.Bool("dry-run", false, "print the resolved configuration and exit")
		versionFlag  = flag.Bool("version", false, "print version and exit")
		verboseFlag  = flag.Bool("v", false, "verbose logging")
	)
	flag.Parse()

	cfg := &config{
		maxDays:         envInt("GEX_MAX_DAYS", 30),
		seriesHours:     envInt("GEX_SERIES_HOURS", 720),
		includeOptional: envStr("GEX_INCLUDE_OPTIONAL", "1") != "0",
		once:            *onceFlag,
		dryRun:          *dryRunFlag,
		printVersion:    *versionFlag,
		verbose:         *verboseFlag,
		interval:        time.Duration(float64(envInt("GEX_INTERVAL", 10)) * float64(time.Second)),
		dataDir:         defaultDataDir(),
	}

	if *intervalFlag > 0 {
		cfg.interval = *intervalFlag
	}
	if cfg.interval <= 0 {
		return nil, fmt.Errorf("interval must be positive, got %s", cfg.interval)
	}
	if *dataDirFlag != "" {
		cfg.dataDir = *dataDirFlag
	} else if v := os.Getenv("GEX_DATA_DIR"); v != "" {
		cfg.dataDir = v
	}

	raw := *assetsFlag
	if raw == "" {
		raw = envStr("GEX_ASSETS", "BTC,ETH,SOL")
	}
	seen := map[string]bool{}
	for _, part := range strings.Split(raw, ",") {
		a := strings.ToUpper(strings.TrimSpace(part))
		if a == "" {
			continue
		}
		if !assetRe.MatchString(a) {
			return nil, fmt.Errorf("invalid asset name %q (allowed: A-Z 0-9 . _ - up to 24 chars)", a)
		}
		if seen[a] {
			continue
		}
		seen[a] = true
		cfg.assets = append(cfg.assets, a)
	}
	if len(cfg.assets) == 0 {
		return nil, errors.New("no assets configured")
	}
	if cfg.maxDays < 1 {
		cfg.maxDays = 1
	}
	if cfg.seriesHours < 1 {
		cfg.seriesHours = 1
	}
	return cfg, nil
}

// ---------------------------------------------------------------------------
// logging
// ---------------------------------------------------------------------------

var logMu sync.Mutex

// version is stamped at build time:
// go build -ldflags "-X main.version=1.2.3"
var version = "dev"

// verbose is set from -v and enables per-request tracing.
var verbose bool

func logf(format string, args ...any) {
	logMu.Lock()
	defer logMu.Unlock()
	_, _ = fmt.Fprintf(os.Stdout, format+"\n", args...)
}

func verbosef(format string, args ...any) {
	if !verbose {
		return
	}
	logf(format, args...)
}

func isoUTCNow() string { return time.Now().UTC().Format("2006-01-02T15:04:05Z") }

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

var httpClient = &http.Client{
	Timeout: httpTimeout,
	CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) > maxRedirects {
			return fmt.Errorf("stopped after %d redirects", maxRedirects)
		}
		if req.URL.Scheme != "https" {
			return fmt.Errorf("refusing insecure redirect to %s", req.URL)
		}
		logf("[%s] redirect -> %s", isoUTCNow(), req.URL)
		return nil
	},
}

func httpGetJSON(ctx context.Context, rawURL string) (map[string]any, error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return nil, err
	}
	if u.Scheme != "https" {
		return nil, fmt.Errorf("refusing non-https URL: %s", rawURL)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "application/json")

	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("http %d for %s", resp.StatusCode, rawURL)
	}

	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes+1))
	if err != nil {
		return nil, err
	}
	if int64(len(raw)) > maxBodyBytes {
		return nil, fmt.Errorf("response exceeds %d bytes for %s", maxBodyBytes, rawURL)
	}

	var out map[string]any
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("decode %s: %w", rawURL, err)
	}
	return out, nil
}

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

func asMap(v any) map[string]any {
	m, _ := v.(map[string]any)
	return m
}

func asSlice(v any) []any {
	s, _ := v.([]any)
	return s
}

// asFloat converts a decoded JSON value to float64. It reports false for
// null/missing/non-numeric values so callers can fall back the same way the
// Python original did.
func asFloat(v any) (float64, bool) {
	switch n := v.(type) {
	case float64:
		return n, true
	case int:
		return float64(n), true
	case int64:
		return float64(n), true
	case json.Number:
		f, err := n.Float64()
		return f, err == nil
	case string:
		f, err := strconv.ParseFloat(strings.TrimSpace(n), 64)
		return f, err == nil
	default:
		return 0, false
	}
}

// jsonFloat renders a float the way the CSV archive did in Python: shortest
// round-tripping decimal, always with a fractional part.
func jsonFloat(f float64) string {
	switch {
	case math.IsNaN(f):
		return "nan"
	case math.IsInf(f, 1):
		return "inf"
	case math.IsInf(f, -1):
		return "-inf"
	}
	s := strconv.FormatFloat(f, 'f', -1, 64)
	if !strings.Contains(s, ".") {
		s += ".0"
	}
	return s
}

func loadJSONObject(path string) map[string]any {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var out map[string]any
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil
	}
	return out
}

// ---------------------------------------------------------------------------
// file paths
// ---------------------------------------------------------------------------

func levelsFile(dir, asset string) string  { return filepath.Join(dir, "gex_levels_"+asset+".json") }
func historyFile(dir, asset string) string { return filepath.Join(dir, "gex_history_"+asset+".csv") }
func daysFile(dir, asset string) string    { return filepath.Join(dir, "gex_days_"+asset+".json") }
func vpFile(dir, asset string) string      { return filepath.Join(dir, "gex_vp_"+asset+".json") }
func seriesFile(dir, asset string) string  { return filepath.Join(dir, "gex_series_"+asset+".json") }

// atomicWriteJSON writes to a sibling .tmp file and renames it into place, so a
// reader never observes a truncated file.
func atomicWriteJSON(path string, v any) error {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	if err := enc.Encode(v); err != nil {
		return err
	}
	data := bytes.TrimRight(buf.Bytes(), "\n")

	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// ---------------------------------------------------------------------------
// level extraction
// ---------------------------------------------------------------------------

// Level is one structural price line. Optional fields are omitted when absent,
// matching the shape the Python original wrote.
type Level struct {
	Price       float64  `json:"price"`
	Kind        string   `json:"kind"`
	Gex         *float64 `json:"gex,omitempty"`
	DistancePct *float64 `json:"distance_pct,omitempty"`
	AbsGex      *float64 `json:"abs_gex,omitempty"`
	Note        string   `json:"note,omitempty"`
}

func extractLevels(gex map[string]any, includeOptional bool) map[string]Level {
	levels := make(map[string]Level, 9)

	profiles := asMap(gex["profiles"])
	total := asMap(profiles["total"])
	walls := asMap(total["walls"])
	meta := asMap(total["meta"])

	wall := func(key, name string) {
		w := asMap(walls[key])
		if w == nil {
			return
		}
		strike, ok := asFloat(w["strike"])
		if !ok {
			return
		}
		gexVal, _ := asFloat(w["value"])
		dist, _ := asFloat(w["distance"])
		levels[name] = Level{
			Price:       strike,
			Kind:        "wall",
			Gex:         &gexVal,
			DistancePct: &dist,
		}
	}
	wall("p1", "P1")
	wall("p2", "P2")
	wall("n1", "N1")
	wall("n2", "N2")

	// meta.flip wins; fall back to the top-level flip_point when the key is
	// absent (mirrors dict.get semantics in the Python original).
	var flip any
	if v, ok := meta["flip"]; ok {
		flip = v
	} else {
		flip = gex["flip_point"]
	}
	if flip != nil {
		if f, ok := asFloat(flip); ok {
			levels["FLIP"] = Level{Price: f, Kind: "flip", Note: "Flip Point / HVL"}
		}
	}

	// Max absolute GEX strike from the per-strike profile.
	var (
		bestStrike float64
		bestAbs    float64
		found      bool
	)
	for _, rv := range asSlice(total["chartData"]) {
		row := asMap(rv)
		if row == nil {
			continue
		}
		strike, ok := asFloat(row["strike"])
		if !ok {
			continue
		}
		abs := 0.0
		if v, ok := asFloat(row["absGex"]); ok {
			abs = math.Abs(v)
		}
		if !found || abs > bestAbs {
			bestStrike, bestAbs, found = strike, abs, true
		}
	}
	if found {
		absGex := bestAbs
		levels["MAXGEX"] = Level{
			Price:  bestStrike,
			Kind:   "max_gex_strike",
			AbsGex: &absGex,
			Note:   "Max GEX strike",
		}
	}

	if includeOptional {
		if mp, ok := asFloat(meta["mp"]); ok {
			levels["MP"] = Level{Price: mp, Kind: "max_pain"}
		}
		em := asMap(meta["expectedMove1d"])
		if v, ok := asFloat(em["max"]); ok {
			levels["EM_HIGH"] = Level{Price: v, Kind: "expected_move_1d"}
		}
		if v, ok := asFloat(em["min"]); ok {
			levels["EM_LOW"] = Level{Price: v, Kind: "expected_move_1d"}
		}
	}

	return levels
}

// ---------------------------------------------------------------------------
// writers
// ---------------------------------------------------------------------------

var historyHeader = []string{
	"timestamp", "asset", "spot", "total_gex", "call_gex", "put_gex",
	"flip", "p1", "p2", "n1", "n2", "mp", "em_high", "em_low",
}

func textOrEmpty(v any) string {
	f, ok := asFloat(v)
	if !ok {
		return ""
	}
	return jsonFloat(f)
}

func appendHistory(dir, asset string, spot *float64, gex map[string]any, levels map[string]Level) error {
	path := historyFile(dir, asset)
	_, statErr := os.Stat(path)
	exists := statErr == nil

	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	defer func() { _ = f.Close() }()

	w := csv.NewWriter(f)
	w.UseCRLF = true // Python's csv module also emits CRLF by default.

	if !exists {
		if err := w.Write(historyHeader); err != nil {
			return err
		}
	}

	price := func(key string) string {
		if lv, ok := levels[key]; ok {
			return jsonFloat(lv.Price)
		}
		return ""
	}

	spotStr := ""
	if spot != nil {
		spotStr = jsonFloat(*spot)
	}

	if err := w.Write([]string{
		isoUTCNow(), asset, spotStr,
		textOrEmpty(gex["total_gex"]), textOrEmpty(gex["call_gex"]), textOrEmpty(gex["put_gex"]),
		price("FLIP"), price("P1"), price("P2"), price("N1"), price("N2"),
		price("MP"), price("EM_HIGH"), price("EM_LOW"),
	}); err != nil {
		return err
	}
	w.Flush()
	return w.Error()
}

// updateDaysArchive upserts today's snapshot into the per-day archive keyed by
// UTC date, keeping only the most recent maxDays entries.
func updateDaysArchive(dir, asset string, levels map[string]Level, spot *float64, maxDays int) string {
	today := time.Now().UTC().Format("2006-01-02")

	dayPayload := map[string]any{}
	for _, k := range dayKeys {
		if lv, ok := levels[k]; ok {
			dayPayload[k] = lv.Price
		}
	}
	if spot != nil {
		dayPayload["SPOT"] = *spot
	}

	days := map[string]any{}
	if old := loadJSONObject(daysFile(dir, asset)); old != nil {
		if d := asMap(old["days"]); d != nil {
			days = d
		}
	}

	if len(dayPayload) > 0 {
		days[today] = dayPayload
	} else if _, ok := days[today]; !ok {
		days[today] = map[string]any{}
	}

	all := make([]string, 0, len(days))
	for k := range days {
		all = append(all, k)
	}
	sort.Strings(all)
	if len(all) > maxDays {
		all = all[len(all)-maxDays:]
	}
	kept := make(map[string]any, len(all))
	for _, k := range all {
		kept[k] = days[k]
	}

	_ = atomicWriteJSON(daysFile(dir, asset), map[string]any{
		"asset":      asset,
		"updated_at": isoUTCNow(),
		"today":      today,
		"days":       kept,
	})
	return today
}

// writeVP writes the net GEX profile: one row per strike.
func writeVP(dir, asset string, gex map[string]any, spot *float64) {
	total := asMap(asMap(gex["profiles"])["total"])
	chartData := asSlice(total["chartData"])

	rows := make([][]any, 0, len(chartData))
	for _, rv := range chartData {
		row := asMap(rv)
		strike, ok := asFloat(row["strike"])
		if !ok {
			continue
		}
		num := func(k string) float64 {
			f, _ := asFloat(row[k])
			return f
		}
		rows = append(rows, []any{strike, num("callGex"), num("putGex"), num("netGex")})
	}

	var spotVal any
	if spot != nil {
		spotVal = *spot
	}

	_ = atomicWriteJSON(vpFile(dir, asset), map[string]any{
		"asset":      asset,
		"updated_at": isoUTCNow(),
		"spot":       spotVal,
		"rows":       rows, // [strike, callGex, putGex, netGex]
	})
}

// updateSeriesArchive appends this cycle's values to the change-point-compressed
// step series. A value equal to the previous one extends the last point instead
// of duplicating it, so the points describe a right-continuous staircase. Points
// older than seriesHours are pruned while keeping one anchor point so the step
// leading into the visible window is not lost.
func updateSeriesArchive(dir, asset string, levels map[string]Level, spot *float64, seriesHours, intervalSeconds int) {
	series := map[string]any{}
	if old := loadJSONObject(seriesFile(dir, asset)); old != nil {
		if s := asMap(old["series"]); s != nil {
			series = s
		}
	}

	nowEpoch := time.Now().Unix()
	cutoff := nowEpoch - int64(seriesHours)*3600

	type point struct {
		T int64
		P float64
	}

	for _, key := range seriesKeys {
		var (
			value float64
			has   bool
		)
		if key == "SPOT" {
			if spot != nil {
				value, has = *spot, true
			}
		} else if lv, ok := levels[key]; ok {
			value, has = lv.Price, true
		}
		if !has {
			continue
		}

		var pts []point
		for _, pv := range asSlice(series[key]) {
			pa := asSlice(pv)
			if len(pa) < 2 {
				continue
			}
			t, ok1 := asFloat(pa[0])
			p, ok2 := asFloat(pa[1])
			if !ok1 || !ok2 {
				continue
			}
			pts = append(pts, point{T: int64(t), P: p})
		}

		// Prune points before the cutoff, keeping one anchor (idx-1) so the
		// horizontal step leading into the window survives.
		idx := 0
		for idx < len(pts) && pts[idx].T < cutoff {
			idx++
		}
		if idx > 1 {
			pts = pts[idx-1:]
		}
		// idx == 1 keeps every point, which is equivalent to keeping the anchor
		// plus all in-window points.

		if len(pts) > 0 && pts[len(pts)-1].P == value {
			pts[len(pts)-1].T = nowEpoch
		} else {
			pts = append(pts, point{T: nowEpoch, P: value})
		}

		out := make([][]any, 0, len(pts))
		for _, p := range pts {
			out = append(out, []any{p.T, p.P})
		}
		series[key] = out
	}

	_ = atomicWriteJSON(seriesFile(dir, asset), map[string]any{
		"asset":      asset,
		"updated_at": isoUTCNow(),
		"interval":   intervalSeconds,
		"hours":      seriesHours,
		"series":     series,
	})
}

// ---------------------------------------------------------------------------
// one poll cycle
// ---------------------------------------------------------------------------

func oneCycle(ctx context.Context, cfg *config, asset string) error {
	priceURL := fmt.Sprintf("%s/api/price?asset=%s&realtime=1", apiRoot, url.QueryEscape(asset))
	gexURL := fmt.Sprintf("%s/api/gex-latest?asset=%s", apiRoot, url.QueryEscape(asset))

	verbosef("[%s] %s fetching %s", isoUTCNow(), asset, priceURL)
	price, err := httpGetJSON(ctx, priceURL)
	if err != nil {
		return err
	}
	verbosef("[%s] %s fetching %s", isoUTCNow(), asset, gexURL)
	gex, err := httpGetJSON(ctx, gexURL)
	if err != nil {
		return err
	}

	// Prefer the realtime price endpoint; fall back to the price inside the
	// gex payload.
	var spot *float64
	if v, ok := asFloat(price["price"]); ok {
		spot = &v
	} else if v, ok := asFloat(price["last"]); ok {
		spot = &v
	} else if v, ok := asFloat(gex["asset_price"]); ok {
		spot = &v
	}

	levels := extractLevels(gex, cfg.includeOptional)

	totalGex, hasTotal := asFloat(gex["total_gex"])
	regime := "negative"
	if !hasTotal || totalGex >= 0 {
		regime = "positive"
	}

	payload := map[string]any{
		"asset":          asset,
		"updated_at":     isoUTCNow(),
		"data_timestamp": gex["timestamp"],
		"availability":   gex["availability"],
		"stale":          gex["stale"],
		"spot":           nil,
		"total_gex":      gex["total_gex"],
		"call_gex":       gex["call_gex"],
		"put_gex":        gex["put_gex"],
		"regime":         regime,
		"dvol":           gex["dvol"],
		"iv_percentile":  gex["iv_percentile"],
		"levels":         levels,
	}
	if spot != nil {
		payload["spot"] = *spot
	}

	if err := os.MkdirAll(cfg.dataDir, 0o755); err != nil {
		return err
	}
	if err := atomicWriteJSON(levelsFile(cfg.dataDir, asset), payload); err != nil {
		return err
	}
	if err := appendHistory(cfg.dataDir, asset, spot, gex, levels); err != nil {
		return err
	}
	dayKey := updateDaysArchive(cfg.dataDir, asset, levels, spot, cfg.maxDays)
	writeVP(cfg.dataDir, asset, gex, spot)
	updateSeriesArchive(cfg.dataDir, asset, levels, spot, cfg.seriesHours, int(cfg.interval.Seconds()))

	spotStr := "None"
	if spot != nil {
		spotStr = jsonFloat(*spot)
	}
	flipStr := "None"
	if lv, ok := levels["FLIP"]; ok {
		flipStr = jsonFloat(lv.Price)
	}
	logf("[%s] %s spot=%s regime=%s flip=%s levels=%d day=%s",
		isoUTCNow(), asset, spotStr, regime, flipStr, len(levels), dayKey)
	return nil
}

func runAll(ctx context.Context, cfg *config) {
	var wg sync.WaitGroup
	for _, asset := range cfg.assets {
		wg.Add(1)
		go func(asset string) {
			defer wg.Done()
			if err := oneCycle(ctx, cfg, asset); err != nil {
				logf("[%s] %s error: %v; keeping last file", isoUTCNow(), asset, err)
			}
		}(asset)
	}
	wg.Wait()
}

func main() {
	cfg, err := parseConfig()
	if err != nil {
		_, _ = fmt.Fprintf(os.Stderr, "gexpoller: %v\n", err)
		os.Exit(2)
	}
	verbose = cfg.verbose

	if cfg.printVersion {
		logf("gexpoller %s (%s/%s)", version, runtime.GOOS, runtime.GOARCH)
		return
	}

	if cfg.dryRun {
		logf("assets          : %v", cfg.assets)
		logf("interval        : %s", cfg.interval)
		logf("data dir        : %s", cfg.dataDir)
		logf("max days        : %d", cfg.maxDays)
		logf("series hours    : %d", cfg.seriesHours)
		logf("include optional: %t", cfg.includeOptional)
		return
	}

	if err := os.MkdirAll(cfg.dataDir, 0o755); err != nil {
		_, _ = fmt.Fprintf(os.Stderr, "gexpoller: cannot create data dir: %v\n", err)
		os.Exit(1)
	}

	if cfg.once {
		runAll(context.Background(), cfg)
		return
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	logf("GEX->ATAS bridge started | assets=%v interval=%s", cfg.assets, cfg.interval)
	logf("data dir: %s", cfg.dataDir)

	for {
		runAll(ctx, cfg)
		select {
		case <-ctx.Done():
			logf("[%s] shutting down", isoUTCNow())
			return
		case <-time.After(cfg.interval):
		}
	}
}
