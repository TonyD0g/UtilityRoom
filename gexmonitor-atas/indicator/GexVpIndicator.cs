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
    [DisplayName("GEX Volume Profile")]
    [Description(
        "GEX Volume Profile - a horizontal histogram of the NET GEX profile: one " +
        "bar per option strike, aligned with the price scale.\n\n" +
        "At each strike the bar length is proportional to the strike's NET gamma " +
        "(call GEX minus put GEX). ALL bars extend LEFTWARD from an anchor line on " +
        "the right edge of the strip, regardless of sign; the sign is shown by " +
        "color: positive net = Call GEX color, negative net = Put GEX color. Bar " +
        "lengths auto-scale to the strikes visible in the current price window, and " +
        "the concrete net value is printed at each bar. The strip is flush with " +
        "the right edge of the chart, its background color is configurable, and " +
        "the bars are nearly as thick as the price candles. Data refreshes in " +
        "near real time (every few seconds).\n\n" +
        "Auto-detect asset from chart: BTCUSDT/BTC, ETHUSDT/ETH and SOLUSDT/SOL " +
        "load their matching data file; any other instrument shows nothing.\n\n" +
        "Parameters:\n" +
        "  VP JSON file - manual path, used when auto-detect is off.\n" +
        "  Refresh interval (seconds) - how often the file is checked.\n" +
        "  Strip width - width of the profile strip on the right.\n" +
        "  Bar thickness % - bar height as % of the gap between adjacent strikes.\n" +
        "  Price axis reserve - space reserved for the right-side price labels.\n" +
        "  Bar opacity - transparency of the bars (0-255).\n" +
        "  Positive / Negative net GEX color - bar colors for the two signs.\n" +
        "  Strip background color - color of the strip background.\n" +
        "  Show value labels / spot marker / title - toggle helpers.\n\n" +
        "Data source: https://gexmonitor.com (public, no login required).")]
    public class GexVpIndicator : Indicator
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

        private sealed class VpRow
        {
            public decimal Strike;
            public decimal Call;
            public decimal Put;
            public decimal Net;
        }

        private List<VpRow> _rows = new();
        private decimal _spot;
        private string _updatedAt = "";

        private DateTime _lastCheck = DateTime.MinValue;
        private DateTime _lastFileWrite = DateTime.MinValue;
        private string _loadedPath = "";
        private bool _timerStarted;
        private bool _wasLoading = true;
        private int _barCount = -1;

        #region Settings

        [Display(Name = "Auto-detect asset from chart", GroupName = "Data", Order = 0)]
        public bool AutoDetect { get; set; } = true;

        [Display(Name = "VP JSON file", GroupName = "Data", Order = 1)]
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

        [Display(Name = "Refresh interval (seconds)", GroupName = "Data", Order = 2)]
        public int RefreshSeconds
        {
            get => _refreshSeconds;
            set => _refreshSeconds = value < 1 ? 1 : value;
        }
        private int _refreshSeconds = 5;

        [Display(Name = "Strip width", GroupName = "Layout", Order = 5)]
        public int StripWidth
        {
            get => _stripWidth;
            set => _stripWidth = value < 80 ? 80 : value;
        }
        private int _stripWidth = 230;

        [Display(Name = "Price axis reserve", GroupName = "Layout", Order = 7)]
        public int PriceAxisReserve
        {
            get => _priceAxisReserve;
            set => _priceAxisReserve = value < 0 ? 0 : value;
        }
        private int _priceAxisReserve = 0;

        [Display(Name = "Bar thickness %",
            Description = "Bar height as a percentage of one strike cell (the gap " +
            "between adjacent strikes), 10-100. Higher = thicker bars.",
            GroupName = "Layout", Order = 6)]
        public int BarThickness
        {
            get => _barThickness;
            set => _barThickness = value < 10 ? 10 : (value > 100 ? 100 : value);
        }
        private int _barThickness = 90;

        [Display(Name = "Bar opacity", GroupName = "Layout", Order = 8)]
        public int Opacity
        {
            get => _opacity;
            set => _opacity = value < 0 ? 0 : (value > 255 ? 255 : value);
        }
        private int _opacity = 190;

        [Display(Name = "Strip background color", GroupName = "Colors", Order = 8)]
        public Color BackgroundColor { get; set; } = Color.FromArgb(225, 9, 12, 18);

        [Display(Name = "Positive net GEX color", GroupName = "Colors", Order = 10)]
        public Color CallColor { get; set; } = Color.LimeGreen;

        [Display(Name = "Negative net GEX color", GroupName = "Colors", Order = 11)]
        public Color PutColor { get; set; } = Color.OrangeRed;

        [Display(Name = "Show value labels", GroupName = "Overlays", Order = 20)]
        public bool ShowValues { get; set; } = true;

        [Display(Name = "Show spot marker", GroupName = "Overlays", Order = 21)]
        public bool ShowSpotMarker { get; set; } = true;

        [Display(Name = "Show title", GroupName = "Overlays", Order = 22)]
        public bool ShowTitle { get; set; } = true;

        #endregion

        public GexVpIndicator()
        {
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
            return Path.Combine(DataDir, $"gex_vp_{asset}.json");
        }

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
                if (_rows.Count > 0)
                {
                    _rows = new List<VpRow>();
                    ForceRepaint();
                }
                return;
            }

            if (_loadedPath != path)
            {
                _loadedPath = path;
                _lastFileWrite = DateTime.MinValue;
                _rows = new List<VpRow>();
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
                ForceRepaint();
            }
            catch
            {
                // Keep the previously drawn profile on any parse/IO error.
            }
        }

        private void ParseFile(string path)
        {
            var root = JObject.Parse(File.ReadAllText(path));
            var rows = new List<VpRow>();

            if (root["rows"] is JArray arr)
            {
                foreach (var token in arr)
                {
                    if (token is not JArray a || a.Count < 4) continue;
                    rows.Add(new VpRow
                    {
                        Strike = a[0].Value<decimal>(),
                        Call = a[1].Value<decimal>(),
                        Put = a[2].Value<decimal>(),
                        Net = a[3].Value<decimal>()
                    });
                }
            }

            _rows = rows;
            _spot = root["spot"]?.Value<decimal?>() ?? 0m;
            _updatedAt = root.Value<string>("updated_at") ?? "";
        }

        private void ForceRepaint()
        {
            try { RedrawChart(new RedrawArg(Container.Region)); } catch { }
        }

        protected override void OnRender(RenderContext context, DrawingLayouts layout)
        {
            try
            {
                if (_rows.Count == 0 || ChartInfo == null)
                    return;

                var container = ChartInfo.PriceChartContainer;
                var region = Container.Region;
                if (container == null || region.Width <= 0 || region.Height <= 0)
                    return;

                var stripRight = region.Right - PriceAxisReserve;
                var stripX = stripRight - StripWidth;
                var strip = new Rectangle(stripX, region.Top, StripWidth, region.Height);

                var titleH = ShowTitle ? 18 : 4;
                var contentTop = region.Top + titleH;

                context.SetClip(strip);

                // Background (user-configurable)
                context.FillRectangle(BackgroundColor, strip);

                // Bars all grow LEFT from this anchor flush with the right edge.
                var anchorX = stripRight - 1;
                var maxLen = StripWidth - 6;

                // Title + color legend
                var fontTitle = new RenderFont("Segoe UI", 9, FontStyle.Bold);
                var fontSmall = new RenderFont("Segoe UI", 8);
                if (ShowTitle)
                {
                    context.DrawString("NET GEX PROFILE", fontTitle, Color.White,
                        stripX + 4, region.Top + 3);
                    if (!string.IsNullOrEmpty(_updatedAt))
                    {
                        var t = _updatedAt.Length >= 19
                            ? _updatedAt.Substring(11, 8) + " UTC"
                            : "";
                        var ts = context.MeasureString(t, fontSmall);
                        context.DrawString(t, fontSmall, Color.Gray,
                            anchorX - ts.Width - 4, region.Top + 4);
                    }
                    // Color legend (direction is always leftward; sign by color).
                    context.DrawString("NET+", fontSmall,
                        Color.FromArgb(200, CallColor), stripX + 100, region.Top + 4);
                    context.DrawString("NET-", fontSmall,
                        Color.FromArgb(200, PutColor), stripX + 134, region.Top + 4);
                }

                // Determine scale from visible strikes (net GEX magnitude)
                decimal maxMag = 0m;
                foreach (var r in _rows)
                {
                    var y = container.GetYByPrice(r.Strike, false);
                    if (y < contentTop || y > region.Bottom)
                        continue;
                    var m = Math.Abs(r.Net);
                    if (m > maxMag) maxMag = m;
                }
                if (maxMag <= 0m)
                {
                    context.ResetClip();
                    return;
                }

                // Bar thickness is a percentage of the CELL height, where a cell
                // is the gap between adjacent STRIKES (not between $1 price rows).
                // Build the visible, strike-sorted (high->low) item list first.
                var visible = _rows
                    .Select(r => new
                    {
                        Row = r,
                        Y = (int)container.GetYByPrice(r.Strike, false)
                    })
                    .Where(v => v.Y >= contentTop && v.Y <= region.Bottom)
                    .OrderByDescending(v => v.Row.Strike)
                    .ToList();

                var callFill = Color.FromArgb(Opacity, CallColor);
                var putFill = Color.FromArgb(Opacity, PutColor);

                for (var i = 0; i < visible.Count; i++)
                {
                    var v = visible[i];
                    var r = v.Row;
                    var y = v.Y;

                    // Cell spans halfway to each neighboring strike.
                    var topEdge = contentTop;
                    var botEdge = region.Bottom;
                    if (i > 0)
                        topEdge = (visible[i - 1].Y + y) / 2;
                    if (i < visible.Count - 1)
                        botEdge = (visible[i + 1].Y + y) / 2;

                    var cellH = botEdge - topEdge;
                    if (cellH < 1) cellH = 1;

                    var barH = cellH * BarThickness / 100;
                    if (barH < 1) barH = 1;
                    var barY = y - barH / 2;

                    // Every bar grows LEFT from the anchor regardless of sign.
                    var len = (int)(Math.Abs(r.Net) / maxMag * maxLen);
                    if (len <= 0)
                        continue;

                    var tipX = anchorX - len;
                    context.FillRectangle(r.Net >= 0m ? callFill : putFill,
                        new Rectangle(tipX, barY, len, barH));

                    if (ShowValues && len >= 24 && barH >= 9)
                    {
                        var txt = Compact(r.Net);
                        var signColor = r.Net >= 0m ? CallColor : PutColor;
                        var sz = context.MeasureString(txt, fontSmall);

                        if (tipX - 3 - sz.Width >= stripX + 2)
                        {
                            // Label just outside the left tip, vertically centered.
                            context.DrawString(txt, fontSmall, signColor,
                                tipX - 3 - sz.Width, y - sz.Height / 2);
                        }
                        else if (barH >= 11)
                        {
                            // Not enough room: draw inside the bar near its tip.
                            context.DrawString(txt, fontSmall, Color.White,
                                tipX + 3, y - sz.Height / 2);
                        }
                    }
                }

                // Anchor line (all bars start here)
                context.DrawLine(new RenderPen(Color.FromArgb(170, Color.White), 1f),
                    anchorX, contentTop, anchorX, region.Bottom);

                // Spot marker
                if (ShowSpotMarker && _spot > 0m)
                {
                    var ySpot = container.GetYByPrice(_spot, false);
                    if (ySpot >= contentTop && ySpot <= region.Bottom)
                    {
                        var pen = new RenderPen(Color.White, 1f, DashStyle.Dash);
                        context.DrawLine(pen, stripX, ySpot, stripRight, ySpot);
                        context.DrawString("SPOT", fontSmall, Color.White,
                            stripX + 3, ySpot + 1);
                    }
                }

                // Border
                context.DrawRectangle(new RenderPen(Color.FromArgb(120, Color.Gray), 1f), strip);

                context.ResetClip();
            }
            catch
            {
                // Never let drawing errors affect the platform.
                try { context.ResetClip(); } catch { }
            }
        }

        private static string Compact(decimal v)
        {
            var a = Math.Abs(v);
            if (a >= 1_000_000_000m) return (v / 1_000_000_000m).ToString("0.0", CultureInfo.InvariantCulture) + "B";
            if (a >= 1_000_000m) return (v / 1_000_000m).ToString("0.00", CultureInfo.InvariantCulture) + "M";
            if (a >= 1_000m) return (v / 1_000m).ToString("0.0", CultureInfo.InvariantCulture) + "K";
            return v.ToString("0", CultureInfo.InvariantCulture);
        }
    }
}
