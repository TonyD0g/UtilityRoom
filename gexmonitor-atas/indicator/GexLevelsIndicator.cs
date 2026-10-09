using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.ComponentModel.DataAnnotations;
using System.Globalization;
using System.IO;
using System.Linq;
using ATAS.Indicators;
using Avalonia.Threading;
using Newtonsoft.Json.Linq;
using OFT.Rendering.Context;
using OFT.Rendering.Settings;
using OFT.Rendering.Tools;

namespace GexLevels
{
    [DisplayName("GEX Levels")]
    [Description(
        "GEX Levels - real-time gamma exposure levels for BTC, ETH and SOL.\n\n" +
        "This indicator draws the key structural price lines from the GEX Monitor " +
        "options analytics page as horizontal line segments on the price chart. " +
        "Each day is drawn as its own segment (segments are not connected across " +
        "UTC days), today's segment moves in real time as the data source updates, " +
        "and past segments stay frozen.\n\n" +
        "Data source: https://gexmonitor.com (public, no login required). A local " +
        "poller (gex_poller.py) fetches the site every few seconds and writes " +
        "per-asset JSON files; this indicator reads those files.\n\n" +
        "Lines:\n" +
        "  FLIP   - Gamma flip point / HVL (sign of total gamma flips here).\n" +
        "  P1/P2  - Call walls (call resistance, nearest / further).\n" +
        "  N1/N2  - Put walls (put support, nearest / further).\n" +
        "  MP     - Max pain strike (hidden by default).\n" +
        "  EM HIGH / EM LOW - 1-day expected move band (hidden by default).\n" +
        "  SPOT   - Real-time spot price.\n\n" +
        "Parameters:\n" +
        "  Auto-detect asset from chart - when on, BTCUSDT/BTC, ETHUSDT/ETH and " +
        "SOLUSDT/SOL charts automatically load their matching data file; other " +
        "instruments display nothing.\n" +
        "  Days JSON file - manual path to a gex_days_<asset>.json file, used when " +
        "auto-detect is off.\n" +
        "  Refresh interval (seconds) - how often the file is checked for updates.\n" +
        "  History days to draw - number of recent day segments to display.\n" +
        "  Show FLIP / P1 / P2 / N1 / N2 / Max Pain / expected move / SPOT - toggle " +
        "individual lines on or off.")]
    public class GexLevelsIndicator : Indicator
    {
        // Shared data directory written by GexPoller: %AppData%\ATAS X\GexData
        private static readonly string DataDir = System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "ATAS X", "GexData");

        private static readonly Dictionary<string, string> AssetMap = new()
        {
            // ATAS instrument -> gexmonitor asset
            ["BTCUSDT"] = "BTC",
            ["BTC"] = "BTC",
            ["ETHUSDT"] = "ETH",
            ["ETH"] = "ETH",
            ["SOLUSDT"] = "SOL",
            ["SOL"] = "SOL",
        };

        private static readonly string[] LevelKeys =
            { "FLIP", "P1", "P2", "N1", "N2", "MP", "EM_HIGH", "EM_LOW", "SPOT" };

        private sealed class LevelDef
        {
            public readonly string Label;
            public readonly ValueDataSeries Series;

            public LevelDef(string label, ValueDataSeries series)
            {
                Label = label;
                Series = series;
            }
        }

        private sealed class Segment
        {
            public string Day;
            public int StartBar;
            public int EndBar;
        }

        private readonly List<LevelDef> _levels = new();
        private readonly List<Segment> _segments = new();

        private Dictionary<string, decimal[]> _days = new(StringComparer.Ordinal);

        private DateTime _lastCheck = DateTime.MinValue;
        private DateTime _lastFileWrite = DateTime.MinValue;
        private string _loadedPath = "";
        private bool _timerStarted;
        private bool _wasLoading = true;
        private bool _segmentsDirty = true;
        private int _barCount = -1;
        private int _lastRebuiltCount = -1;
        private DateTime[] _dates = Array.Empty<DateTime>();

