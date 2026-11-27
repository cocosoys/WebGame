
    // ===== WebGame auto-join bootstrap v4 (standalone .js, defer-loaded) =====
    (function () {
        "use strict";
        var NS = "_eaglercraft_1.12";
        var PENDING_KEY = "wg_auto_pending";
        var LAST_KEY = "wg_auto_last";
        (function () {
            var OrigWS = window.WebSocket;
            if (!OrigWS) return;
            function Wrapper(url, protocols) {
                var ws = (protocols === undefined) ? new OrigWS(url) : new OrigWS(url, protocols);
                try {
                    if (typeof url === "string" && url.indexOf("/eagler") >= 0) {
                        setTimeout(function () { if (window.__wgHideOverlay) window.__wgHideOverlay(); }, 0);
                    }
                } catch (e) {}
                return ws;
            }
            Wrapper.prototype = OrigWS.prototype;
            Wrapper.CONNECTING = OrigWS.CONNECTING;
            Wrapper.OPEN = OrigWS.OPEN;
            Wrapper.CLOSING = OrigWS.CLOSING;
            Wrapper.CLOSED = OrigWS.CLOSED;
            try { window.WebSocket = Wrapper; } catch (e) {}
        })();
        function nbtString(s) {
            var bytes = new TextEncoder().encode(s);
            var out = new Uint8Array(bytes.length + 2);
            new DataView(out.buffer).setUint16(0, s.length);
            out.set(bytes, 2);
            return out;
        }
        function intBE(v) { var b = new ArrayBuffer(4); new DataView(b).setInt32(0, v); return new Uint8Array(b); }
        function nbtName(tag, name, payload) {
            var nb = new TextEncoder().encode(name);
            var out = new Uint8Array(1 + nb.length + 2 + payload.length);
            out[0] = tag;
            new DataView(out.buffer).setUint16(1, name.length);
            out.set(nb, 3);
            out.set(payload, 3 + nb.length);
            return out;
        }
        function concatBytes(a, b) {
            var out = new Uint8Array(a.length + b.length);
            out.set(a, 0); out.set(b, a.length);
            return out;
        }
        function buildProfile(u) {
            var parts = [
                new Uint8Array([10, 0, 0]),
                nbtName(9, "capes", concatBytes(new Uint8Array([0]), intBE(0))),
                nbtName(3, "presetCape", intBE(0)),
                nbtName(3, "customCape", intBE(-1)),
                nbtName(9, "skins", concatBytes(new Uint8Array([0]), intBE(0))),
                nbtName(8, "username", nbtString(u)),
                nbtName(3, "presetSkin", intBE(0)),
                nbtName(3, "customSkin", intBE(-1)),
                new Uint8Array([0])
            ];
            var len = 0, i;
            for (i = 0; i < parts.length; i++) len += parts[i].length;
            var out = new Uint8Array(len), o = 0;
            for (i = 0; i < parts.length; i++) { out.set(parts[i], o); o += parts[i].length; }
            return out;
        }
        function toB64(bytes) {
            var bin = "";
            for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
            return btoa(bin);
        }
        function cleanName(s) {
            return (s || "").replace(/[^A-Za-z0-9_\u4e00-\u9fa5]/g, "").substring(0, 16);
        }
        function applyProfile(u) {
            return (async function () {
                var raw = buildProfile(u);
                var cs = new CompressionStream("gzip");
                var buf = await new Response(new Blob([raw]).stream().pipeThrough(cs)).arrayBuffer();
                localStorage.setItem(NS + ".p", toB64(new Uint8Array(buf)));
                localStorage.setItem(LAST_KEY, u);
                localStorage.setItem(PENDING_KEY, u);
            })();
        }
        var pending = localStorage.getItem(PENDING_KEY);
        var params = new URLSearchParams(window.location.search);
        var urlUser = cleanName(params.get("user") || "");
        // 服务端口径（auto-join.enabled / default-username）：由 WebGame 的匿名端点下发，
        // 每次会话只问一次（sessionStorage 标记），避免刷新页面时反复请求。
        var CFG_URL = "/api/plugins/WebGame/cloud/page-config";
        var CFG_FLAG = "wg_auto_cfg";
        var cfg = { ready: false, enabled: false, name: "", note: "" };
        function loadCfg(cb) {
            var settled = false;
            function fin(v) { if (settled) return; settled = true; cb(v); }
            var timer = setTimeout(function () { fin(null); }, 2000);
            try {
                fetch(CFG_URL, { credentials: "same-origin", cache: "no-store" })
                    .then(function (r) { return r.json(); })
                    .then(function (j) {
                        clearTimeout(timer);
                        fin((j && j.data && j.data.autoJoin) || null);
                    })
                    .catch(function () { clearTimeout(timer); fin(null); });
            } catch (e) { clearTimeout(timer); fin(null); }
        }
        if (!pending) {
            if (urlUser && urlUser.length >= 3) {
                applyProfile(urlUser).then(function () { window.location.reload(); });
                return;
            }
            function buildOverlay() {
                if (document.getElementById("wg_overlay")) return;
                var ov = document.createElement("div");
                ov.id = "wg_overlay";
                ov.style.cssText = "position:fixed;top:0;left:0;width:100%;height:100%;z-index:99999;background:rgba(0,0,0,0.88);display:flex;align-items:center;justify-content:center;font-family:'Microsoft YaHei','PingFang SC',Arial,sans-serif;";
                ov.innerHTML = ""
                    + "<div style='background:#161616;border:2px solid #4a4a4a;border-radius:10px;padding:30px 42px;text-align:center;box-shadow:0 0 60px rgba(0,0,0,.7);'>"
                    + "<div style='color:#fff;font-size:24px;font-weight:bold;margin-bottom:4px;'>WebGame 服务器</div>"
                    + "<div style='color:#9a9a9a;font-size:13px;margin-bottom:22px;'>输入用户名后自动进入服务器</div>"
                    + "<input id='wg_name' type='text' maxlength='16' autocomplete='off' spellcheck='false' style='width:260px;padding:10px 14px;font-size:16px;border:2px solid #666;border-radius:4px;background:#101010;color:#fff;outline:none;text-align:center;' placeholder='用户名（3-16 字符）'/>"
                    + "<div style='margin-top:18px;'>"
                    + "<button id='wg_btn' style='padding:10px 44px;font-size:16px;font-weight:bold;background:#3c8527;color:#fff;border:2px solid #2a5f1c;border-radius:4px;cursor:pointer;'>进入服务器</button>"
                    + "</div>"
                    + "<div id='wg_err' style='color:#e55;font-size:13px;margin-top:12px;min-height:16px;'></div>"
                    + "</div>";
                document.body.appendChild(ov);
                var inp = document.getElementById("wg_name");
                var last = cleanName(localStorage.getItem(LAST_KEY) || "");
                if (last) inp.value = last;
                else if (cfg.name) inp.value = cfg.name;
                var btn = document.getElementById("wg_btn");
                var err = document.getElementById("wg_err");
                if (!cfg.ready) {
                    // 配置还没回来：先锁住按钮，别让人填完名进到一个"开关已关"的页面
                    btn.disabled = true;
                    err.textContent = "正在读取服务器配置…";
                }
                inp.focus();
                function submit() {
                    var u = cleanName(inp.value);
                    if (u.length < 3) {
                        document.getElementById("wg_err").textContent = "用户名至少 3 个字符";
                        inp.focus();
                        return;
                    }
                    document.getElementById("wg_btn").disabled = true;
                    document.getElementById("wg_err").textContent = "正在准备...";
                    applyProfile(u).then(function () {
                        window.location.reload();
                    }).catch(function () {
                        document.getElementById("wg_err").textContent = "准备失败，请重试";
                        document.getElementById("wg_btn").disabled = false;
                    });
                }
                document.getElementById("wg_btn").addEventListener("click", submit);
                inp.addEventListener("keydown", function (e) { if (e.key === "Enter") submit(); });
            }
            function guardOverlay() {
                if (document.body) { buildOverlay(); }
                else { var wt = setInterval(function () { if (document.body) { clearInterval(wt); buildOverlay(); } }, 50); }
                try {
                    if (window.MutationObserver) {
                        var mo = new MutationObserver(function () { buildOverlay(); });
                        mo.observe(document.documentElement, { childList: true, subtree: true });
                    }
                } catch (e) {}
                setInterval(buildOverlay, 1000);
            }
            guardOverlay();
            loadCfg(function (aj) {
                cfg.ready = true;
                cfg.enabled = !!(aj && aj.enabled);
                cfg.name = cleanName((aj && aj.username) || "");
                cfg.note = (aj && aj.note) || "";
                var remembered = cleanName(localStorage.getItem(LAST_KEY) || "");
                // 自动进服的两层含义（服务端口径）：
                //   ①enabled=true 且本机记住过名字 → 直接用记住的名字进，不再弹框；
                //   ②否则弹框，输入框预填 记住的名字 → default-username。
                // 不把 default-username 当"所有人的名字"直接登录：同名玩家在离线模式下会互踢。
                if (cfg.enabled && remembered.length >= 3 && !sessionStorage.getItem(CFG_FLAG)) {
                    sessionStorage.setItem(CFG_FLAG, "1");
                    applyProfile(remembered).then(function () { window.location.reload(); });
                    return;
                }
                var inp = document.getElementById("wg_name");
                var btn = document.getElementById("wg_btn");
                var err = document.getElementById("wg_err");
                if (inp && !inp.value) inp.value = remembered || cfg.name || "";
                if (btn) btn.disabled = false;
                if (err) err.textContent = cfg.note || "";
                if (inp) inp.focus();
                sessionStorage.setItem(CFG_FLAG, "1");
            });
            return;
        }
        var username = pending;
        (function () {
            function buildOverlay2() {
                if (window.__wgOverlayHidden) return;
                if (document.getElementById("wg_overlay")) return;
                var ov = document.createElement("div");
                ov.id = "wg_overlay";
                ov.style.cssText = "position:fixed;top:0;left:0;width:100%;height:100%;z-index:99999;background:rgba(0,0,0,0.9);display:flex;align-items:center;justify-content:center;font-family:'Microsoft YaHei','PingFang SC',Arial,sans-serif;";
                ov.innerHTML = ""
                    + "<div style='color:#fff;font-size:20px;'>正在进入服务器<span id='wg_dots'></span></div>"
                    + "<div style='color:#8a8a8a;font-size:13px;margin-top:10px;'>用户名：" + username + "</div>";
                document.body.appendChild(ov);
            }
            function guardOverlay2() {
                if (document.body) { buildOverlay2(); }
                else { var wt = setInterval(function () { if (document.body) { clearInterval(wt); buildOverlay2(); } }, 50); }
                try {
                    if (window.MutationObserver) {
                        var mo = new MutationObserver(function () { buildOverlay2(); });
                        mo.observe(document.documentElement, { childList: true, subtree: true });
                    }
                } catch (e) {}
                setInterval(buildOverlay2, 1000);
            }
            guardOverlay2();
            var n = 0;
            setInterval(function () {
                n = (n % 3) + 1;
                var d = document.getElementById("wg_dots");
                if (d) d.textContent = new Array(n + 1).join(".");
            }, 400);
            window.__wgHideOverlay = function () {
                window.__wgOverlayHidden = true;
                var o = document.getElementById("wg_overlay");
                if (o) { o.style.display = "none"; if (o.parentNode) o.parentNode.removeChild(o); }
            };
        })();
        (function () {
            function getCanvas() {
                var f = document.getElementById("game_frame");
                return f ? f.querySelector("canvas") : null;
            }
            function key(type, code, keyCode, key) {
                var c = getCanvas();
                if (!c) return;
                var ev = new KeyboardEvent(type, { bubbles: true, cancelable: true, code: code, key: key, keyCode: keyCode, which: keyCode });
                c.dispatchEvent(ev);
            }
            function tap(code, keyCode, k) {
                key("keydown", code, keyCode, k);
                key("keyup", code, keyCode, k);
            }
            function brightness() {
                var c = getCanvas();
                if (!c) return null;
                try {
                    var tmp = document.createElement("canvas");
                    tmp.width = 16; tmp.height = 16;
                    var t = tmp.getContext("2d");
                    t.drawImage(c, 0, 0, 16, 16);
                    var d = t.getImageData(0, 0, 16, 16).data;
                    var s = 0, i;
                    for (i = 0; i < d.length; i += 4) s += (d[i] + d[i + 1] + d[i + 2]) / 3;
                    return s / (d.length / 4);
                } catch (e) { return null; }
            }
            var stage = "boot", soundGoneAt = 0, menuKeyedAt = 0, done = false;
            setInterval(function () {
                if (done) return;
                var b = brightness();
                if (b === null) return;
                if (stage === "boot") { if (b > 30) stage = "waitSound"; return; }
                if (stage === "waitSound") {
                    if (b > 150) { tap("Enter", 13, "Enter"); soundGoneAt = Date.now(); stage = "postSound"; }
                    else if (b <= 80) { soundGoneAt = Date.now(); stage = "postSound"; }
                    return;
                }
                if (stage === "postSound") {
                    if ((Date.now() - soundGoneAt > 10000 && b < 90) || Date.now() - soundGoneAt > 25000) {
                        tap("ArrowDown", 40, "ArrowDown");
                        setTimeout(function () { tap("Enter", 13, "Enter"); }, 350);
                        menuKeyedAt = Date.now();
                        stage = "postMenu";
                    }
                    return;
                }
                if (stage === "postMenu") {
                    if (Date.now() - menuKeyedAt > 4500) {
                        tap("ArrowDown", 40, "ArrowDown");
                        setTimeout(function () { tap("Enter", 13, "Enter"); }, 350);
                        done = true;
                        setTimeout(function () { localStorage.removeItem(PENDING_KEY); }, 6000);
                        setTimeout(function () { if (window.__wgHideOverlay) window.__wgHideOverlay(); }, 15000);
                    }
                    return;
                }
            }, 400);
        })();
    })();
    