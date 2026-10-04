/* CivicFix website – talks to the same API as the Android app. No build step. */
"use strict";

const S = { token: localStorage.getItem("cf_token"), user: JSON.parse(localStorage.getItem("cf_user") || "null"), meta: null, unread: 0, timers: [] };
const $ = (sel, root = document) => root.querySelector(sel);
const view = () => $("#view");

/* ---------- helpers ---------- */
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmt = (ms) => ms ? new Date(ms).toLocaleString("en-IN", { day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" }) : "–";
const cat = (k) => (S.meta?.categories?.[k]) || { label: k, emoji: "📍" };
function toast(msg) { const t = $("#toast"); t.textContent = msg; t.classList.add("show"); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove("show"), 3500); }
function clearTimers() { S.timers.forEach(clearInterval); S.timers = []; }

async function api(path, opts = {}) {
  const headers = opts.headers || {};
  if (S.token) headers.Authorization = `Bearer ${S.token}`;
  let body = opts.body;
  if (body && !(body instanceof FormData)) { headers["Content-Type"] = "application/json"; body = JSON.stringify(body); }
  const r = await fetch(path, { method: opts.method || (body ? "POST" : "GET"), headers, body });
  if (r.status === 401 && S.token) { logout(); throw new Error("Please sign in again"); }
  const data = r.headers.get("content-type")?.includes("json") ? await r.json() : null;
  if (!r.ok) {
    const d = data?.detail;
    throw new Error(Array.isArray(d) ? d.map((e) => `${e.loc?.slice(-1)[0]}: ${e.msg}`).join(", ") : (d || `Error ${r.status}`));
  }
  return data;
}

function setSession(token, user) {
  S.token = token; S.user = user;
  localStorage.setItem("cf_token", token); localStorage.setItem("cf_user", JSON.stringify(user));
}
function logout() { S.token = null; S.user = null; localStorage.removeItem("cf_token"); localStorage.removeItem("cf_user"); location.hash = "#/"; renderNav(); }
const isStaff = () => ["officer", "supervisor", "admin"].includes(S.user?.role);

function statusPill(c) {
  const map = { NEW: ["New", "p-info"], ASSIGNED: ["Assigned", "p-warn"], IN_PROGRESS: ["In progress", "p-warn"], RESOLVED: ["Resolved – verify", "p-ok"], CLOSED: ["Closed", "p-ok"], REOPENED: ["Reopened", "p-danger"] };
  const [l, k] = map[c.status] || [c.status, "p-info"];
  let out = `<span class="pill ${k}">${l}</span>`;
  if (c.overdue) out += ` <span class="pill p-danger">Overdue</span>`;
  if (c.escalationLevel >= 2) out += ` <span class="pill p-danger">Escalated L${c.escalationLevel}</span>`;
  else if (c.escalationLevel === 1) out += ` <span class="pill p-warn">Warned</span>`;
  return out;
}

function countdown(due, status) {
  if (["RESOLVED", "CLOSED"].includes(status)) return `<span class="muted">done</span>`;
  const now = Date.now() + (S.offset || 0);
  let d = due - now; const late = d < 0; d = Math.abs(d);
  const h = Math.floor(d / 3.6e6), m = Math.floor((d % 3.6e6) / 6e4);
  const txt = h >= 48 ? `${Math.floor(h / 24)}d ${h % 24}h` : `${h}h ${m}m`;
  return `<span class="countdown" style="color:${late ? "var(--danger)" : h < 6 ? "var(--warn)" : "var(--ok)"}">${late ? "late by " : ""}${txt}${late ? "" : " left"}</span>`;
}

function thumb(c) {
  return c.beforePhoto ? `<img class="thumb" src="${esc(c.beforePhoto)}" alt="">` : `<div class="thumb">${cat(c.category).emoji}</div>`;
}

function citem(c) {
  return `<a class="citem" href="#/c/${esc(c.id)}">${thumb(c)}<div style="flex:1;min-width:0">
    <div class="t">${esc(cat(c.category).label)}</div>
    <div class="small muted" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">📍 ${esc(c.address || c.locationLabel)}</div>
    <div class="row" style="margin-top:4px">${statusPill(c)}</div>
    <div class="small muted">${esc(c.id)} · ${countdown(c.dueAt, c.status)}${c.supportCount ? ` · 👥 +${c.supportCount}` : ""}</div></div></a>`;
}

/* ---------- navigation ---------- */
function renderNav() {
  const u = S.user;
  const links = [`<a href="#/">Home</a>`, `<a href="#/map">Map</a>`];
  if (!u || u.role === "citizen") links.push(`<a href="#/report">Report a problem</a>`);
  if (u) links.push(`<a href="#/dashboard">${u.role === "citizen" ? "My complaints" : "Dashboard"}</a>`);
  if (u) links.push(`<a class="bell" href="#/notifications">🔔${S.unread ? `<span class="count">${S.unread}</span>` : ""}</a>`);
  links.push(u ? `<button id="logout" title="${esc(u.designation || u.role)}">${esc(u.name)} · Sign out</button>` : `<a href="#/login">Sign in</a>`);
  $("#nav").innerHTML = links.join("");
  $("#logout")?.addEventListener("click", logout);
  const here = location.hash.split("/")[1] || "";
  document.querySelectorAll("#nav a").forEach((a) => a.classList.toggle("active", (a.getAttribute("href").split("/")[1] || "") === here));
}

async function pollMe() {
  if (!S.token) return;
  try { const me = await api("/api/me"); S.unread = me.unreadNotifications; S.offset = me.serverTime - Date.now(); S.user = me.user; renderNav(); } catch { /* offline */ }
}

const routes = {
  "": home, map: mapPage, login: loginPage, register: registerPage, report: reportPage,
  c: complaintPage, dashboard: dashboard, notifications: notificationsPage,
};

async function router() {
  clearTimers();
  const [, page = "", arg] = location.hash.split("/");
  renderNav();
  view().innerHTML = `<div class="loading">Loading…</div>`;
  try { await (routes[page] || home)(arg); } catch (e) { view().innerHTML = `<div class="card">⚠️ ${esc(e.message)}</div>`; }
  window.scrollTo(0, 0);
}

/* ---------- maps ---------- */
function leaflet(el, lat = 28.6139, lng = 77.209, zoom = 11) {
  const m = L.map(el).setView([lat, lng], zoom);
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap contributors" }).addTo(m);
  return m;
}
const catColor = { pothole_road_damage: "#8d5b3f", streetlight: "#e39b00", water_leakage: "#0284c7", drainage: "#5b5bd6", garbage: "#2e8b57", road_blockage: "#e2571e", damaged_infrastructure: "#5e6b7a", other: "#8b5cf6" };
function dot(lat, lng, color, r = 9) { return L.circleMarker([lat, lng], { radius: r, color: "#fff", weight: 2, fillColor: color, fillOpacity: .9 }); }

/* ---------- pages ---------- */
async function home() {
  const [pub, stats] = await Promise.all([api("/api/public/complaints"), S.token ? api("/api/stats").catch(() => null) : null]);
  const open = pub.filter((c) => !["RESOLVED", "CLOSED"].includes(c.status));
  view().innerHTML = `
  <section class="hero">
    <div><h1>Report it. We route it.<br>Officers fix it – on a deadline.</h1>
      <p>Potholes, broken streetlights, sewer overflows, leaks, garbage, blocked roads in Delhi. AI recognises the problem from your photo, sends it to the right department and escalates it automatically if the deadline is missed.</p>
      <div class="row"><a class="btn" href="#/report">📸 Report a problem</a><a class="btn secondary" href="#/map">See the live map</a></div></div>
    <div class="grid g4" style="grid-template-columns:1fr 1fr">
      ${[["Reported", pub.length], ["Open", open.length], ["Overdue", pub.filter((c) => c.overdue).length], ["+1 supports", pub.reduce((a, c) => a + c.supportCount, 0)]]
        .map(([l, v]) => `<div class="card stat" style="background:rgba(255,255,255,.12);border:0;color:#fff;box-shadow:none"><div class="v">${v}</div><div class="l" style="color:#e0f2ee">${l}</div></div>`).join("")}
    </div>
  </section>
  <div class="grid g2" style="margin-top:18px">
    <div class="card"><h3><span class="badge-ico">🗺️</span>Open problems near you</h3><div id="homemap" class="map sm"></div></div>
    <div class="card stack"><h3><span class="badge-ico">🧭</span>How a complaint moves</h3>
      <ol class="small" style="margin:0;padding-left:18px">
        <li><b>Report</b> with photo + location – AI picks the category; nearby duplicates are suggested so you can just add +1.</li>
        <li><b>Route</b> to the responsible Delhi agency (MCD, DJB, PWD…) and assigned to a field officer with a <b>deadline</b>.</li>
        <li><b>Deadline missed</b> → officer gets a warning, supervisor is informed.</li>
        <li><b>Late by 2× the allowed time</b> → moves to the higher supervisor; still late → head of department.</li>
        <li><b>Resolve</b> only with a <b>live photo and video</b> taken at the spot; you confirm whether it is really fixed.</li>
      </ol></div>
  </div>
  <div class="card" style="margin-top:14px"><h3><span class="badge-ico">🏛️</span>Who handles what in Delhi</h3><div class="tbl-wrap"><table class="tbl">
    <tr><th>Problem</th><th>Agency</th><th>Officer chain (escalation)</th><th>Deadline (high / medium / low)</th></tr>
    ${Object.entries(S.meta.categories).map(([k, c]) => { const w = S.meta.workflows[c.department]; return `<tr><td>${c.emoji} ${esc(c.label)}</td><td>${esc(w.agency)}${w.alt_agency ? `<div class="small muted">${esc(w.alt_agency)}</div>` : ""}</td><td class="small">${w.chain.map(esc).join(" → ")}</td><td class="small">${w.sla_hours.high} h / ${w.sla_hours.medium} h / ${w.sla_hours.low} h</td></tr>`; }).join("")}
  </table></div></div>`;
  const m = leaflet($("#homemap"));
  open.forEach((c) => c.lat && dot(c.lat, c.lng, catColor[c.category]).addTo(m).bindPopup(`<b>${esc(c.categoryLabel)}</b><br>${esc(c.address)}<br>${esc(c.id)} · 👥 ${c.supportCount}`));
}

async function mapPage() {
  const pub = await api("/api/public/complaints");
  view().innerHTML = `<div class="card"><div class="row"><h2 style="margin:0">Live complaint map</h2><span class="spacer"></span>
    <label class="small"><input type="checkbox" id="showdone" style="width:auto"> show resolved</label></div>
    <div class="row small" style="margin:8px 0">${Object.entries(S.meta.categories).map(([k, c]) => `<span><span style="color:${catColor[k]}">●</span> ${esc(c.label)}</span>`).join(" ")}</div>
    <div id="bigmap" class="map" style="height:70vh"></div></div>`;
  const m = leaflet($("#bigmap"));
  const layer = L.layerGroup().addTo(m);
  const draw = () => {
    layer.clearLayers();
    pub.filter((c) => c.lat && ($("#showdone").checked || !["RESOLVED", "CLOSED"].includes(c.status)))
      .forEach((c) => dot(c.lat, c.lng, catColor[c.category], 8 + Math.min(c.supportCount, 6)).addTo(layer)
        .bindPopup(`<b>${esc(c.categoryLabel)}</b> ${c.overdue ? "⏰" : ""}<br>${esc(c.address)}<br>${esc(c.agency)}<br>${esc(c.id)} · ${esc(c.status)} · 👥 ${c.supportCount}`));
  };
  $("#showdone").addEventListener("change", draw); draw();
}

function loginPage() {
  view().innerHTML = `<div class="card auth stack"><h2>Sign in</h2>
    <p class="muted small">Citizens, officers, supervisors and administrators use the same sign-in.</p>
    <form id="f" class="stack"><label class="field">Name<input name="name" autocomplete="username" required></label>
    <label class="field">Password<input name="password" type="password" autocomplete="current-password" required></label>
    <div id="err"></div><button class="btn block">Sign in</button></form>
    <p class="small">New citizen? <a href="#/register">Create an account</a></p></div>`;
  $("#f").addEventListener("submit", async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    try { const r = await api("/api/auth/login", { body: { name: f.get("name"), password: f.get("password") } }); setSession(r.token, r.user); location.hash = "#/dashboard"; }
    catch (err) { $("#err").innerHTML = `<div class="banner b-danger">⚠️ ${esc(err.message)}</div>`; }
  });
}