        public GexLevelsIndicator()
        {
            AddLevel("FLIP", "FLIP", System.Drawing.Color.Gold, 2, LineDashStyle.Solid);
            AddLevel("P1", "P1", System.Drawing.Color.LimeGreen, 1, LineDashStyle.Solid);
            AddLevel("P2", "P2", System.Drawing.Color.LimeGreen, 1, LineDashStyle.Dash);
            AddLevel("N1", "N1", System.Drawing.Color.OrangeRed, 1, LineDashStyle.Solid);
            AddLevel("N2", "N2", System.Drawing.Color.OrangeRed, 1, LineDashStyle.Dash);
            AddLevel("MP", "MP", System.Drawing.Color.Gray, 1, LineDashStyle.Dot);
            AddLevel("EM_HIGH", "EM HIGH", System.Drawing.Color.DodgerBlue, 1, LineDashStyle.Dash);
            AddLevel("EM_LOW", "EM LOW", System.Drawing.Color.DodgerBlue, 1, LineDashStyle.Dash);
            AddLevel("SPOT", "SPOT", System.Drawing.Color.White, 1, LineDashStyle.Dot);
        }

        private void AddLevel(string key, string label, System.Drawing.Color color, int width, LineDashStyle style)
        {
            var series = new ValueDataSeries(key, label)
            {
                Color = color,
                Width = width,
                LineDashStyle = style,
                ShowZeroValue = false,
                ShowCurrentValue = false,
                ScaleIt = false
            };
            DataSeries.Add(series);
            _levels.Add(new LevelDef(label, series));
        }

        #region Settings

        [Display(Name = "Auto-detect asset from chart", GroupName = "Data", Order = 0)]
        public bool AutoDetect { get; set; } = true;

        [Display(Name = "Days JSON file", GroupName = "Data", Order = 1)]
        public string FilePath
        {
            get => _filePath;
            set
            {
                _filePath = value;
                _lastFileWrite = DateTime.MinValue;
                _lastCheck = DateTime.MinValue;
            }
        }
        private string _filePath = "";

        private string ResolvedFilePath()
        {
            if (!AutoDetect)
                return FilePath;

            var instr = Instrument ?? "";
            // Try stripping common suffixes if exact match fails.
            if (!AssetMap.TryGetValue(instr, out var asset))
            {
                var baseName = instr
                    .Replace("USDT", "")
                    .Replace("USD", "")
                    .Replace("PERP", "")
                    .Replace("SWAP", "")
                    .Trim();
                if (!AssetMap.TryGetValue(baseName, out asset))
                    return "";
            }
            return System.IO.Path.Combine(DataDir, $"gex_days_{asset}.json");
        }

        [Display(Name = "Refresh interval (seconds)", GroupName = "Data", Order = 2)]
        public int RefreshSeconds
        {
            get => _refreshSeconds;
            set => _refreshSeconds = value < 1 ? 1 : value;
        }
        private int _refreshSeconds = 5;

        [Display(Name = "History days to draw", GroupName = "Display", Order = 5)]
        public int MaxHistoryDays
        {
            get => _maxHistoryDays;
            set => _maxHistoryDays = value < 1 ? 1 : value;
        }
        private int _maxHistoryDays = 2;

        [Display(Name = "Show FLIP / HVL", GroupName = "Levels", Order = 10)]
        public bool ShowFlip { get; set; } = true;

        [Display(Name = "Show P1 (call resistance)", GroupName = "Levels", Order = 11)]
        public bool ShowP1 { get; set; } = true;

        [Display(Name = "Show P2", GroupName = "Levels", Order = 12)]
        public bool ShowP2 { get; set; } = true;

        [Display(Name = "Show N1 (put support)", GroupName = "Levels", Order = 13)]
        public bool ShowN1 { get; set; } = true;

        [Display(Name = "Show N2", GroupName = "Levels", Order = 14)]
        public bool ShowN2 { get; set; } = true;

