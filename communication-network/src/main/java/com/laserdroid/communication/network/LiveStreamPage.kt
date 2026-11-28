package com.laserdroid.communication.network

/**
 * Pages served by [LiveStreamServer]. Self-contained (no Internet needed).
 */
object LiveStreamPage {

    /** Access key entry page. */
    fun login(wrongKey: Boolean): String = LOGIN.replace(
        "{{message}}",
        if (wrongKey) "<p class=\"error\">Wrong key.</p>" else ""
    )

    private val LOGIN: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>LaserDroid live - access key</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
         background: #101214; color: #eceff1; font-family: system-ui, sans-serif; }
  form { background: #16191d; padding: 24px; border-radius: 12px; width: min(90vw, 320px); }
  h1 { margin: 0 0 6px; font-size: 1.3em; }
  p { color: #9aa4ae; margin: 0 0 16px; }
  .error { color: #e53935; font-weight: bold; }
  input { width: 100%; box-sizing: border-box; font-size: 1.6em; letter-spacing: .3em; text-align: center;
          padding: 10px; border-radius: 8px; border: 1px solid #37474f; background: #0b0d0f; color: #fff; }
  button { margin-top: 16px; width: 100%; padding: 12px; font-size: 1.1em; font-weight: bold;
           border: 0; border-radius: 8px; background: #ff7a1a; color: #000; cursor: pointer; }
</style>
</head>
<body>
<form method="get" action="./">
  <h1>LaserDroid live</h1>
  <p>Enter the access key shown on the phone (SERVER tab, Live camera).</p>
  {{message}}
  <input name="key" inputmode="numeric" autocomplete="off" autofocus required>
  <button type="submit">Open</button>
</form>
</body>
</html>
""".trimIndent()

    /** Monitoring page: video, job preview, status, Pause / Stop. */
    val HTML: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>LaserDroid live</title>
<style>
  :root { color-scheme: dark; }
  html, body { height: 100%; }
  body { margin: 0; display: flex; flex-direction: column; background: #101214; color: #eceff1;
         font-family: system-ui, sans-serif; }
  header { display: flex; flex-wrap: wrap; gap: 8px 16px; align-items: center; padding: 8px 12px; background: #16191d; }
  .chip { padding: 3px 10px; border-radius: 6px; font-weight: bold; color: #000; background: #546e7a; }
  .pos { font-family: monospace; font-size: 1.1em; }
  .grow { flex: 1; }
  a { color: #9aa4ae; font-size: .85em; }
  main { flex: 1; min-height: 0; display: flex; gap: 4px; }
  @media (orientation: portrait) { main { flex-direction: column; } }
  .pane { position: relative; flex: 1; min-width: 0; min-height: 0; overflow: hidden;
          display: flex; align-items: center; justify-content: center; background: #000; }
  #videoPane { flex: 3; }
  #previewPane { flex: 2; background: #0b0d0f; display: none; }
  #video { transform-origin: center; }
  #preview { position: absolute; inset: 0; width: 100%; height: 100%; }
  footer { padding: 6px 12px; background: #16191d; }
  .bar { height: 10px; background: #22262b; border-radius: 5px; overflow: hidden; }
  .bar > div { height: 100%; width: 0; background: #ff7a1a; transition: width .5s; }
  .row { display: flex; align-items: center; gap: 10px; margin-top: 6px; }
  .muted { color: #9aa4ae; }
  button { min-width: 120px; padding: 12px 18px; font-size: 1.1em; font-weight: bold; border: 0;
           border-radius: 8px; cursor: pointer; color: #000; }
  button:disabled { opacity: .3; cursor: default; }
  #pause { background: #ffb300; }
  #stop { background: #e53935; color: #fff; }
  #offline, #notice { color: #e53935; }
</style>
</head>
<body>
<header>
  <span id="state" class="chip">…</span>
  <span id="pos" class="pos">X --- Y ---</span>
  <span id="job" class="muted">No job</span>
  <span id="offline" style="display:none">connection lost</span>
  <span class="grow"></span>
  <a href="logout">forget key</a>
</header>
<main>
  <div id="videoPane" class="pane"><img id="video" alt="camera"></div>
  <div id="previewPane" class="pane"><canvas id="preview"></canvas></div>
</main>
<footer>
  <div class="bar"><div id="progress"></div></div>
  <div class="row">
    <button id="pause" disabled>PAUSE</button>
    <button id="stop" disabled>STOP</button>
    <span id="times" class="muted grow"></span>
    <span id="notice"></span>
  </div>
</footer>
<script>
  // Key given in the URL: the server stored it in a cookie, hide it.
  var query = location.search;
  if (query) history.replaceState(null, '', location.pathname);
  function url(path) { return path + query; }

  var video = document.getElementById('video');
  var videoPane = document.getElementById('videoPane');
  var rotation = 0;
  function startVideo() { video.src = url('stream') + (query ? '&' : '?') + 't=' + Date.now(); }
  video.onerror = function () { setTimeout(startVideo, 2000); };
  video.onload = fitVideo;
  startVideo();

  function fitVideo() {
    var w = video.naturalWidth, h = video.naturalHeight;
    if (!w || !h) return;
    var turned = rotation === 90 || rotation === 270;
    var pw = videoPane.clientWidth, ph = videoPane.clientHeight;
    var scale = Math.min(pw / (turned ? h : w), ph / (turned ? w : h));
    video.style.width = (w * scale) + 'px';
    video.style.height = (h * scale) + 'px';
    video.style.transform = 'rotate(' + rotation + 'deg)';
  }

  // ---------- Job preview ----------
  var canvas = document.getElementById('preview');
  var previewPane = document.getElementById('previewPane');
  var preview = null, previewId = null, loadingId = null, last = null;
  var COLORS = ['#5a5a5a', '#4682dc', '#ff9628'];
  var DONE = '#50dc78', HEAD = '#ff2020';

  function loadPreview(id) {
    if (loadingId === id) return;
    loadingId = id;
    fetch(url('preview')).then(function (r) { return r.ok ? r.json() : null; }).then(function (p) {
      loadingId = null;
      preview = p;
      previewId = p ? p.id : null;
      draw();
    }).catch(function () { loadingId = null; });
  }

  function bounds(p) {
    if (p.b) return p.b;
    var s = p.s, b = [Infinity, Infinity, -Infinity, -Infinity];
    for (var i = 0; i < s.length; i += 2) {
      b[0] = Math.min(b[0], s[i]); b[2] = Math.max(b[2], s[i]);
      b[1] = Math.min(b[1], s[i + 1]); b[3] = Math.max(b[3], s[i + 1]);
    }
    return b;
  }

  function draw() {
    var visible = !!(preview && preview.n > 0);
    previewPane.style.display = visible ? 'flex' : 'none';
    fitVideo();
    if (!visible) return;
    var ratio = window.devicePixelRatio || 1;
    var cw = previewPane.clientWidth, ch = previewPane.clientHeight;
    canvas.width = Math.round(cw * ratio);
    canvas.height = Math.round(ch * ratio);
    var g = canvas.getContext('2d');
    g.setTransform(ratio, 0, 0, ratio, 0, 0);
    g.clearRect(0, 0, cw, ch);
    var b = bounds(preview);
    var margin = 14;
    var bw = Math.max(b[2] - b[0], 1e-3), bh = Math.max(b[3] - b[1], 1e-3);
    var scale = Math.min((cw - 2 * margin) / bw, (ch - 2 * margin) / bh);
    var ox = (cw - bw * scale) / 2, oy = (ch - bh * scale) / 2;
    function px(x) { return ox + (x - b[0]) * scale; }
    function py(y) { return ch - oy - (y - b[1]) * scale; }
    var line = last && last.line != null ? last.line : -1;
    var s = preview.s, t = preview.t, l = preview.l;
    g.lineWidth = 1;
    // One path per (type, executed) group: at most six strokes.
    for (var type = 0; type < 3; type++) {
      for (var done = 0; done < 2; done++) {
        g.beginPath();
        var any = false;
        for (var i = 0; i < preview.n; i++) {
          if (t.charCodeAt(i) - 48 !== type) continue;
          if ((l[i] <= line ? 1 : 0) !== done) continue;
          g.moveTo(px(s[i * 4]), py(s[i * 4 + 1]));
          g.lineTo(px(s[i * 4 + 2]), py(s[i * 4 + 3]));
          any = true;
        }
        if (!any) continue;
        g.strokeStyle = done && type !== 0 ? DONE : COLORS[type];
        g.stroke();
      }
    }
    if (last && last.x != null) {
      var hx = px(last.x), hy = py(last.y);
      g.strokeStyle = HEAD;
      g.lineWidth = 2;
      g.beginPath();
      g.moveTo(hx - 8, hy); g.lineTo(hx + 8, hy);
      g.moveTo(hx, hy - 8); g.lineTo(hx, hy + 8);
      g.stroke();
    }
  }
  window.addEventListener('resize', draw);

  // ---------- Status ----------
  var stateColors = { IDLE: '#43a047', RUN: '#1e88e5', JOG: '#1e88e5', HOLD: '#ffb300', ALARM: '#e53935', ERROR: '#e53935' };
  function time(ms) {
    if (ms == null) return '--:--';
    var s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    s = s % 60;
    return (h > 0 ? h + ':' : '') + (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
  }
  function refresh() {
    fetch(url('status')).then(function (r) {
      if (r.status === 401) { location.href = './'; throw new Error('key'); }
      return r.json();
    }).then(function (s) {
      last = s;
      document.getElementById('offline').style.display = 'none';
      var state = document.getElementById('state');
      state.textContent = s.machine;
      state.style.background = stateColors[s.machine] || '#546e7a';
      document.getElementById('pos').textContent =
        'X ' + s.x.toFixed(3) + '  Y ' + s.y.toFixed(3) + (s.zAxis ? '  Z ' + s.z.toFixed(3) : '');
      document.getElementById('job').textContent = s.jobName ? (s.jobName + ' · ' + s.jobState +
        (s.laserOff ? ' · laser off' : '')) : (s.framing ? 'Framing' : 'No job');
      document.getElementById('progress').style.width = (s.percent || 0) + '%';
      document.getElementById('times').textContent = s.jobName ?
        (s.percent.toFixed(1) + ' %   elapsed ' + time(s.elapsedMs) + '   remaining ' + time(s.remainingMs)) : '';
      document.getElementById('pause').disabled = !s.canPause;
      document.getElementById('stop').disabled = !s.canStop;
      if ((s.rotation || 0) !== rotation) { rotation = s.rotation || 0; fitVideo(); }
      if (s.previewId == null) { preview = null; previewId = null; }
      else if (s.previewId !== previewId) loadPreview(s.previewId);
      draw();
    }).catch(function () {
      document.getElementById('offline').style.display = 'inline';
    });
  }
  refresh();
  setInterval(refresh, 1000);

  // ---------- Controls (resume / restart stay on the phone) ----------
  function command(action) {
    var notice = document.getElementById('notice');
    notice.textContent = '';
    fetch(url('control') + (query ? '&' : '?') + 'action=' + action, { method: 'POST' })
      .then(function (r) { return r.json(); })
      .then(function (res) { notice.textContent = res.ok ? '' : res.error; refresh(); })
      .catch(function () { notice.textContent = 'Command failed'; });
  }
  document.getElementById('pause').onclick = function () { command('pause'); };
  document.getElementById('stop').onclick = function () {
    if (confirm('Stop the job? Restarting is only possible on the phone.')) command('stop');
  };
</script>
</body>
</html>
""".trimIndent()
}
