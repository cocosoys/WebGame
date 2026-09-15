/**
 * SOYSHTTPOverMC 公共登录组件（全局挂载 SoysAuth）：
 *  - 动态创建登录弹窗 DOM（任意页面引用本文件即具备弹窗能力，无需复制 HTML/CSS）；
 *  - request()：统一请求，自动携带 Authorization: Bearer 会话令牌；
 *  - authFetch()：遇 401/403（HTTP 状态或 body.code）→ 自动弹登录窗 → 登录成功重试一次；
 *  - patchFetch()：全局包装 window.fetch，让页面上任何受保护请求都自动弹窗（调用一次全局生效，防循环重试）；
 *  - 令牌存储：localStorage(soys_token) + Cookie(soys_session)，可跨页面共享登录态。
 *
 * 页面集成：
 *   <script src="/soys-auth.js"></script>
 *   <script> SoysAuth.patchFetch(); </script>   // 可选：全局 401/403 自动弹窗
 * 登录成功后可通过 SoysAuth.onLoginSuccess = function(player, mode){} 刷新页面登录状态。
 */
(function (global) {
  'use strict';
  // API 前缀：SOYS 契约注入（api-prefix 可被服务器管理员配置，禁止写死 /api）
  var API = (global.SOYS_CONTEXT && global.SOYS_CONTEXT.apiPrefix) || '/api';

  var TOKEN_KEY = 'soys_token';
  var COOKIE_KEY = 'soys_session';
  var mask = null;           // 登录弹窗 DOM（懒创建）
  var pendingOpen = null;    // 弹窗打开的 Promise（已打开时返回同一实例，防并发弹窗/轮询误触）
  var patched = false;       // patchFetch 是否已生效
  var retryActive = false;   // 401/403 重试中（防循环弹窗）

  // ===== 令牌 =====
  function getToken() {
    try { return localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
  }
  function setToken(t) {
    try {
      if (t) {
        localStorage.setItem(TOKEN_KEY, t);
        document.cookie = COOKIE_KEY + '=' + t + '; path=/';
      } else {
        localStorage.removeItem(TOKEN_KEY);
        document.cookie = COOKIE_KEY + '=; path=/; max-age=0';
      }
    } catch (e) {}
  }

  // ===== 弹窗 DOM（懒创建）=====
  function ensureDom() {
    if (mask) return;
    var style = document.createElement('style');
    style.textContent =
      '#soysLoginMask{position:fixed;inset:0;background:rgba(0,0,0,.66);display:none;' +
      'align-items:center;justify-content:center;z-index:9999;backdrop-filter:blur(2px)}' +
      '#soysLoginMask.show{display:flex}' +
      '#soysLoginMask .soys-card{background:#12121f;border:1px solid #1f6feb66;border-radius:14px;' +
      'width:340px;padding:26px 28px;box-shadow:0 0 36px rgba(31,111,235,.35);color:#cfe;' +
      'font-family:system-ui,"Segoe UI",Arial,sans-serif}' +
      '#soysLoginMask .soys-card h2{margin:0 0 4px;font-size:18px;color:#5cf}' +
      '#soysLoginMask .soys-card p{margin:0 0 14px;font-size:12px;color:#789}' +
      '#soysLoginMask label{display:block;font-size:12px;color:#9ab;margin:10px 0 4px}' +
      '#soysLoginMask input{width:100%;box-sizing:border-box;padding:10px;border-radius:8px;' +
      'border:1px solid #2a2a40;background:#0a0a12;color:#cfe;font-size:14px}' +
      '#soysLoginMask input:focus{outline:none;border-color:#1f6feb}' +
      '#soysLoginMask .soys-err{min-height:18px;margin-top:8px;font-size:12px;color:#f96;word-break:break-all}' +
      '#soysLoginMask .soys-actions{margin-top:14px;display:flex;gap:10px}' +
      '#soysLoginMask .soys-actions button{flex:1;padding:10px;border:0;border-radius:8px;' +
      'font-size:14px;cursor:pointer}' +
      '#soysLoginMask .soys-ok{background:#1f6feb;color:#fff}' +
      '#soysLoginMask .soys-cancel{background:transparent;border:1px solid #2a2a40;color:#cfe}';
    document.head.appendChild(style);

    mask = document.createElement('div');
    mask.id = 'soysLoginMask';
    mask.innerHTML =
      '<div class="soys-card">' +
      '<h2>登录</h2><p>输入玩家名与登录密码完成验证，获取访问令牌。</p>' +
      '<label for="soysInUser">玩家名</label>' +
      '<input id="soysInUser" autocomplete="username" placeholder="游戏内玩家名">' +
      '<label for="soysInPass">登录密码</label>' +
      '<input id="soysInPass" type="password" autocomplete="current-password" placeholder="••••••••">' +
      '<label class="soys-remember" style="display:flex;align-items:center;gap:6px;margin-top:10px;font-size:12px;color:#9ab;cursor:pointer;">' +
      '<input type="checkbox" id="soysRemember" style="width:auto;flex:none;"> 记住我（本机免登录）</label>' +
      '<div class="soys-err" id="soysLoginErr"></div>' +
      '<div class="soys-actions">' +
      '<button class="soys-cancel" id="soysBtnCancel">取消</button>' +
      '<button class="soys-ok" id="soysBtnOk">登录</button>' +
      '</div></div>';
    document.body.appendChild(mask);

    document.getElementById('soysBtnCancel').onclick = closeLogin;
    document.getElementById('soysBtnOk').onclick = submitLogin;
    mask.addEventListener('click', function (e) { if (e.target === mask) closeLogin(); });
    document.addEventListener('keydown', function (e) {
      if (!mask.classList.contains('show')) return;
      if (e.key === 'Escape') closeLogin();
      if (e.key === 'Enter') submitLogin();
    });
  }

  // ===== 请求 =====
  /** 统一请求：自动带 Bearer 令牌；返回 {status, code, msg, data}。 */
  function request(path, opts) {
    opts = opts || {};
    var headers = opts.headers || {};
    var token = getToken();
    if (token) headers['Authorization'] = 'Bearer ' + token;
    if (opts.json) headers['Content-Type'] = 'application/json';
    return fetch(path, { method: opts.method || 'GET', headers: headers, body: opts.body || undefined })
      .then(function (resp) {
        return resp.text().then(function (text) {
          var body = null;
          try { body = JSON.parse(text); } catch (e) {}
          var code = body && typeof body.code === 'number' ? body.code : resp.status;
          var msg = (body && body.msg) || text || ('HTTP ' + resp.status);
          return { status: resp.status, code: code, msg: msg, data: body ? body.data : null };
        });
      });
  }

  /** 显式受保护请求：遇 401/403（HTTP 或 body.code）→ 弹登录窗 → 登录成功后重试一次。 */
  function authFetch(path, opts) {
    return request(path, opts).then(function (r) {
      if (r.code === 401 || r.code === 403) {
        return openLogin().then(function (ok) {
          if (!ok) throw new Error('未登录或已取消登录: ' + r.msg);
          return request(path, opts); // 登录成功后重试原请求
        });
      }
      return r;
    });
  }

  // ===== 弹窗控制 =====
  /** 登录窗口是否已打开（页面轮询可在打开期间暂停自动请求）。 */
  function isLoginOpen() {
    return !!(mask && mask.classList.contains('show'));
  }

  /**
   * 打开登录窗口，返回 Promise：登录成功 resolve(true)，取消 resolve(false)。
   * 幂等：窗口已打开时返回同一 Promise（不重复弹窗、不覆盖 _resolve，
   * 避免 status 等页面轮询每 2s 触发一次 401 时产生无限弹窗/悬挂 Promise）。
   */
  function openLogin() {
    ensureDom();
    document.getElementById('soysLoginErr').textContent = '';
    if (isLoginOpen() && pendingOpen) {
      return pendingOpen;
    }
    mask.classList.add('show');
    setTimeout(function () { document.getElementById('soysInUser').focus(); }, 50);
    pendingOpen = new Promise(function (resolve) {
      SoysAuth._resolve = resolve; // submitLogin / closeLogin 使用
    });
    return pendingOpen;
  }
  function closeLogin() {
    if (!mask || !mask.classList.contains('show')) return;
    mask.classList.remove('show');
    pendingOpen = null;
    if (SoysAuth._resolve) { var r = SoysAuth._resolve; SoysAuth._resolve = null; r(false); }
  }
  function submitLogin() {
    ensureDom();
    var user = document.getElementById('soysInUser').value.trim();
    var pass = document.getElementById('soysInPass').value;
    var errEl = document.getElementById('soysLoginErr');
    if (!user || !pass) { errEl.textContent = '请输入玩家名和密码'; return; }
    var rememberEl = document.getElementById('soysRemember');
    var remember = !!(rememberEl && rememberEl.checked);
    request(API + '/auth/login', { method: 'POST', json: true,
      body: JSON.stringify({ username: user, password: pass, remember: remember }) }).then(function (r) {
      if (r.code === 200 && r.data && r.data.token) {
        setToken(r.data.token);
        document.getElementById('soysInPass').value = '';
        mask.classList.remove('show');
        pendingOpen = null;
        var done = SoysAuth._resolve; SoysAuth._resolve = null;
        if (done) done(true);
        if (typeof SoysAuth.onLoginSuccess === 'function') {
          try { SoysAuth.onLoginSuccess(r.data.player, r.data.mode); } catch (e) {}
        }
      } else {
        errEl.textContent = r.msg || '登录失败';
      }
    }).catch(function (e) {
      errEl.textContent = '登录失败: ' + (e && e.message ? e.message : e);
    });
  }

  // ===== 全局拦截：任意 fetch 返回 401/403 自动弹窗；离线 cookie 自动升级 =====
  function patchFetch() {
    if (patched) return;
    patched = true;
    var orig = global.fetch;

    /** 用当前最新令牌（getToken）重试一次原请求；retryActive 防循环。 */
    function retryWithNewToken(input, init) {
      retryActive = true;
      var headers = new Headers((init && init.headers) || {});
      var token = getToken();
      if (token) headers.set('Authorization', 'Bearer ' + token);
      var retryOpts = {
        method: (init && init.method) || 'GET',
        headers: headers,
        body: init && init.body
      };
      return orig.call(global, input, retryOpts).then(function (r2) {
        retryActive = false;
        return r2;
      }, function (e2) {
        retryActive = false;
        throw e2;
      });
    }

    function rebuild(resp, text) {
      return new Response(text, { status: resp.status, statusText: resp.statusText, headers: resp.headers });
    }

    global.fetch = function (input, init) {
      return orig.call(global, input, init).then(function (resp) {
        if (retryActive) return resp; // 重试中的响应不再处理（防循环）
        return resp.text().then(function (text) {
          var body = null;
          try { body = JSON.parse(text); } catch (e) {}
          var code = body && typeof body.code === 'number' ? body.code : resp.status;

          // 离线 cookie 自动升级：玩家已进游戏在线时，服务端把离线令牌换发为在线令牌并下发
          // X-Soys-New-Token（Set-Cookie 由浏览器自动接管；此处同步 localStorage 供 Bearer 使用）
          var newToken = resp.headers.get('X-Soys-New-Token');
          if (newToken) {
            setToken(newToken);
            if (!isLoginOpen()) {
              // 带新令牌重试一次，让本次响应基于在线令牌权限；若仍 401/403 → 弹窗登录
              return retryWithNewToken(input, init).then(function (r2) {
                return r2.text().then(function (t2) {
                  var b2 = null;
                  try { b2 = JSON.parse(t2); } catch (e) {}
                  var c2 = b2 && typeof b2.code === 'number' ? b2.code : r2.status;
                  if ((c2 === 401 || c2 === 403) && !isLoginOpen()) {
                    return openLogin().then(function (ok) {
                      if (!ok) return new Response(t2, { status: r2.status, statusText: r2.statusText, headers: r2.headers });
                      return retryWithNewToken(input, init);
                    });
                  }
                  return new Response(t2, { status: r2.status, statusText: r2.statusText, headers: r2.headers });
                });
              });
            }
            return rebuild(resp, text); // 弹窗已打开（轮询暂停中）→ 仅存新令牌，交给调用方
          }

          var needLogin = resp.status === 401 || resp.status === 403 || code === 401 || code === 403;
          // 重建响应（text 已被消费，调用方仍能正常 .text()/.json()）
          if (!needLogin) return rebuild(resp, text);
          // 登录窗口已打开（如 status 面板轮询在用户输入期间又触发 401）：
          // 不再重复弹窗，直接把原始响应交给调用方（页面轮询已按 isLoginOpen 暂停）
          if (isLoginOpen()) return rebuild(resp, text);
          return openLogin().then(function (ok) {
            if (!ok) return rebuild(resp, text); // 用户取消 → 返回原始 401/403 响应
            return retryWithNewToken(input, init); // 登录成功 → 带新令牌重试一次
          });
        });
      });
    };
  }

  global.SoysAuth = {
    getToken: getToken,
    setToken: setToken,
    request: request,
    authFetch: authFetch,
    openLogin: openLogin,
    closeLogin: closeLogin,
    submitLogin: submitLogin,
    patchFetch: patchFetch,
    isLoginOpen: isLoginOpen,
    onLoginSuccess: null, // 页面可挂载: function(player, mode){}
    _resolve: null
  };
})(window);