        [Display(Name = "Show Max Pain", GroupName = "Levels", Order = 15)]
        public bool ShowMp { get; set; } = false;

        [Display(Name = "Show 1D expected move band", GroupName = "Levels", Order = 16)]
        public bool ShowEm { get; set; } = false;

        [Display(Name = "Show SPOT price", GroupName = "Levels", Order = 17)]
        public bool ShowSpot { get; set; } = true;

        #endregion

        private bool LevelEnabled(int index) => index switch
        {
            0 => ShowFlip,
            1 => ShowP1,
            2 => ShowP2,
            3 => ShowN1,
            4 => ShowN2,
            5 => ShowMp,
            6 => ShowEm,
            7 => ShowEm,
            8 => ShowSpot,
            _ => false
        };

        protected override void OnCalculate(int bar, decimal value)
        {
            if (bar == 0 && !_timerStarted)
            {
                _timerStarted = true;
                StartTimer();
            }

            if (bar > _barCount)
                _barCount = bar;

            if (IsLoading)
            {
                _wasLoading = true;
                return;
            }

            if (_wasLoading)
            {
                _wasLoading = false;
                _segmentsDirty = true;
            }

            MaybeReload();

            if (bar != _barCount)
                return;

            EnsureDates(bar + 1);
            var key = _dates[bar].ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
            var lastSegIsCurrent = _segments.Count > 0 && _segments[^1].Day == key;

            if (_segmentsDirty || bar > _lastRebuiltCount + 1 || (!lastSegIsCurrent && _days.ContainsKey(key)))
            {
                RebuildAll();
            }
            else
            {
                if (lastSegIsCurrent)
                    _segments[^1].EndBar = bar;
                WriteDayValues(bar, key);
            }
        }

        private void StartTimer()
        {
            try
            {
                var timer = new DispatcherTimer(TimeSpan.FromSeconds(RefreshSeconds),
                    DispatcherPriority.Background, (s, e) => MaybeReload())
                { };
                timer.Start();
            }
            catch
            {
                // Timer is only a convenience; OnCalculate also reloads on ticks.
            }
        }

        private void MaybeReload()
        {
            var now = DateTime.UtcNow;
            if ((now - _lastCheck).TotalSeconds < RefreshSeconds)
                return;
            _lastCheck = now;

            var path = ResolvedFilePath();
            if (string.IsNullOrEmpty(path))
            {
                // Chart instrument not mapped to a tracked asset: hide all lines.
                if (_days.Count > 0)
                {
                    _days = new Dictionary<string, decimal[]>(StringComparer.Ordinal);
                    _segmentsDirty = true;
                    if (!IsLoading && _barCount >= 0)
                        RebuildAll();
                }
                return;
            }

            // Instrument switched: reset so lines from the old asset don't linger.
            if (_loadedPath != path)
            {
                _loadedPath = path;
                _lastFileWrite = DateTime.MinValue;
                _days = new Dictionary<string, decimal[]>(StringComparer.Ordinal);
                _segmentsDirty = true;
            }

            try
            {
                if (!File.Exists(path))
                    return;

                var writeTime = File.GetLastWriteTimeUtc(path);
                if (writeTime == _lastFileWrite)
                    return;
                _lastFileWrite = writeTime;

                var map = ParseDaysFile(path);
                if (map == null || map.Count == 0)
                    return;

                _days = map;
                _segmentsDirty = true;

                if (!IsLoading && _barCount >= 0)
                {
                    RebuildAll();
                    try
                    {
                        RedrawChart(new RedrawArg(Container.Region));
                    }
                    catch
                    {
                        // Without a forced repaint the next chart refresh applies the levels.
                    }
                }
            }
            catch
            {
                // Keep the previously drawn levels on any parse/IO error.
            }
        }

