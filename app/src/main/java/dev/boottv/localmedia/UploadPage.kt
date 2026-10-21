package dev.boottv.localmedia

/** The page a phone or computer sees at the TV's address (see [UploadServer]). Self-contained: no outside files. */
internal object UploadPage {
    val html = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Send to BootTV</title>
<style>
  :root { --bg:#141A3C; --card:#212A5E; --raised:#2D3878; --text:#fff; --dim:#B4BEFF; --yellow:#FFCB05; --red:#EE1515; }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font:16px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  main { max-width:560px; margin:0 auto; padding:24px 16px 48px; }
  h1 { font-size:28px; margin:8px 0 4px; color:var(--yellow); text-shadow:2px 2px 0 #2A75BB; }
  p.lead { color:var(--dim); margin:0 0 20px; }
  .card { background:var(--card); border-radius:16px; padding:18px; margin-bottom:14px; }
  label { display:block; font-weight:600; margin-bottom:6px; }
  input[type=text], input[type=tel] { width:100%; padding:12px 14px; border-radius:10px; border:2px solid var(--raised); background:var(--bg); color:var(--text); font-size:17px; }
  input:focus { outline:none; border-color:var(--yellow); }
  .code { letter-spacing:10px; font-size:26px; text-align:center; font-weight:700; }
  .hint { color:var(--dim); font-size:14px; margin-top:6px; }
  .check { display:flex; gap:10px; align-items:center; margin-top:12px; }
  .check input { width:22px; height:22px; accent-color:var(--yellow); }
  .pick { display:block; text-align:center; padding:22px; border:2px dashed var(--raised); border-radius:12px; cursor:pointer; color:var(--dim); }
  .pick input { display:none; }
  button { width:100%; padding:14px; border:0; border-radius:999px; background:var(--yellow); color:#1B1B1F; font-size:18px; font-weight:700; cursor:pointer; }
  button:disabled { opacity:.5; cursor:default; }
  .file { margin-top:10px; }
  .file .name { display:flex; justify-content:space-between; gap:8px; font-size:15px; }
  .bar { height:6px; background:var(--raised); border-radius:3px; margin-top:4px; overflow:hidden; }
  .bar div { height:100%; width:0; background:var(--yellow); transition:width .2s; }
  .ok { color:#7AC74C; } .err { color:#FF7A7A; }
  #msg { margin-top:12px; font-weight:600; }
</style>
</head>
<body>
<main>
  <h1>Send to BootTV</h1>
  <p class="lead">Pick videos on this phone or computer. They play on the TV in My Media, and a folder can become its own channel in Live TV.</p>

  <div class="card">
    <label for="code">Code shown on the TV</label>
    <input id="code" class="code" type="tel" inputmode="numeric" maxlength="4" autocomplete="off" placeholder="0000">
  </div>

  <div class="card">
    <label for="folder">Folder</label>
    <input id="folder" type="text" list="folders" placeholder="e.g. Pokémon, Home movies" autocomplete="off">
    <datalist id="folders"></datalist>
    <div class="hint">Videos are grouped by folder on the TV.</div>
    <div class="check"><input id="channel" type="checkbox" checked><label for="channel" style="margin:0;font-weight:500">Make this folder a channel in Live TV</label></div>
  </div>

  <div class="card">
    <label class="pick" id="pickLabel">Choose videos (and pictures)<input id="files" type="file" multiple accept="video/*,image/*,.mkv,.avi,.ts,.webm"></label>
    <label class="pick" id="dirLabel" style="margin-top:10px">Or choose a whole folder: a season or a show<input id="dir" type="file" webkitdirectory multiple></label>
    <div class="hint">A whole folder keeps its "Season 1", "Season 2" folders. Episodes are recognised from names like S01E03, 1x03 or Episode 3, so the TV plays them in order and one after another. For a poster, add movie.jpg next to movie.mp4, or poster.jpg for the folder.</div>
    <div id="list"></div>
  </div>

  <button id="send" disabled>Send to TV</button>
  <div id="msg"></div>
</main>
<script>
  const ${'$'} = id => document.getElementById(id);
  const code = ${'$'}('code'), folder = ${'$'}('folder'), files = ${'$'}('files'), send = ${'$'}('send'), list = ${'$'}('list'), msg = ${'$'}('msg');
  if (location.hash.length > 1) code.value = location.hash.slice(1, 5);
  let picked = []; // {file, sub}
  const ready = () => { send.disabled = !(code.value.length === 4 && picked.length); };
  code.oninput = () => { ready(); if (code.value.length === 4) loadFolders(); };
  function loadFolders() {
    fetch('/folders', { headers: { 'X-Code': code.value } }).then(r => r.ok ? r.json() : []).then(fs => {
      ${'$'}('folders').innerHTML = fs.map(f => '<option value="' + f.replace(/"/g, '&quot;') + '">').join('');
    }).catch(() => {});
  }
  if (code.value.length === 4) loadFolders();
  const size = n => n > 1e9 ? (n / 1e9).toFixed(1) + ' GB' : (n / 1e6).toFixed(0) + ' MB';
  function show(label, other) {
    ${'$'}(label).firstChild.textContent = picked.length + ' file' + (picked.length === 1 ? '' : 's') + ' chosen (tap to change)';
    ${'$'}(other).firstChild.textContent = other === 'dirLabel' ? 'Or choose a whole folder: a season or a show' : 'Choose videos (and pictures)';
    list.innerHTML = '';
    picked.forEach((p, i) => {
      list.insertAdjacentHTML('beforeend', '<div class="file"><div class="name"><span></span><span id="s' + i + '">' + size(p.file.size) + '</span></div><div class="bar"><div id="b' + i + '"></div></div></div>');
      list.lastChild.querySelector('.name span').textContent = (p.sub ? p.sub + '/' : '') + p.file.name;
    });
    ready();
  }
  files.onchange = () => { picked = [...files.files].map(f => ({ file: f, sub: '' })); show('pickLabel', 'dirLabel'); };
  ${'$'}('dir').onchange = () => {
    const all = [...${'$'}('dir').files].filter(f => !f.name.startsWith('.'));
    // "Pokémon/Season 1/S01E03.mp4": the top folder names the show; what's between goes along as the sub-path.
    const top = all.length ? (all[0].webkitRelativePath || '').split('/')[0] : '';
    if (top && !folder.value.trim()) folder.value = top;
    picked = all.map(f => { const parts = (f.webkitRelativePath || f.name).split('/'); return { file: f, sub: parts.slice(1, -1).join('/') }; });
    show('dirLabel', 'pickLabel');
  };
  function upload(f, i) {
    return new Promise((resolve, reject) => {
      const x = new XMLHttpRequest();
      x.open('POST', '/upload?folder=' + encodeURIComponent(folder.value.trim() || 'Videos') + '&name=' + encodeURIComponent(f.name) + '&path=' + encodeURIComponent(picked[i].sub || ''));
      x.setRequestHeader('X-Code', code.value);
      x.upload.onprogress = e => { if (e.lengthComputable) ${'$'}('b' + i).style.width = (100 * e.loaded / e.total) + '%'; };
      x.onload = () => {
        let r = {}; try { r = JSON.parse(x.responseText); } catch (e) {}
        if (x.status === 200) { ${'$'}('s' + i).textContent = 'On the TV ✓'; ${'$'}('s' + i).className = 'ok'; resolve(); }
        else { ${'$'}('s' + i).textContent = 'Failed'; ${'$'}('s' + i).className = 'err'; reject(r.error || 'Failed'); }
      };
      x.onerror = () => reject('Lost the connection to the TV. Is BootTV still showing Send to TV?');
      x.send(f);
    });
  }
  send.onclick = async () => {
    send.disabled = true; msg.textContent = ''; msg.className = '';
    let failed = 0, lastError = '';
    for (const [i, p] of picked.entries()) {
      try { await upload(p.file, i); } catch (e) { failed++; lastError = e; if (String(e).startsWith('Wrong code')) break; }
    }
    if (failed) { msg.textContent = lastError; msg.className = 'err'; send.disabled = false; return; }
    const done = await fetch('/done?folder=' + encodeURIComponent(folder.value.trim() || 'Videos') + '&channel=' + (${'$'}('channel').checked ? 1 : 0),
      { method: 'POST', headers: { 'X-Code': code.value } }).then(r => r.json()).catch(() => ({}));
    msg.textContent = done.message || done.error || 'All sent! Find them in My Media on the TV.';
    msg.className = done.error ? 'err' : 'ok';
    send.disabled = false;
  };
</script>
</body>
</html>
"""
}
