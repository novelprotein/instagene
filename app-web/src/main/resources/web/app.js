"use strict";

const state = { seq: null };

const $ = (id) => document.getElementById(id);

async function api(path, body) {
  const res = await fetch(path, {
    method: body === undefined ? "GET" : "POST",
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(text);
  return JSON.parse(text);
}

function setStatus(msg) {
  $("status").textContent = msg;
}

function escapeHtml(v) {
  return v
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function gcPercent(bases) {
  if (!bases) return "0.0";
  const gc = bases.toUpperCase().split("").filter((c) => c === "G" || c === "C" || c === "S").length;
  return (gc * 100 / bases.length).toFixed(1);
}

function render() {
  const s = state.seq;
  if (!s) {
    $("sequence").textContent = "No sequence loaded.";
    return;
  }
  $("meta").innerHTML =
    `<strong>${escapeHtml(s.name)}</strong> — ${s.length} bp, ${s.kind.toLowerCase()}, ${s.topology.toLowerCase()}` +
    (s.description ? ` <em>${escapeHtml(s.description)}</em>` : "") +
    ` &nbsp; GC ${gcPercent(s.bases)}%`;
  $("sequence").textContent = s.bases ? s.bases.match(/.{1,60}/g).join("\n") : "";
  $("features").textContent = "";
  for (const f of s.features || []) {
    const li = document.createElement("li");
    li.textContent = `${f.name}  ${f.type}  ${f.start + 1}..${f.end}  ${f.strand}`;
    $("features").appendChild(li);
  }
  setStatus(`Loaded ${s.name} (${s.length} bp)`);
}

function showOutput(text) {
  $("output").textContent = text ?? "";
  setStatus("Analysis complete.");
}

async function runOp(op, args = {}) {
  if (!state.seq) {
    setStatus("Load a sequence first.");
    return;
  }
  setStatus(`Running ${op}…`);
  const r = await api("/api/op", { op, seq: state.seq, args });
  if (r.error) {
    setStatus("Error: " + r.error);
    $("output").textContent = r.error;
    return;
  }
  if (r.seq) {
    state.seq = r.seq;
    render();
  }
  if (r.text !== null && r.text !== undefined) showOutput(r.text);
}

async function init() {
  const samples = await api("/api/samples");
  $("sample").innerHTML = samples.map((n) => `<option>${escapeHtml(n)}</option>`).join("");

  $("loadSample").onclick = async () => {
    state.seq = await api("/api/open", { sample: $("sample").value });
    render();
  };

  $("loadFile").onclick = () => $("file").click();
  $("file").onchange = async () => {
    const file = $("file").files[0];
    if (!file) return;
    const text = await file.text();
    state.seq = await api("/api/open", { text });
    render();
  };

  $("loadText").onclick = async () => {
    const text = $("paste").value.trim();
    if (!text) return;
    state.seq = await api("/api/open", { text });
    render();
  };

  for (const btn of document.querySelectorAll("[data-op]")) {
    btn.onclick = () => {
      const op = btn.dataset.op;
      switch (op) {
        case "digest": {
          const enzymes = prompt("Enzymes (comma-separated, empty = all):", "EcoRI,HinDIII");
          if (enzymes === null) return;
          runOp("digest", { enzymes });
          break;
        }
        case "find": {
          const pattern = prompt("Pattern (IUPAC-aware):", "GGATCC");
          if (!pattern) return;
          runOp("find", { pattern });
          break;
        }
        case "rotate": {
          const origin = prompt("New origin position (1-based):", "1");
          if (!origin) return;
          runOp("rotate", { origin });
          break;
        }
        default:
          runOp(op);
      }
    };
  }

  $("copy").onclick = async () => {
    const s = state.seq;
    if (!s) return;
    const fasta = ">" + s.name + "\n" + s.bases.match(/.{1,60}/g).join("\n") + "\n";
    await navigator.clipboard.writeText(fasta);
    setStatus("Copied FASTA to clipboard.");
  };

  state.seq = await api("/api/open", { sample: samples[0] });
  render();
}

init().catch((e) => {
  setStatus("Failed to start: " + e.message);
});