        private static Dictionary<string, decimal[]> ParseDaysFile(string path)
        {
            var root = JObject.Parse(File.ReadAllText(path));
            var map = new Dictionary<string, decimal[]>(StringComparer.Ordinal);

            if (root["days"] is JObject days)
            {
                foreach (var prop in days.Properties())
                {
                    if (prop.Value is not JObject day || string.IsNullOrEmpty(prop.Name))
                        continue;

                    var vals = new decimal[LevelKeys.Length];
                    var any = false;
                    for (var i = 0; i < LevelKeys.Length; i++)
                    {
                        var v = TryDecimal(day[LevelKeys[i]]);
                        if (v.HasValue && v.Value != 0m)
                        {
                            vals[i] = v.Value;
                            any = true;
                        }
                    }

                    if (any)
                        map[prop.Name] = vals;
                }
            }

            if (map.Count == 0 && root["levels"] is JObject levels)
            {
                // Fallback: legacy/current-day snapshot (gex_levels.json format).
                var vals = new decimal[LevelKeys.Length];
                var any = false;
                for (var i = 0; i < LevelKeys.Length; i++)
                {
                    var v = TryDecimal(levels[LevelKeys[i]]?["price"]);
                    if (v.HasValue && v.Value != 0m)
                    {
                        vals[i] = v.Value;
                        any = true;
                    }
                }

                if (any)
                {
                    var updated = root.Value<string>("updated_at");
                    var key = !string.IsNullOrEmpty(updated) && updated.Length >= 10
                        ? updated.Substring(0, 10)
                        : DateTime.UtcNow.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
                    map[key] = vals;
                }
            }

            return map;
        }

        private static decimal? TryDecimal(JToken token)
        {
            if (token == null)
                return null;
            try
            {
                return token.Value<decimal>();
            }
            catch
            {
                return null;
            }
        }

        private void EnsureDates(int count)
        {
            if (_dates.Length >= count)
                return;

            var old = _dates;
            _dates = new DateTime[count];
            var keep = Math.Min(old.Length, count);
            if (keep > 0)
                Array.Copy(old, _dates, keep);

            for (var b = old.Length; b < count; b++)
                _dates[b] = GetCandle(b).Time.Date;
        }

        private void WriteDayValues(int bar, string key)
        {
            _days.TryGetValue(key, out var vals);
            for (var i = 0; i < _levels.Count; i++)
                _levels[i].Series[bar] = vals != null && LevelEnabled(i) ? vals[i] : 0m;
        }

        private void RebuildAll()
        {
            _segmentsDirty = false;
            _segments.Clear();

            foreach (var lv in _levels)
                lv.Series.Clear();

            if (_barCount < 0)
                return;

            EnsureDates(_barCount + 1);

            if (_days.Count == 0)
            {
                _lastRebuiltCount = _barCount;
                return;
            }

            var drawDays = new HashSet<string>(
                _days.Keys.OrderByDescending(k => k).Take(MaxHistoryDays),
                StringComparer.Ordinal);

            var start = 0;
            while (start <= _barCount)
            {
                var day = _dates[start];
                var end = start;
                while (end + 1 <= _barCount && _dates[end + 1] == day)
                    end++;

                var key = day.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
                if (drawDays.Contains(key) && _days.TryGetValue(key, out var vals))
                {
                    var any = false;
                    for (var b = start; b <= end; b++)
                    {
                        for (var i = 0; i < _levels.Count; i++)
                        {
                            var v = LevelEnabled(i) ? vals[i] : 0m;
                            _levels[i].Series[b] = v;
                            any |= v != 0m;
                        }
                    }

                    if (any)
                        _segments.Add(new Segment { Day = key, StartBar = start, EndBar = end });

                    // 用透明色遮住前一天最后一天连到当天第一天的"竖线/斜线"
                    for (var i = 0; i < _levels.Count; i++)
                        _levels[i].Series.Colors[start] = System.Drawing.Color.Transparent;
                }

                start = end + 1;
            }

            _lastRebuiltCount = _barCount;
        }
    }
}