function registerPage() {
  view().innerHTML = `<div class="card auth stack"><h2>Create a citizen account</h2>
    <form id="f" class="stack"><label class="field">Name<input name="name" minlength="3" required></label>
    <label class="field">Phone number (optional)<input name="phone" type="tel" placeholder="Lets the department call you"></label>
    <label class="field">Password (min. 6 characters)<input name="password" type="password" minlength="6" required></label>
    <label class="field">Confirm password<input name="confirm" type="password" required></label>
    <div id="err"></div><button class="btn block">Create account</button></form>
    <p class="small muted">Officer and supervisor accounts are created by the administrator.</p></div>`;
  $("#f").addEventListener("submit", async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    if (f.get("password") !== f.get("confirm")) { $("#err").innerHTML = `<div class="banner b-danger">Passwords do not match</div>`; return; }
    try { const r = await api("/api/auth/register", { body: { name: f.get("name"), password: f.get("password"), phone: f.get("phone") || null } }); setSession(r.token, r.user); location.hash = "#/report"; }
    catch (err) { $("#err").innerHTML = `<div class="banner b-danger">⚠️ ${esc(err.message)}</div>`; }
  });
}

/* ---------- report ---------- */
async function reportPage() {
  if (!S.token) { location.hash = "#/login"; toast("Sign in or create an account to report"); return; }
  const st = { lat: null, lng: null, photo: null, category: null, severity: "medium", ranked: null };
  view().innerHTML = `<h2>Report a problem</h2><div class="grid g2">
   <div class="card stack"><h3><span class="badge-ico">📍</span>Where?</h3>
     <div class="row"><button class="btn secondary sm" id="gps">🛰️ Use my location</button>
       <input id="q" placeholder="Search or type the address" style="flex:1;margin:0"><button class="btn sm" id="find">Find</button></div>
     <div id="results"></div><div id="pick" class="map sm"></div>
     <label class="field">Address<textarea id="address" rows="2" placeholder="Filled from the map – edit if needed"></textarea></label>
     <label class="field">Nearby landmark (optional)<input id="landmark"></label></div>
   <div class="card stack"><h3><span class="badge-ico">📷</span>What?</h3>
     <label class="field">Photo<input id="photo" type="file" accept="image/*" capture="environment"></label>
     <img id="preview" class="media hidden" alt="">
     <div id="ai"></div>
     <div class="cat-grid" id="cats"></div>
     <label class="field">Severity<select id="sev"><option value="low">Low</option><option value="medium" selected>Medium</option><option value="high">High / safety risk</option></select></label>
     <label class="field">Describe the problem<textarea id="desc" rows="3" placeholder="e.g. Sewer overflowing near the school gate since 2 days"></textarea></label></div></div>
   <div id="dups" style="margin-top:14px"></div>
   <div class="card" style="margin-top:14px"><div id="err"></div><button class="btn block" id="submit">✅ Submit complaint</button></div>`;

  const m = leaflet($("#pick"));
  let marker = null;
  const setPin = async (lat, lng, addr) => {
    st.lat = lat; st.lng = lng;
    marker ? marker.setLatLng([lat, lng]) : (marker = L.marker([lat, lng], { draggable: true }).addTo(m).on("dragend", (e) => setPin(e.target.getLatLng().lat, e.target.getLatLng().lng)));
    m.setView([lat, lng], Math.max(m.getZoom(), 17));
    if (addr) $("#address").value = addr;
    else { try { const r = await (await fetch(`https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${lat}&lon=${lng}`)).json(); if (r.display_name) $("#address").value = r.display_name; } catch {} }
    checkDups();
  };
  m.on("click", (e) => setPin(e.latlng.lat, e.latlng.lng));
  $("#gps").onclick = () => navigator.geolocation?.getCurrentPosition((p) => setPin(p.coords.latitude, p.coords.longitude), () => toast("Location not available – tap the map"));
  $("#find").onclick = async () => {
    const q = $("#q").value.trim(); if (q.length < 3) return;
    const res = await (await fetch(`https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&countrycodes=in&q=${encodeURIComponent(q)}`)).json();
    if (!res.length) { $("#results").innerHTML = `<div class="banner b-warn small">Not found on the map – the address is kept as typed; tap the map to place the pin.</div>`; $("#address").value = q; return; }
    $("#results").innerHTML = res.map((r, i) => `<button class="cat small" data-i="${i}" style="width:100%;margin-top:4px">📍 ${esc(r.display_name)}</button>`).join("");
    $("#results").querySelectorAll("button").forEach((b) => b.onclick = () => { const r = res[b.dataset.i]; $("#results").innerHTML = ""; setPin(+r.lat, +r.lon, r.display_name); });
  };
  $("#q").addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); $("#find").click(); } });

  const drawCats = () => {
    const p = Object.fromEntries((st.ranked || []).map((r) => [r.category, r.p]));
    $("#cats").innerHTML = Object.entries(S.meta.categories).map(([k, c]) => `<button class="cat ${st.category === k ? "on" : ""}" data-k="${k}">
      <div class="e">${c.emoji}</div><div style="font-weight:700">${esc(c.label)}</div>${p[k] >= .05 ? `<div class="small" style="color:var(--teal)">AI ${Math.round(p[k] * 100)}%</div>` : ""}</button>`).join("");
    $("#cats").querySelectorAll(".cat").forEach((b) => b.onclick = () => { st.category = b.dataset.k; drawCats(); checkDups(); });
  };
  drawCats();

  $("#photo").addEventListener("change", async (e) => {
    st.photo = e.target.files[0] || null;
    if (!st.photo) return;
    $("#preview").src = URL.createObjectURL(st.photo); $("#preview").classList.remove("hidden");
    $("#ai").innerHTML = `<div class="banner b-info small">🤖 Analysing the photo…</div>`;
    try {
      const fd = new FormData(); fd.append("photo", st.photo);
      st.ranked = (await api("/api/ai/classify", { body: fd })).ranked;
      const top = st.ranked[0]; st.category = top.category;
      $("#ai").innerHTML = top.p < .4 ? `<div class="banner b-warn small">🤖 Not sure – please pick the category.</div>`
        : top.category === "other" ? `<div class="banner b-warn small">🔍 The photo does not clearly show a civic problem.</div>`
        : `<div class="banner b-ok small">🤖 Looks like <b>${esc(top.label)}</b> (${Math.round(top.p * 100)}%)</div>`;
    } catch (err) { $("#ai").innerHTML = `<div class="banner b-warn small">${esc(err.message)}</div>`; }
    drawCats(); checkDups();
  });

  async function checkDups() {
    if (st.lat == null) return;
    const fd = new FormData(); fd.append("lat", st.lat); fd.append("lng", st.lng); fd.append("radius", 100);
    if (st.category) fd.append("category", st.category);
    if (st.photo) fd.append("photo", st.photo);
    const { matches } = await api("/api/complaints/check-duplicates", { body: fd });
    $("#dups").innerHTML = !matches.length ? "" : `<div class="card stack" style="border-color:var(--warn)"><h3><span class="badge-ico">👥</span>Already reported nearby?</h3>
      <p class="small muted">These open complaints are within 100 m${st.photo ? " and the AI compared the photos" : ""}. If it is the same problem, add your +1 instead – it raises the priority.</p>
      ${matches.map((c) => `<div class="row">${citem(c)}<div class="stack" style="min-width:160px"><span class="pill ${c.verdict === "same" ? "p-danger" : "p-warn"}">${c.verdict === "same" ? "Very likely the same" : "Possibly the same"}</span>
        <span class="small muted">${c.distanceM} m away${c.similarity != null ? ` · photo match ${Math.round(c.similarity * 100)}%` : ""}</span>
        <button class="btn sm" data-sup="${esc(c.id)}" ${c.hasSupported || c.isMine ? "disabled" : ""}>👍 +1 (${c.supportCount})</button></div></div>`).join("")}</div>`;
    $("#dups").querySelectorAll("[data-sup]").forEach((b) => b.onclick = async () => { await api(`/api/complaints/${b.dataset.sup}/support`, { method: "POST" }); toast("Thanks – your +1 was added"); location.hash = `#/c/${b.dataset.sup}`; });
  }

  $("#submit").onclick = async () => {
    if (st.lat == null) { $("#err").innerHTML = `<div class="banner b-danger">Choose the location on the map first</div>`; return; }
    const fd = new FormData();
    Object.entries({ lat: st.lat, lng: st.lng, address: $("#address").value, landmark: $("#landmark").value, description: $("#desc").value, severity: $("#sev").value })
      .forEach(([k, v]) => fd.append(k, v));
    if (st.category) fd.append("category", st.category);
    if (st.photo) fd.append("photo", st.photo);
    $("#submit").disabled = true;
    try { const c = await api("/api/complaints", { body: fd }); toast(`Complaint ${c.id} submitted`); location.hash = `#/c/${c.id}`; }
    catch (err) { $("#err").innerHTML = `<div class="banner b-danger">⚠️ ${esc(err.message)}</div>`; $("#submit").disabled = false; }
  };
}

