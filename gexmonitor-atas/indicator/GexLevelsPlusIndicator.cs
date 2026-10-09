using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.ComponentModel.DataAnnotations;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Globalization;
using System.IO;
using System.Linq;
using ATAS.Indicators;
using Avalonia.Threading;
using Newtonsoft.Json.Linq;
using OFT.Rendering.Context;
using OFT.Rendering.Tools;

namespace GexLevels
{
    [DisplayName("GEX Levels Plus")]
    [Description(
        "GEX Levels Plus - real-time gamma exposure levels for BTC, ETH and SOL, " +
        "with visible move history.\n\n" +
        "Like GEX Levels it shows the key structural price lines from the GEX " +
        "Monitor options analytics page (FLIP, call walls P1/P2, put walls " +
        "N1/N2, Max Pain, the 1-day expected move band and the spot price), " +
        "but instead of a single horizontal line per level it freezes a " +
        "horizontal segment at every price the level has been at: while a " +
        "level keeps its value its line runs horizontally, and when the value " +
        "changes the old segment stays behind at the old price and a new " +
        "segment starts at the new one. You can therefore see exactly at " +
        "which price every line was before a change and where it is now. " +
        "Each segment carries a small price label below the line (historical " +
        "segments show the price only, dimmed), and the newest segment is " +
        "labelled with the level name and its current price and extends to " +
        "the right edge of the chart.\n\n" +
        "Data source: https://gexmonitor.com (public, no login required). A " +
        "local poller (gex_poller.py / GexPoller.exe) fetches the site every " +
        "few seconds and maintains a change-point-compressed history file per " +
        "asset; this indicator reads that file.\n\n" +
        "Lines:\n" +
        "  FLIP   - Gamma flip point / HVL (sign of total gamma flips here).\n" +
        "  P1/P2  - Call walls (call resistance, nearest / further).\n" +
        "  N1/N2  - Put walls (put support, nearest / further).\n" +
        "  MP     - Max pain strike (hidden by default).\n" +
        "  EM HIGH / EM LOW - 1-day expected move band (hidden by default).\n" +
        "  SPOT   - Real-time spot price.\n\n" +
        "Parameters:\n" +
        "  Auto-detect asset from chart - BTCUSDT/BTC, ETHUSDT/ETH and " +
        "SOLUSDT/SOL charts automatically load their matching data file; other " +
        "instruments display nothing.\n" +
        "  Series JSON file - manual path to a gex_series_<asset>.json file, " +
        "used when auto-detect is off.\n" +
        "  Refresh interval (seconds) - how often the file is checked.\n" +
        "  History hours to draw - age limit of the displayed segments.\n" +
        "  Line width - thickness of the level lines.\n" +
        "  Show price-change history (steps) - on by default. Turn it off to " +
        "hide the stair and draw only each level's current price as a single " +
        "horizontal line.\n" +
        "  Show segment labels / Label font size - control the price labels.\n" +
        "  Show FLIP / P1 / P2 / N1 / N2 / Max Pain / expected move / SPOT - " +
        "toggle individual stair lines.")]
    public class GexLevelsPlusIndicator : Indicator
    {
        // Shared data directory written by GexPoller: %AppData%\ATAS X\GexData
        private static readonly string DataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "ATAS X", "GexData");

        private static readonly Dictionary<string, string> AssetMap = new()
        {
            ["BTCUSDT"] = "BTC",
            ["BTC"] = "BTC",
            ["ETHUSDT"] = "ETH",
            ["ETH"] = "ETH",
            ["SOLUSDT"] = "SOL",
            ["SOL"] = "SOL",
        };

        private static readonly string[] LevelKeys =
            { "FLIP", "P1", "P2", "N1", "N2", "MP", "EM_HIGH", "EM_LOW", "SPOT" };

        private static readonly string[] LevelLabels =
            { "FLIP", "P1", "P2", "N1", "N2", "MP", "EM HIGH", "EM LOW", "SPOT" };

        private static readonly Color LabelBg = Color.FromArgb(160, 5, 8, 12);

        private sealed class StepPoint
        {
            public long T;
            public decimal P;
        }

