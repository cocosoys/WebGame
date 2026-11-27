
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
        if (pending && urlUser && cleanName(pending) !== urlUser) {
            localStorage.removeItem(PENDING_KEY);
            pending = null;
        }
        if (!pending) {
            if (urlUser && urlUser.length >= 3) {
                applyProfile(urlUser).then(function () { window.location.reload(); });
                return;
            }
            // 客户端内不再自己弹用户名框：没有名字就去统一名称页，填完再带 ?user= 回来。
            var back = window.location.pathname || "";
            window.location.replace("/web/plugins/WebGame/play/name/?kind=eagler&label="
                + encodeURIComponent(document.title || "Eagler 网页版")
                + "&back=" + encodeURIComponent(back));
            return;
        }
        var username = pending;
        (function () {
            function buildOverlay2() {
                if (window.__wgOverlayHidden) return;
                if (document.getElementById("wg_overlay")) return;
                var ov = document.createElement("div");
                ov.id = "wg_overlay";
                ov.style.cssText = "position:fixed;top:0;left:0;width:100%;height:100%;z-index:99999;background:#070b16;display:flex;align-items:center;justify-content:center;font-family:'Microsoft YaHei','PingFang SC',Arial,sans-serif;";
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
            // 兜底：极端情况下 WebSocket 根本没建起来，也不能让玩家永远停在遮罩后。
            setTimeout(function () {
                if (window.__wgHideOverlay) window.__wgHideOverlay();
            }, 25000);
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
        // 只做两次安全按键：第一次通过“按任意键开启声音”，第二次在服务器列表
        // 出现后确认进入默认服务器。绝不再按 ArrowDown —— 服务器列表为空时那会越界崩溃。
        (function () {
            function getCanvas() {
                var f = document.getElementById("game_frame");
                return f ? f.querySelector("canvas") : null;
            }
            function tapEnter() {
                var c = getCanvas();
                if (!c) return;
                ["keydown", "keyup"].forEach(function (type) {
                    c.dispatchEvent(new KeyboardEvent(type, {
                        bubbles: true, cancelable: true, code: "Enter", key: "Enter",
                        keyCode: 13, which: 13
                    }));
                });
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
                    var sum = 0;
                    for (var i = 0; i < d.length; i += 4) sum += (d[i] + d[i + 1] + d[i + 2]) / 3;
                    return sum / (d.length / 4);
                } catch (e) { return null; }
            }
            var canvasAt = 0, soundAt = 0, entryAt = 0, brightHits = 0;
            var timer = setInterval(function () {
                if (entryAt) { clearInterval(timer); return; }
                if (!getCanvas()) return;
                if (!canvasAt) canvasAt = Date.now();
                if (!soundAt) {
                    if (Date.now() - canvasAt >= 6000) {
                        tapEnter();
                        soundAt = Date.now();
                    }
                    return;
                }
                if (Date.now() - soundAt < 3500) return;
                var b = brightness();
                if (b !== null && b > 25) brightHits++; else brightHits = 0;
                if (brightHits >= 3) {
                    tapEnter();
                    entryAt = Date.now();
                    clearInterval(timer);
                } else if (Date.now() - soundAt > 15000) {
                    clearInterval(timer);
                }
            }, 500);
        })();
        })();
    })();
    