/* ---------- complaint detail ---------- */
async function complaintPage(id) {
  if (!S.token) { location.hash = "#/login"; return; }
  const c = await api(`/api/complaints/${encodeURIComponent(id)}`);
  const u = S.user, ce = cat(c.category);
  const person = (p, role) => p ? `<div><div class="small muted">${role}</div><b>${esc(p.name)}</b><div class="small">${esc(p.designation || "")}</div></div>` : "";
  view().innerHTML = `
  <div class="row"><a href="javascript:history.back()">← Back</a></div>
  <div class="grid g2" style="margin-top:8px">
    <div class="stack">
      <div class="card stack"><div class="row"><h2 style="margin:0">${ce.emoji} ${esc(ce.label)}</h2><span class="spacer"></span><span class="muted">${esc(c.id)}</span></div>
        <div class="row">${statusPill(c)} <span class="pill p-info">severity ${esc(c.severity)}</span>${c.supportCount ? `<span class="pill p-info">👥 ${c.supportCount} more citizen(s)</span>` : ""}</div>
        ${c.beforePhoto ? `<img class="media" src="${esc(c.beforePhoto)}" alt="Reported photo">` : ""}
        <div>${esc(c.description || "(no description)")}</div>
        <div class="small muted">Reported by ${esc(c.citizenName)} · ${fmt(c.createdAt)}${c.citizenPhone ? ` · 📞 <a href="tel:${esc(c.citizenPhone)}">${esc(c.citizenPhone)}</a>` : ""}</div>
        ${c.aiSummary ? `<div class="banner b-info small">🤖 ${esc(c.aiSummary)}</div>` : ""}</div>
      <div class="card stack"><h3><span class="badge-ico">📍</span>Location</h3><div id="cmap" class="map sm"></div>
        <div>${esc(c.address || "")}</div><div class="small muted">Ward office: ${esc(c.locationLabel)}${c.landmark ? ` · near ${esc(c.landmark)}` : ""}</div>
        ${c.lat ? `<a class="small" target="_blank" rel="noopener" href="https://www.google.com/maps/search/?api=1&query=${c.lat},${c.lng}">Open in Google Maps ↗</a>` : ""}</div>
    </div>
    <div class="stack">
      <div class="card stack"><h3><span class="badge-ico">⏱️</span>Deadline</h3>
        <div class="row"><div><div class="small muted">Due</div><b>${fmt(c.dueAt)}</b></div><span class="spacer"></span><div id="cd">${countdown(c.dueAt, c.status)}</div></div>
        <div class="small muted">${esc(c.agency)} · target ${c.slaHours} h for ${esc(c.severity)} severity</div>
        <div class="grid g3">${person(c.officer, "Assigned officer")}${person(c.supervisor, "Supervisor")}${person(c.escalatedTo, "Escalated to")}</div></div>
      ${c.afterPhoto || c.afterVideo ? `<div class="card stack"><h3><span class="badge-ico">🎥</span>Completion proof (live)</h3>
        <div class="grid g2">${c.afterPhoto ? `<img class="media" src="${esc(c.afterPhoto)}" alt="After photo">` : ""}${c.afterVideo ? `<video class="media" src="${esc(c.afterVideo)}" controls playsinline></video>` : ""}</div>
        ${c.actionTaken ? `<div><b>Action taken:</b> ${esc(c.actionTaken)}</div>` : ""}
        ${c.proofCheck ? `<div class="small">${esc(c.proofCheck)}</div>` : ""}${c.afterPhotoAiCheck ? `<div class="small">${esc(c.afterPhotoAiCheck)}</div>` : ""}</div>` : ""}
      <div id="actions"></div>
      <div class="card"><h3><span class="badge-ico">🕒</span>Timeline</h3><ul class="timeline">
        ${c.timeline.map((e) => `<li><b>${esc(e.title)}</b>${e.note ? `<div class="small">${esc(e.note)}</div>` : ""}<div class="small muted">${fmt(e.time)} · ${esc(e.actor)}</div></li>`).join("")}</ul></div>
    </div></div>`;
  if (c.lat) { const m = leaflet($("#cmap"), c.lat, c.lng, 17); L.marker([c.lat, c.lng]).addTo(m); }
  S.timers.push(setInterval(() => { const el = $("#cd"); if (el) el.innerHTML = countdown(c.dueAt, c.status); }, 30000));
  renderActions(c, u);
}