        private sealed class StepSeg
        {
            public int Bar1;
            public int Bar2;
            public decimal Price;
        }

        private Dictionary<string, List<StepPoint>> _data = new(StringComparer.Ordinal);

        // Rebuilt stair segments per level index.
        private readonly List<StepSeg>[] _segments;

        private DateTime _lastCheck = DateTime.MinValue;
        private DateTime _lastFileWrite = DateTime.MinValue;
        private string _loadedPath = "";
        private bool _timerStarted;
        private bool _wasLoading = true;
        private int _barCount = -1;

        private bool _dataDirty = true;
        private int _builtBarCount = -1;
        private int _builtHistHours = -1;
        private int _builtSig = -1;
        private bool _builtSteps = true;

        private static readonly DateTime UnixEpoch =
            new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);

        public GexLevelsPlusIndicator()
        {
            _segments = new List<StepSeg>[LevelKeys.Length];
            for (var i = 0; i < _segments.Length; i++)
                _segments[i] = new List<StepSeg>();

            // Required for OnRender to be called at all (ATAS docs: Drawing basics).
            EnableCustomDrawing = true;
            // LatestBar = redraw on every tick; Final = redraw on every chart repaint.
            SubscribeToDrawingEvents(DrawingLayouts.LatestBar | DrawingLayouts.Final);

            // Pin the indicator to the main candles (price) panel.
            Panel = IndicatorDataProvider.CandlesPanel;
            DenyToChangePanel = true;
            DrawAbovePrice = true;

            // Hide the default (unused) data series so it draws nothing itself.
            if (DataSeries.Count > 0)
                DataSeries[0].IsHidden = true;
        }

        #region Settings

        [Display(Name = "Auto-detect asset from chart", GroupName = "Data", Order = 0)]
        public bool AutoDetect { get; set; } = true;

        [Display(Name = "Series JSON file", GroupName = "Data", Order = 1)]
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
            return Path.Combine(DataDir, $"gex_series_{asset}.json");
        }

        [Display(Name = "Refresh interval (seconds)", GroupName = "Data", Order = 2)]
        public int RefreshSeconds
        {
            get => _refreshSeconds;
            set => _refreshSeconds = value < 1 ? 1 : value;
        }
        private int _refreshSeconds = 3;

        [Display(Name = "Show price-change history (steps)",
            Description = "On (default): every price a level has been at stays " +
                          "on the chart as its own step, so you can see when and " +
                          "where the level changed. Off: only the current price " +
                          "is drawn, as a single horizontal line.",
            GroupName = "Display", Order = 3)]
        public bool ShowHistorySteps { get; set; } = true;

        [Display(Name = "History hours to draw",
            Description = "Steps older than this many hours are not drawn.",
            GroupName = "Display", Order = 5)]
        public int HistoryHours
        {
            get => _historyHours;
            set => _historyHours = value < 1 ? 1 : value;
        }
        private int _historyHours = 720;

        [Display(Name = "Line width", GroupName = "Display", Order = 4)]
        public int LineWidth
        {
            get => _lineWidth;
            set => _lineWidth = value < 1 ? 1 : (value > 6 ? 6 : value);
        }
        private int _lineWidth = 2;

        [Display(Name = "FLIP / HVL color", GroupName = "Colors", Order = 20)]
        public Color FlipColor { get; set; } = Color.Gold;

        [Display(Name = "Call walls P1/P2 color", GroupName = "Colors", Order = 21)]
        public Color CallWallColor { get; set; } = Color.LimeGreen;

        [Display(Name = "Put walls N1/N2 color", GroupName = "Colors", Order = 22)]
        public Color PutWallColor { get; set; } = Color.OrangeRed;

        [Display(Name = "Max pain color", GroupName = "Colors", Order = 23)]
        public Color MaxPainColor { get; set; } = Color.Gray;

        [Display(Name = "Expected move color", GroupName = "Colors", Order = 24)]
        public Color ExpectedMoveColor { get; set; } = Color.DodgerBlue;

        [Display(Name = "SPOT color", GroupName = "Colors", Order = 25)]
        public Color SpotColor { get; set; } = Color.White;

        private Color LevelColor(int index) => index switch
        {
            0 => FlipColor,
            1 => CallWallColor,
            2 => CallWallColor,
            3 => PutWallColor,
            4 => PutWallColor,
            5 => MaxPainColor,
            6 => ExpectedMoveColor,
            7 => ExpectedMoveColor,
            8 => SpotColor,
            _ => Color.Gray
        };

        [Display(Name = "Show segment labels", GroupName = "Labels", Order = 6)]
        public bool ShowLabels { get; set; } = true;

        [Display(Name = "Label font size", GroupName = "Labels", Order = 7)]
        public int LabelFontSize
        {
            get => _labelFontSize;
            set => _labelFontSize = value < 6 ? 6 : (value > 24 ? 24 : value);
        }
        private int _labelFontSize = 9;

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

        private int EnabledSignature()
        {
            var sig = 0;
            for (var i = 0; i < LevelKeys.Length; i++)
                if (LevelEnabled(i))
                    sig |= 1 << i;
            return sig;
        }

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
                _wasLoading = false;

            MaybeReload();
        }

        private void StartTimer()
        {
            try
            {
                var timer = new DispatcherTimer(TimeSpan.FromSeconds(RefreshSeconds),
                    DispatcherPriority.Background, (s, e) => MaybeReload());
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
                if (_data.Count > 0)
                {
                    _data = new Dictionary<string, List<StepPoint>>(StringComparer.Ordinal);
                    _dataDirty = true;
                    ForceRepaint();
                }
                return;
            }

            if (_loadedPath != path)
            {
                _loadedPath = path;
                _lastFileWrite = DateTime.MinValue;
                _data = new Dictionary<string, List<StepPoint>>(StringComparer.Ordinal);
                _dataDirty = true;
            }

            try
            {
                if (!File.Exists(path))
                    return;

                var writeTime = File.GetLastWriteTimeUtc(path);
                if (writeTime == _lastFileWrite)
                    return;
                _lastFileWrite = writeTime;

                ParseFile(path);
                _dataDirty = true;
                ForceRepaint();
            }
            catch
            {
                // Keep the previously drawn steps on any parse/IO error.
            }
        }

        private void ParseFile(string path)
        {
            var root = JObject.Parse(File.ReadAllText(path));
            var data = new Dictionary<string, List<StepPoint>>(StringComparer.Ordinal);

            if (root["series"] is JObject series)
            {
                foreach (var prop in series.Properties())
                {
                    if (prop.Value is not JArray arr || string.IsNullOrEmpty(prop.Name))
                        continue;

                    var pts = new List<StepPoint>();
                    foreach (var token in arr)
                    {
                        if (token is not JArray a || a.Count < 2) continue;
                        var t = a[0].Value<long?>();
                        var p = TryDecimal(a[1]);
                        if (!t.HasValue || !p.HasValue) continue;
                        pts.Add(new StepPoint { T = t.Value, P = p.Value });
                    }

                    if (pts.Count > 0)
                        data[prop.Name] = pts;
                }
            }

            _data = data;
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

        private void ForceRepaint()
        {
            try { RedrawChart(new RedrawArg(Container.Region)); } catch { }
        }

        // Detect whether chart candle timestamps are UTC or local, by comparing
        // the most recent candle against the current time on both scales.
        private bool DetectTimeBasis()
        {
            try
            {
                var candleTime = GetCandle(_barCount).Time;
                var nowUtc = DateTime.UtcNow;
                var nowLocal = nowUtc.ToLocalTime();
                var dUtc = Math.Abs((candleTime - nowUtc).TotalHours);
                var dLocal = Math.Abs((candleTime - nowLocal).TotalHours);
                return dUtc <= dLocal;
            }
            catch
            {
                return true;
            }
        }

        private DateTime PointTime(long epoch, bool utc)
        {
            var dto = DateTimeOffset.FromUnixTimeSeconds(epoch);
            return utc ? dto.UtcDateTime : dto.LocalDateTime;
        }

        private void EnsureSegments()
        {
            var sig = EnabledSignature();
            if (!_dataDirty && _barCount == _builtBarCount &&
                HistoryHours == _builtHistHours && sig == _builtSig &&
                ShowHistorySteps == _builtSteps)
                return;

            _dataDirty = false;

            var utc = DetectTimeBasis();
            _builtBarCount = _barCount;
            _builtHistHours = HistoryHours;
            _builtSig = sig;
            _builtSteps = ShowHistorySteps;

            for (var i = 0; i < LevelKeys.Length; i++)
                _segments[i].Clear();

            if (_barCount < 0)
                return;

            var cutoff = DateTime.UtcNow - TimeSpan.FromHours(HistoryHours);

            for (var li = 0; li < LevelKeys.Length; li++)
            {
                if (!LevelEnabled(li))
                    continue;
                if (!_data.TryGetValue(LevelKeys[li], out var raw) || raw.Count == 0)
                    continue;

                // Effective points inside the history window, keeping one older
                // anchor so the step leading into the window is preserved.
                var times = new List<DateTime>(raw.Count);
                var prices = new List<decimal>(raw.Count);
                for (var k = 0; k < raw.Count; k++)
                {
                    var tUtc = UnixEpoch.AddSeconds(raw[k].T);
                    if (tUtc < cutoff)
                    {
                        // Keep only the last pre-cutoff point as the anchor.
                        times.Clear();
                        prices.Clear();
                        times.Add(PointTime(raw[k].T, utc));
                        prices.Add(raw[k].P);
                        continue;
                    }
                    times.Add(PointTime(raw[k].T, utc));
                    prices.Add(raw[k].P);
                }

                var segs = _segments[li];
                int segStart = -1;
                decimal segPrice = 0m;

                // Bar period from the two most recent candles, used to align
                // samples to the bar they were taken in (bar end, not start).
                var period = _barCount > 0
                    ? GetCandle(_barCount).Time - GetCandle(_barCount - 1).Time
                    : TimeSpan.Zero;
                if (period <= TimeSpan.Zero)
                    period = TimeSpan.FromMinutes(1);

                for (var b = 0; b <= _barCount; b++)
                {
                    var barTime = GetCandle(b).Time;
                    // A sample taken during [barStart, barEnd) belongs to this
                    // bar; align on the bar END, not its start.
                    var barEnd = b < _barCount ? GetCandle(b + 1).Time : barTime + period;

                    // Last point with time <= bar end (right-continuous).
                    var idx = times.BinarySearch(barEnd);
                    if (idx < 0) idx = ~idx - 1;

                    decimal? v;
                    if (idx >= 0)
                    {
                        v = prices[idx];
                    }
                    else if (b == _barCount && times.Count > 0)
                    {
                        // The live bar: the current value is always in effect
                        // here, even if its timestamp is newer than bar end.
                        v = prices[prices.Count - 1];
                    }
                    else
                    {
                        v = null;
                    }

                    if (v.HasValue)
                    {
                        if (segStart < 0)
                        {
                            segStart = b;
                            segPrice = v.Value;
                        }
                        else if (v.Value != segPrice)
                        {
                            segs.Add(new StepSeg { Bar1 = segStart, Bar2 = b - 1, Price = segPrice });
                            segStart = b;
                            segPrice = v.Value;
                        }
                    }
                    else
                    {
                        if (segStart >= 0)
                        {
                            segs.Add(new StepSeg { Bar1 = segStart, Bar2 = b - 1, Price = segPrice });
                            segStart = -1;
                        }
                    }
                }

                if (segStart >= 0)
                    segs.Add(new StepSeg { Bar1 = segStart, Bar2 = _barCount, Price = segPrice });

                if (!ShowHistorySteps)
                {
                    // History module off: drop every past step and keep only the
                    // current value, drawn as one line across the whole chart.
                    // The newest step always ends on the live bar; if it does
                    // not, the level has no current value and nothing is drawn.
                    if (segs.Count > 0 && segs[segs.Count - 1].Bar2 == _barCount)
                    {
                        var current = segs[segs.Count - 1].Price;
                        segs.Clear();
                        segs.Add(new StepSeg { Bar1 = 0, Bar2 = _barCount, Price = current });
                    }
                    else
                    {
                        segs.Clear();
                    }
                }
            }
        }

        protected override void OnRender(RenderContext context, DrawingLayouts layout)
        {
            try
            {
                if (ChartInfo == null || _barCount < 0)
                    return;

                var container = ChartInfo.PriceChartContainer;
                var region = Container.Region;
                if (container == null || region.Width <= 0 || region.Height <= 0)
                    return;

                EnsureSegments();

                var lastBar = _barCount;
                int lastBarEndX = region.Right;
                try { lastBarEndX = (int)container.GetXByBar(lastBar, false); } catch { }

                var font = new RenderFont("Segoe UI", LabelFontSize, FontStyle.Bold);
                var sample = context.MeasureString("0123", font);
                var labelH = sample.Height + 3;
                var drawn = new List<Rectangle>();

                for (var li = 0; li < LevelKeys.Length; li++)
                {
                    if (!LevelEnabled(li))
                        continue;

                    var segs = _segments[li];
                    if (segs.Count == 0)
                        continue;

                    var pen = new RenderPen(LevelColor(li), LineWidth);

                    for (var s = 0; s < segs.Count; s++)
                    {
                        var seg = segs[s];
                        int x1;
                        int x2;
                        try
                        {
                            x1 = (int)container.GetXByBar(seg.Bar1, true);
                            x2 = (int)container.GetXByBar(seg.Bar2, false);
                        }
                        catch
                        {
                            continue;
                        }

                        // The current value extends to the right chart edge.
                        if (seg.Bar2 == lastBar && lastBarEndX < region.Right)
                            x2 = region.Right;

                        if (x2 < region.Left || x1 > region.Right)
                            continue;

                        var y = (int)container.GetYByPrice(seg.Price, false);
                        context.DrawLine(pen, Math.Max(x1, region.Left), y,
                            Math.Min(x2, region.Right), y);

                        if (!ShowLabels)
                            continue;

                        // Label below the line, like the sketch: historical
                        // segments show their own price (dimmed), the newest
                        // segment shows the level name + current price.
                        var isNew = s == segs.Count - 1 && seg.Bar2 == lastBar;
                        var text = isNew
                            ? LevelLabels[li] + " " + FmtPrice(seg.Price)
                            : FmtPrice(seg.Price);
                        var sz = context.MeasureString(text, font);

                        var vx1 = Math.Max(x1, region.Left);
                        var vx2 = Math.Min(x2, region.Right);
                        if (vx2 - vx1 < sz.Width + 4)
                            continue;

                        var lx = (vx1 + vx2) / 2 - sz.Width / 2;
                        lx = Math.Max(region.Left + 2,
                            Math.Min(lx, region.Right - sz.Width - 4));

                        var ly = y + 3;
                        if (ly + labelH > region.Bottom)
                            ly = y - 3 - labelH;

                        var rect = new Rectangle(lx, ly, sz.Width + 4, labelH);
                        for (var attempt = 0; attempt < 4 && Intersects(drawn, rect); attempt++)
                        {
                            rect = new Rectangle(rect.X, rect.Y + labelH + 1,
                                rect.Width, rect.Height);
                            if (rect.Bottom > region.Bottom)
                                break;
                        }
                        if (Intersects(drawn, rect))
                            continue;
                        drawn.Add(rect);

                        var fg = isNew
                            ? LevelColor(li)
                            : Color.FromArgb(170, LevelColor(li));
                        context.FillRectangle(LabelBg, rect);
                        context.DrawString(text, font, fg, rect.X + 2, rect.Y + 1);
                    }
                }
            }
            catch
            {
                // Never let drawing errors affect the platform.
            }
        }

        private static bool Intersects(List<Rectangle> rects, Rectangle r)
        {
            foreach (var o in rects)
                if (o.IntersectsWith(r))
                    return true;
            return false;
        }

        private static string FmtPrice(decimal v)
        {
            return v.ToString("0.##", CultureInfo.InvariantCulture);
        }
    }
}