function renderActions(c, u) {
  const box = $("#actions"); const open = ["NEW", "ASSIGNED", "IN_PROGRESS", "REOPENED"].includes(c.status);
  const reload = () => complaintPage(c.id);
  if (u.role === "citizen") {
    if (c.isMine && c.status === "RESOLVED") {
      box.innerHTML = `<div class="card stack" style="border-color:var(--ok)"><h3><span class="badge-ico">✅</span>Is the problem actually fixed?</h3>
        <p class="small">Compare the before photo with the live photo/video of the completed work.</p>
        <div class="row"><button class="btn ok" id="yes">Yes, fixed</button><button class="btn danger" id="no">No, not fixed</button></div>
        <input id="why" placeholder="If not fixed, what is still wrong? (optional)"></div>`;
      $("#yes").onclick = async () => { await api(`/api/complaints/${c.id}/verify`, { body: { fixed: true } }); toast("Thank you – closed"); reload(); };
      $("#no").onclick = async () => { await api(`/api/complaints/${c.id}/verify`, { body: { fixed: false, reason: $("#why").value } }); toast("Reopened and escalated"); reload(); };
    } else if (!c.isMine && open) {
      box.innerHTML = `<div class="card"><button class="btn block" id="sup" ${c.hasSupported ? "disabled" : ""}>${c.hasSupported ? "You supported this (+1)" : "👍 I'm facing this too (+1)"}</button></div>`;
      $("#sup").onclick = async () => { await api(`/api/complaints/${c.id}/support`, { method: "POST" }); toast("+1 added"); reload(); };
    }
    return;
  }
  if (!open) return;
  const isSup = ["supervisor", "admin"].includes(u.role);
  box.innerHTML = `<div class="card stack"><h3><span class="badge-ico">🛠️</span>Officer actions</h3>
    ${["NEW", "ASSIGNED", "REOPENED"].includes(c.status) ? `<div class="row"><input id="team" placeholder="Field team (optional)" style="flex:1;margin:0"><button class="btn" id="start">Start work</button></div>` : ""}
    <details ${c.status === "IN_PROGRESS" ? "open" : ""}><summary><b>Mark resolved – live photo + video required</b></summary><div class="stack" style="margin-top:10px">
      <div class="banner b-info small">📱 Open this page on your phone at the site: the buttons open the camera directly. Your GPS position and the capture time are checked.</div>
      <label class="field">Live photo of the completed work<input id="aphoto" type="file" accept="image/*" capture="environment"></label>
      <label class="field">Live video of the completed work<input id="avideo" type="file" accept="video/*" capture="environment"></label>
      <label class="field">Action taken<textarea id="act" rows="2"></textarea></label>
      <button class="btn ok" id="resolve">Upload proof & mark resolved</button></div></details>
    ${isSup ? `<button class="btn secondary" id="assign">👤 Assign / change deadline</button>` : ""}
    <label class="field">Correct category (re-routes the complaint)<select id="recat">${Object.entries(S.meta.categories).map(([k, x]) => `<option value="${k}" ${k === c.category ? "selected" : ""}>${x.emoji} ${esc(x.label)}</option>`).join("")}</select></label>
    <div id="aerr"></div></div>`;
  $("#start")?.addEventListener("click", async () => { await api(`/api/complaints/${c.id}/start`, { body: { team: $("#team").value || null } }); toast("Work started"); reload(); });
  $("#recat").onchange = async (e) => { if (confirm("Re-route this complaint to another department?")) { await api(`/api/complaints/${c.id}/category`, { body: { category: e.target.value } }); reload(); } };
  $("#assign")?.addEventListener("click", () => assignModal(c, reload));
  $("#resolve").onclick = async () => {
    const p = $("#aphoto").files[0], v = $("#avideo").files[0];
    if (!p || !v || !$("#act").value.trim()) { $("#aerr").innerHTML = `<div class="banner b-danger">Live photo, live video and the action taken are all required.</div>`; return; }
    const send = async (pos) => {
      const fd = new FormData(); fd.append("photo", p); fd.append("video", v); fd.append("action_taken", $("#act").value);
      fd.append("captured_at", Math.min(p.lastModified, v.lastModified) + (S.offset || 0));
      if (pos) { fd.append("lat", pos.coords.latitude); fd.append("lng", pos.coords.longitude); }
      $("#resolve").disabled = true; $("#resolve").textContent = "Uploading…";
      try { await api(`/api/complaints/${c.id}/resolve`, { body: fd }); toast("Resolved – citizen asked to verify"); reload(); }
      catch (err) { $("#aerr").innerHTML = `<div class="banner b-danger">⚠️ ${esc(err.message)}</div>`; $("#resolve").disabled = false; $("#resolve").textContent = "Upload proof & mark resolved"; }
    };
    navigator.geolocation ? navigator.geolocation.getCurrentPosition(send, () => send(null), { enableHighAccuracy: true, timeout: 10000 }) : send(null);
  };
}

async function assignModal(c, done) {
  const staff = await api(`/api/staff?department=${c.departmentId}`);
  const due = new Date(c.dueAt - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  openModal(`<h3>Assign ${esc(c.id)}</h3><div class="stack">
    <label class="field">Officer<select id="off">${staff.map((s) => `<option value="${s.id}" ${c.officer?.id === s.id ? "selected" : ""}>${esc(s.name)} – ${esc(s.designation)} (${s.open} open)</option>`).join("")}</select></label>
    <label class="field">Deadline<input id="due" type="datetime-local" value="${due}"></label>
    <label class="field">Field team (optional)<input id="mteam" value="${esc(c.assignedTeam || "")}"></label>
    <label class="field">Note to officer<input id="note"></label><div id="merr"></div>
    <div class="row"><button class="btn" id="save">Assign</button><button class="btn secondary" id="cancel">Cancel</button></div></div>`);
  $("#cancel").onclick = closeModal;
  $("#save").onclick = async () => {
    try {
      await api(`/api/complaints/${c.id}/assign`, { body: { officer_id: +$("#off").value, due_at: new Date($("#due").value).getTime() + (S.offset || 0), team: $("#mteam").value || null, note: $("#note").value } });
      closeModal(); toast("Assigned"); done();
    } catch (err) { $("#merr").innerHTML = `<div class="banner b-danger">${esc(err.message)}</div>`; }
  };
}
function openModal(html) { $("#modal-card").innerHTML = html; $("#modal").classList.remove("hidden"); }
function closeModal() { $("#modal").classList.add("hidden"); }
$("#modal").addEventListener("click", (e) => { if (e.target.id === "modal") closeModal(); });

/* ---------- dashboards ---------- */
async function dashboard() {
  if (!S.token) { location.hash = "#/login"; return; }
  await pollMe();
  const r = S.user.role;
  if (r === "citizen") return citizenDash();
  if (r === "officer") return staffDash("assigned", "My tasks");
  if (r === "supervisor") return staffDash("team", "Team tasks");
  return adminDash();
}

async function citizenDash() {
  const [mine, community] = await Promise.all([api("/api/complaints?scope=mine"), api("/api/complaints?scope=community&limit=50")]);
  const verify = mine.filter((c) => c.status === "RESOLVED" && c.isMine);
  view().innerHTML = `<div class="row"><h2 style="margin:0">Hi, ${esc(S.user.name)} 👋</h2><span class="spacer"></span><a class="btn" href="#/report">📸 Report a problem</a></div>
    ${verify.length ? `<div class="banner b-warn" style="margin-top:12px">🔔 ${verify.length} complaint(s) marked resolved – open them and confirm whether the problem is actually fixed.</div>` : ""}
    <div class="grid g2" style="margin-top:14px"><div class="card"><h3>My complaints (${mine.length})</h3><div class="clist">${mine.map(citem).join("") || `<p class="muted">Nothing yet.</p>`}</div></div>
    <div class="card"><h3>Recent in the city</h3><div class="clist">${community.slice(0, 12).map(citem).join("")}</div></div></div>`;
}

async function staffDash(scope, title) {
  const isSup = S.user.role === "supervisor";
  const [list, stats] = await Promise.all([api(`/api/complaints?scope=${scope}`), api("/api/stats")]);
  const open = list.filter((c) => !["RESOLVED", "CLOSED"].includes(c.status));
  const mineEsc = list.filter((c) => c.escalatedTo?.id === S.user.id && !["RESOLVED", "CLOSED"].includes(c.status));
  let tab = "open";
  view().innerHTML = `<div class="row"><div><h2 style="margin:0">${esc(title)}</h2><div class="muted small">${esc(S.user.designation || "")} · ${esc(S.meta.workflows[S.user.departmentId]?.agency || "")}</div></div>
    <span class="spacer"></span>${isSup ? `<div class="row small"><span class="muted">Demo clock +${stats.timeOffsetHours} h</span><button class="btn sm secondary" data-h="24">+1 day</button><button class="btn sm secondary" data-h="72">+3 days</button><button class="btn sm secondary" data-reset="1">reset</button></div>` : ""}</div>
    <div class="grid g4" style="margin:14px 0">
      ${[["Open", open.length, "var(--info)"], ["Overdue", open.filter((c) => c.overdue).length, "var(--danger)"], ["Due in 24 h", open.filter((c) => !c.overdue && c.dueAt - Date.now() - (S.offset || 0) < 864e5).length, "var(--warn)"],
        ["Escalated to me", mineEsc.length, "var(--danger)"], ["Resolved", list.filter((c) => ["RESOLVED", "CLOSED"].includes(c.status)).length, "var(--ok)"]]
        .map(([l, v, col]) => `<div class="card stat"><div class="v" style="color:${col}">${v}</div><div class="l">${l}</div></div>`).join("")}</div>
    ${mineEsc.length ? `<div class="banner b-danger">🔺 ${mineEsc.length} complaint(s) were escalated to you because the deadline was missed by 2× the allowed time.</div>` : ""}
    <div class="card" style="margin-top:14px"><div class="tabs" id="tabs">${[["open", "Open"], ["overdue", "Overdue"], ["escalated", "Escalated"], ["done", "Resolved"], ["all", "All"]].map(([k, l]) => `<button data-t="${k}">${l}</button>`).join("")}</div>
    <div class="tbl-wrap"><table class="tbl"><thead><tr><th>Complaint</th><th>Location</th><th>Officer</th><th>Deadline</th><th>Status</th>${isSup ? "<th></th>" : ""}</tr></thead><tbody id="rows"></tbody></table></div></div>`;
  const draw = () => {
    document.querySelectorAll("#tabs button").forEach((b) => b.classList.toggle("on", b.dataset.t === tab));
    const rows = list.filter((c) => tab === "all" ? true : tab === "open" ? !["RESOLVED", "CLOSED"].includes(c.status) : tab === "overdue" ? c.overdue
      : tab === "escalated" ? c.escalationLevel >= 1 && !["RESOLVED", "CLOSED"].includes(c.status) : ["RESOLVED", "CLOSED"].includes(c.status))
      .sort((a, b) => a.dueAt - b.dueAt);
    $("#rows").innerHTML = rows.map((c) => `<tr><td><a href="#/c/${esc(c.id)}"><b>${cat(c.category).emoji} ${esc(cat(c.category).label)}</b></a><div class="small muted">${esc(c.id)} · ${esc(c.severity)}${c.supportCount ? ` · 👥 +${c.supportCount}` : ""}</div></td>
      <td class="small">${esc(c.address || c.locationLabel)}</td><td class="small">${esc(c.officer?.name || "–")}${c.escalatedTo ? `<div class="muted">↑ ${esc(c.escalatedTo.name)}</div>` : ""}</td>
      <td class="small">${fmt(c.dueAt)}<div>${countdown(c.dueAt, c.status)}</div></td><td>${statusPill(c)}</td>
      ${isSup ? `<td><button class="btn sm secondary" data-a="${esc(c.id)}">Assign</button></td>` : ""}</tr>`).join("") || `<tr><td colspan="6" class="muted">Nothing here 🎉</td></tr>`;
    document.querySelectorAll("[data-a]").forEach((b) => b.onclick = () => assignModal(list.find((c) => c.id === b.dataset.a), () => staffDash(scope, title)));
  };
  document.querySelectorAll("#tabs button").forEach((b) => b.onclick = () => { tab = b.dataset.t; draw(); });
  document.querySelectorAll("[data-h],[data-reset]").forEach((b) => b.onclick = async () => {
    const r = await api("/api/admin/time", { body: b.dataset.reset ? { reset: true } : { add_hours: +b.dataset.h } });
    toast(`Clock +${r.offsetHours} h · ${r.escalated.length} escalation step(s)`); staffDash(scope, title);
  });
  draw();
  S.timers.push(setInterval(draw, 60000));
}

async function adminDash() {
  let tab = sessionStorage.getItem("cf_admin_tab") || "complaints";
  view().innerHTML = `<div class="row"><h2 style="margin:0">Administration</h2><span class="spacer"></span><span class="pill p-danger">Full access · every change is logged</span></div>
    <div class="tabs" style="margin-top:12px" id="atabs">${[["complaints", "All complaints"], ["users", "Officers & users"], ["audit", "Audit log"], ["stats", "Analytics"]].map(([k, l]) => `<button data-t="${k}">${l}</button>`).join("")}</div><div id="apane"></div>`;
  const show = async () => {
    sessionStorage.setItem("cf_admin_tab", tab);
    document.querySelectorAll("#atabs button").forEach((b) => b.classList.toggle("on", b.dataset.t === tab));
    const pane = $("#apane"); pane.innerHTML = `<div class="loading">Loading…</div>`;
    if (tab === "complaints") {
      const list = await api("/api/complaints?scope=all&limit=1000");
      pane.innerHTML = `<div class="card"><input id="flt" placeholder="Filter by id, citizen, officer, address, status…" style="margin:0 0 10px"><div class="tbl-wrap"><table class="tbl">
        <thead><tr><th>Complaint</th><th>Citizen</th><th>Officer → Supervisor → Escalated</th><th>Deadline</th><th>Status</th><th></th></tr></thead><tbody id="arows"></tbody></table></div></div>`;
      const draw = () => {
        const q = $("#flt").value.toLowerCase();
        $("#arows").innerHTML = list.filter((c) => !q || JSON.stringify([c.id, c.citizenName, c.officer?.name, c.supervisor?.name, c.escalatedTo?.name, c.address, c.status, c.categoryLabel]).toLowerCase().includes(q))
          .map((c) => `<tr><td><a href="#/c/${esc(c.id)}"><b>${esc(c.id)}</b></a><div class="small">${cat(c.category).emoji} ${esc(c.categoryLabel)} · ${esc(c.severity)}</div><div class="small muted">${esc(c.address || c.locationLabel)}</div></td>
            <td class="small">${esc(c.citizenName)}${c.citizenPhone ? `<div>${esc(c.citizenPhone)}</div>` : ""}</td>
            <td class="small">${esc(c.officer?.name || "–")} → ${esc(c.supervisor?.name || "–")}${c.escalatedTo ? ` → <b>${esc(c.escalatedTo.name)}</b>` : ""}<div class="muted">${esc(c.agency)}</div></td>
            <td class="small">${fmt(c.dueAt)}<div>${countdown(c.dueAt, c.status)}</div></td><td>${statusPill(c)}</td>
            <td><div class="row"><button class="btn sm secondary" data-e="${esc(c.id)}">Edit</button><button class="btn sm danger" data-d="${esc(c.id)}">Delete</button></div></td></tr>`).join("");
        document.querySelectorAll("[data-e]").forEach((b) => b.onclick = () => editModal(list.find((c) => c.id === b.dataset.e), show));
        document.querySelectorAll("[data-d]").forEach((b) => b.onclick = async () => {
          if (!confirm(`Delete ${b.dataset.d} permanently (including photos and video)?`)) return;
          await api(`/api/admin/complaints/${b.dataset.d}`, { method: "DELETE" }); toast("Deleted"); show();
        });
      };
      $("#flt").oninput = draw; draw();
    } else if (tab === "users") {
      const users = await api("/api/admin/users");
      pane.innerHTML = `<div class="grid g2"><div class="card"><h3>Users (${users.length})</h3><div class="tbl-wrap"><table class="tbl"><thead><tr><th>Name</th><th>Role</th><th>Department / level</th><th></th></tr></thead><tbody>
        ${users.map((u) => `<tr><td><b>${esc(u.name)}</b><div class="small muted">${esc(u.phone || "")}</div></td><td>${esc(u.role)}${u.active ? "" : ` <span class="pill p-danger">disabled</span>`}</td>
          <td class="small">${esc(S.meta.workflows[u.departmentId]?.agency || "")}<div class="muted">${esc(u.designation || "")}</div></td>
          <td>${u.role !== "admin" ? `<button class="btn sm secondary" data-u="${u.id}" data-act="${u.active ? 0 : 1}">${u.active ? "Disable" : "Enable"}</button>` : ""}</td></tr>`).join("")}</tbody></table></div></div>
        <div class="card stack"><h3>Create officer / supervisor</h3><form id="nu" class="stack">
          <label class="field">Name<input name="name" required minlength="3"></label><label class="field">Phone<input name="phone"></label>
          <label class="field">Password<input name="password" type="password" minlength="6" required></label>
          <label class="field">Role<select name="role"><option value="officer">Field officer (level 0)</option><option value="supervisor">Supervisor</option><option value="admin">Admin</option></select></label>
          <label class="field">Department<select name="department_id">${Object.entries(S.meta.workflows).map(([k, w]) => `<option value="${k}">${esc(w.agency)}</option>`).join("")}</select></label>
          <label class="field">Supervisor level<select name="level"><option value="1">1 – supervisor (AE / Sanitary Superintendent)</option><option value="2">2 – higher supervisor (EE / Deputy Commissioner)</option><option value="3">3 – head (SE / Director)</option></select></label>
          <div id="uerr"></div><button class="btn">Create account</button></form></div></div>`;
      document.querySelectorAll("[data-u]").forEach((b) => b.onclick = async () => { await api(`/api/admin/users/${b.dataset.u}`, { method: "PATCH", body: { active: b.dataset.act === "1" } }); show(); });
      $("#nu").onsubmit = async (e) => {
        e.preventDefault(); const f = Object.fromEntries(new FormData(e.target)); f.level = +f.level;
        try { await api("/api/admin/users", { body: f }); toast("Account created"); show(); } catch (err) { $("#uerr").innerHTML = `<div class="banner b-danger">${esc(err.message)}</div>`; }
      };
    } else if (tab === "audit") {
      const log = await api("/api/admin/audit");
      pane.innerHTML = `<div class="card"><div class="tbl-wrap"><table class="tbl"><thead><tr><th>Time</th><th>By</th><th>Action</th><th>Complaint</th><th>Details</th></tr></thead><tbody>
        ${log.map((a) => `<tr><td class="small">${fmt(a.time)}</td><td>${esc(a.user)}</td><td><b>${esc(a.action)}</b></td><td>${esc(a.complaintId || "")}</td><td class="small"><code>${esc(JSON.stringify(a.details))}</code></td></tr>`).join("") || `<tr><td colspan="5" class="muted">No admin actions yet.</td></tr>`}</tbody></table></div></div>`;
    } else {
      const s = await api("/api/stats");
      const max = Math.max(1, ...Object.values(s.byCategory).map((x) => x.count));
      pane.innerHTML = `<div class="grid g4">${[["Total", s.total], ["Open", s.open], ["Overdue", s.overdue], ["Escalated", s.escalated], ["On-time %", s.onTimePct ?? "–"], ["Reopened", s.reopened]]
          .map(([l, v]) => `<div class="card stat"><div class="v">${v}</div><div class="l">${l}</div></div>`).join("")}</div>
        <div class="grid g2" style="margin-top:14px"><div class="card stack"><h3>By category</h3>${Object.values(s.byCategory).map((x) => `<div><div class="row small"><span>${esc(x.label)}</span><span class="spacer"></span><b>${x.count}</b></div><div class="bar"><i style="width:${100 * x.count / max}%"></i></div></div>`).join("")}</div>
        <div class="card"><h3>By department</h3><table class="tbl"><tr><th>Agency</th><th>Open</th><th>Overdue</th><th>Escalated</th><th>Avg h</th><th>On time</th></tr>
          ${Object.values(s.byDepartment).map((d) => `<tr><td class="small">${esc(d.agency)}</td><td>${d.open}</td><td>${d.overdue}</td><td>${d.escalated}</td><td>${d.avgResolutionHours ?? "–"}</td><td>${d.onTimePct != null ? d.onTimePct + "%" : "–"}</td></tr>`).join("")}</table></div></div>`;
    }
  };
  document.querySelectorAll("#atabs button").forEach((b) => b.onclick = () => { tab = b.dataset.t; show(); });
  show();
}

async function editModal(c, done) {
  const staff = await api(`/api/staff?department=${c.departmentId}`);
  const opt = (sel) => `<option value="">–</option>` + staff.map((s) => `<option value="${s.id}" ${sel === s.id ? "selected" : ""}>${esc(s.name)} – ${esc(s.designation)}</option>`).join("");
  const due = new Date(c.dueAt - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  openModal(`<h3>Edit ${esc(c.id)}</h3><div class="stack">
    <label class="field">Status<select id="es">${["NEW", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED", "REOPENED"].map((s) => `<option ${s === c.status ? "selected" : ""}>${s}</option>`).join("")}</select></label>
    <label class="field">Category<select id="ec">${Object.entries(S.meta.categories).map(([k, x]) => `<option value="${k}" ${k === c.category ? "selected" : ""}>${esc(x.label)}</option>`).join("")}</select></label>
    <label class="field">Severity<select id="ev">${["low", "medium", "high"].map((s) => `<option ${s === c.severity ? "selected" : ""}>${s}</option>`).join("")}</select></label>
    <label class="field">Officer<select id="eo">${opt(c.officer?.id)}</select></label>
    <label class="field">Supervisor<select id="esu">${opt(c.supervisor?.id)}</select></label>
    <label class="field">Escalated to<select id="eesc">${opt(c.escalatedTo?.id)}</select></label>
    <label class="field">Escalation level<select id="el">${[0, 1, 2, 3].map((n) => `<option ${n === c.escalationLevel ? "selected" : ""}>${n}</option>`).join("")}</select></label>
    <label class="field">Deadline<input id="ed" type="datetime-local" value="${due}"></label>
    <label class="field">Description<textarea id="edesc" rows="3">${esc(c.description)}</textarea></label>
    <div id="eerr"></div><div class="row"><button class="btn" id="esave">Save (logged)</button><button class="btn secondary" id="ecancel">Cancel</button></div></div>`);
  $("#ecancel").onclick = closeModal;
  $("#esave").onclick = async () => {
    const num = (v) => v ? +v : null;
    const body = { status: $("#es").value, category: $("#ec").value, severity: $("#ev").value, officer_id: num($("#eo").value), supervisor_id: num($("#esu").value),
      escalated_to_id: num($("#eesc").value), escalation_level: +$("#el").value, due_at: new Date($("#ed").value).getTime() + (S.offset || 0), description: $("#edesc").value };
    try { await api(`/api/admin/complaints/${c.id}`, { method: "PATCH", body }); closeModal(); toast("Saved and logged"); done(); }
    catch (err) { $("#eerr").innerHTML = `<div class="banner b-danger">${esc(err.message)}</div>`; }
  };
}

async function notificationsPage() {
  if (!S.token) { location.hash = "#/login"; return; }
  const list = await api("/api/notifications");
  const icon = { warning: "⚠️", escalation: "🔺", resolved: "✅", info: "🔔" };
  view().innerHTML = `<div class="card stack"><div class="row"><h2 style="margin:0">Notifications</h2><span class="spacer"></span><button class="btn sm secondary" id="allread">Mark all read</button></div>
    ${list.map((n) => `<a class="notif ${n.read ? "" : "unread"}" style="display:block;text-decoration:none;color:inherit" href="${n.complaintId ? `#/c/${esc(n.complaintId)}` : "#/notifications"}">
      <b>${icon[n.kind] || "🔔"} ${esc(n.title)}</b><div class="small">${esc(n.body)}</div><div class="small muted">${fmt(n.createdAt)}</div></a>`).join("") || `<p class="muted">No notifications.</p>`}</div>`;
  $("#allread").onclick = async () => { await api("/api/notifications/read", { body: [] }); S.unread = 0; notificationsPage(); renderNav(); };
}

/* ---------- start ---------- */
(async () => {
  S.meta = await api("/api/meta");
  await pollMe();
  setInterval(pollMe, 30000);
  window.addEventListener("hashchange", router);
  router();
})();
