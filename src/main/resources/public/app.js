(function () {
  var TRAIL_PREFIX = "hub.trail.";
  var root = document.documentElement;
  var navSeq = 0;
  var navCtrl = null;
  var cur = null;
  var items = [];
  var scrollTimer = 0;
  var EASE_OUT = "cubic-bezier(.2, .8, .2, 1)";
  var EASE_IN_OUT = "cubic-bezier(.4, 0, .2, 1)";
  var EASE_SPRING = "cubic-bezier(.34, 1.56, .64, 1)";
  var reduceMotion = window.matchMedia ? window.matchMedia("(prefers-reduced-motion: reduce)") : null;

  function calm() {
    return !!reduceMotion && reduceMotion.matches;
  }

  if ("scrollRestoration" in history) history.scrollRestoration = "manual";

  function locPath() {
    return location.pathname + location.search;
  }

  function viewPath() {
    return cur ? cur.path : locPath();
  }

  function isHubState(s) {
    return !!s && s.hub === 1 && typeof s.tid === "string" && typeof s.idx === "number" && typeof s.path === "string";
  }

  function loadItems(tid) {
    try {
      var v = JSON.parse(sessionStorage.getItem(TRAIL_PREFIX + tid) || "[]");
      return Array.isArray(v) ? v : [];
    } catch (e) {
      return [];
    }
  }

  function saveItems() {
    if (!cur) return;
    try { sessionStorage.setItem(TRAIL_PREFIX + cur.tid, JSON.stringify(items)); } catch (e) { }
  }

  function pruneTrails(keep) {
    try {
      var keys = [];
      for (var i = 0; i < sessionStorage.length; i++) {
        var k = sessionStorage.key(i);
        if (k && k.indexOf(TRAIL_PREFIX) === 0 && k !== TRAIL_PREFIX + keep) keys.push(k);
      }
      keys.sort();
      while (keys.length > 4) sessionStorage.removeItem(keys.shift());
    } catch (e) { }
  }

  function newTid() {
    return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  }

  function adopt(state) {
    if (!cur || cur.tid !== state.tid) items = loadItems(state.tid);
    cur = state;
  }

  function savedScroll(state) {
    var list = cur && cur.tid === state.tid ? items : loadItems(state.tid);
    var it = list[state.idx];
    return it && it.path === state.path ? it.scroll : 0;
  }

  function rememberScroll() {
    if (!cur) return;
    var it = items[cur.idx];
    if (it && it.path === cur.path) {
      it.scroll = Math.max(0, Math.round(window.scrollY));
      saveItems();
    }
  }

  function writeEntry(path, push) {
    var idx = push ? cur.idx + 1 : cur.idx;
    var state = { hub: 1, tid: cur.tid, idx: idx, path: path };
    if (push) history.pushState(state, "", path);
    else history.replaceState(state, "", path);
    if (push) items.length = idx;
    var old = items[idx];
    cur = state;
    items[idx] = { path: path, scroll: !push && old && old.path === path ? old.scroll : 0 };
    saveItems();
  }

  function isInternal(href) {
    return !!href && href.charAt(0) === "/" && href.charAt(1) !== "/" && href.charAt(1) !== "\\";
  }

  function isDownload(href) {
    return /^\/(attachments|downloads)\/\d+(?:[?#].*)?$/.test(href);
  }

  function splitHash(href) {
    var i = href.indexOf("#");
    return i < 0 ? [href, ""] : [href.slice(0, i), href.slice(i + 1)];
  }

  function pathOnly(href) {
    return splitHash(href)[0].split("?")[0];
  }

  function toast(text) {
    var stack = document.querySelector(".flash-stack");
    if (!stack) {
      stack = document.createElement("div");
      stack.className = "flash-stack";
      document.body.insertBefore(stack, document.body.firstChild);
    }
    var el = document.createElement("div");
    el.className = "alert alert-danger alert-dismissible fade show d-flex align-items-start flash";
    el.setAttribute("role", "alert");
    el.setAttribute("data-autohide", "6000");
    var msg = document.createElement("div");
    msg.className = "flex-fill text-break";
    msg.textContent = text;
    var close = document.createElement("button");
    close.type = "button";
    close.className = "btn-close";
    close.setAttribute("data-bs-dismiss", "alert");
    close.setAttribute("aria-label", "关闭");
    el.appendChild(msg);
    el.appendChild(close);
    stack.appendChild(el);
    autoHide(el);
  }

  function closeOverlays() {
    if (!window.bootstrap) return;
    document.querySelectorAll(".modal").forEach(function (m) {
      var inst = window.bootstrap.Modal.getInstance(m);
      if (inst) inst.dispose();
    });
    document.querySelectorAll("[data-bs-toggle=dropdown]").forEach(function (d) {
      var inst = window.bootstrap.Dropdown.getInstance(d);
      if (inst) inst.dispose();
    });
  }

  function render(html) {
    var doc = new DOMParser().parseFromString(html, "text/html");
    doc.querySelectorAll("script").forEach(function (s) { s.parentNode.removeChild(s); });
    closeOverlays();
    document.title = doc.title;
    var body = document.body;
    Array.prototype.slice.call(body.attributes).forEach(function (a) { body.removeAttribute(a.name); });
    Array.prototype.slice.call(doc.body.attributes).forEach(function (a) { body.setAttribute(a.name, a.value); });
    body.replaceChildren.apply(body, Array.prototype.slice.call(doc.body.childNodes));
    init();
  }

  function settle(mode, hash, keepY) {
    if (mode === "keep") {
      window.scrollTo(0, keepY);
      if (hash) reveal(hash, true);
      return;
    }
    if (hash && reveal(hash)) return;
    if (typeof mode === "number") {
      window.scrollTo(0, mode);
      return;
    }
    window.scrollTo(0, 0);
    var auto = document.querySelector("[autofocus]");
    if (auto) auto.focus({ preventScroll: true });
  }

  function enterPage() {
    var area = document.querySelector(".editor-area");
    var el = area ? area.firstElementChild : null;
    if (!el || typeof el.animate !== "function") return;
    if (calm()) el.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 120, easing: "ease" });
    else el.animate([{ opacity: 0, transform: "translateY(6px)" }, { opacity: 1, transform: "none" }], { duration: 220, easing: EASE_OUT });
  }

  function releaseForm(form) {
    if (!form) return;
    form.dataset.sending = "";
    form.querySelectorAll("button[type=submit]").forEach(function (b) { b.disabled = false; });
    form.querySelectorAll("input[data-confirm-name]").forEach(syncConfirmName);
  }

  function onLoginPage() {
    return document.body.classList.contains("bare") && !!document.querySelector('form[action="/login"]');
  }

  function navigate(href, opts) {
    opts = opts || {};
    var seq = ++navSeq;
    if (navCtrl) navCtrl.abort();
    var ctrl = window.AbortController ? new AbortController() : null;
    navCtrl = ctrl;
    var loadingTimer = window.setTimeout(function () { root.classList.add("hub-loading"); }, 120);
    var hops = 0;
    var hash = "";

    function step(target, method, body) {
      var parts = splitHash(target);
      hash = parts[1];
      return fetch(parts[0], {
        method: method,
        body: body,
        headers: { "X-Hub-Nav": "1" },
        credentials: "same-origin",
        cache: "no-store",
        signal: ctrl ? ctrl.signal : undefined
      }).then(function (res) {
        var loc = res.headers.get("X-Hub-Location");
        if (loc && isInternal(loc) && hops < 5) {
          hops++;
          if (method !== "GET" && cur) {
            var lp = splitHash(loc);
            if (lp[0].indexOf("?") < 0 && pathOnly(lp[0]) === pathOnly(cur.path)) loc = cur.path + (lp[1] ? "#" + lp[1] : "");
          }
          return step(loc, "GET", null);
        }
        var path = parts[0];
        if (res.redirected && res.url) {
          var u = new URL(res.url);
          path = u.pathname + u.search;
        }
        var type = res.headers.get("Content-Type") || "";
        return res.text().then(function (text) {
          return { html: text, path: path, method: method, htmlOk: type.indexOf("text/html") >= 0 };
        });
      });
    }

    return step(href, opts.method || "GET", opts.body || null).then(function (r) {
      if (seq !== navSeq) return;
      if (!r.htmlOk) {
        window.location.assign(r.path);
        return;
      }
      rememberScroll();
      var keepY = window.scrollY;
      var mode = "top";
      var from = cur ? pathOnly(cur.path) : "";
      var to = opts.pop ? opts.pop.path : r.method === "GET" ? r.path : null;
      var moved = to !== null && pathOnly(to) !== from;
      if (opts.pop) {
        adopt(opts.pop);
        items[cur.idx] = { path: cur.path, scroll: opts.scroll || 0 };
        saveItems();
        mode = opts.scroll || 0;
      } else if (r.method === "GET" && cur) {
        var same = r.path === cur.path;
        writeEntry(r.path, !same && !onLoginPage());
        if (same) mode = opts.form ? "keep" : "top";
        else if (typeof opts.scroll === "number") mode = opts.scroll;
      }
      render(r.html);
      settle(mode, hash, keepY);
      if (moved) enterPage();
    }).catch(function (err) {
      if (seq !== navSeq || (err && err.name === "AbortError")) return;
      releaseForm(opts.form);
      toast("网络出错了，请稍后再试");
    }).then(function () {
      window.clearTimeout(loadingTimer);
      if (seq === navSeq) root.classList.remove("hub-loading");
    });
  }

  function backTo(target) {
    var want = pathOnly(target);
    if (cur) {
      rememberScroll();
      for (var i = cur.idx - 1; i >= 0; i--) {
        var it = items[i];
        if (!it || pathOnly(it.path) !== want) continue;
        if (i === cur.idx - 1) history.back();
        else navigate(it.path, { scroll: it.scroll });
        return;
      }
    }
    navigate(target);
  }

  window.addEventListener("popstate", function (event) {
    var st = event.state;
    if (!isHubState(st)) return;
    rememberScroll();
    navigate(st.path, { pop: st, scroll: savedScroll(st) });
  });

  window.addEventListener("scroll", function () {
    if (scrollTimer) return;
    scrollTimer = window.setTimeout(function () {
      scrollTimer = 0;
      rememberScroll();
    }, 150);
  }, { passive: true });

  window.addEventListener("pagehide", rememberScroll);

  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement) || !form.hasAttribute("data-composer")) return;
    var body = form.querySelector("textarea[name=body]");
    var files = form.querySelector("input[type=file]");
    var error = form.querySelector(".composer-error");
    var empty = (!body || body.value.trim() === "") && (!files || !files.files || files.files.length === 0);
    if (empty) {
      event.preventDefault();
      event.stopImmediatePropagation();
      if (error) error.hidden = false;
      if (body) body.focus();
    } else if (error) {
      error.hidden = true;
    }
  }, true);

  document.addEventListener("input", function (event) {
    var t = event.target;
    if (!(t instanceof HTMLTextAreaElement) || !t.form || !t.form.hasAttribute("data-composer")) return;
    var error = t.form.querySelector(".composer-error");
    if (error) error.hidden = true;
  });

  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement)) return;
    var editor = form.querySelector("[data-items-editor]");
    var bad = editor ? checkItems(editor) : null;
    if (!bad) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    bad.focus();
  }, true);

  function syncConfirmName(input) {
    if (!input.form) return;
    var ok = input.value.trim() === (input.getAttribute("data-confirm-name") || "").trim();
    input.form.querySelectorAll("button[type=submit]").forEach(function (b) { b.disabled = !ok; });
  }

  document.addEventListener("input", function (event) {
    var t = event.target;
    if (t instanceof HTMLInputElement && t.hasAttribute("data-confirm-name")) syncConfirmName(t);
  });

  document.addEventListener("shown.bs.modal", function (event) {
    var input = event.target instanceof Element ? event.target.querySelector("input[data-confirm-name]") : null;
    if (input) input.focus();
  });

  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement) || event.defaultPrevented) return;
    var message = form.getAttribute("data-confirm");
    if (message && !window.confirm(message)) {
      event.preventDefault();
      return;
    }
    if (form.dataset.sending === "1") {
      event.preventDefault();
      return;
    }
    form.dataset.sending = "1";
    var buttons = form.querySelectorAll("button[type=submit]");
    buttons.forEach(function (b) { b.disabled = true; });
    window.setTimeout(function () { releaseForm(form); }, 8000);
  }, true);

  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement) || event.defaultPrevented) return;
    var action = form.getAttribute("action") || viewPath();
    if (!isInternal(action)) return;
    event.preventDefault();
    var data = new FormData(form);
    if ((form.getAttribute("method") || "get").toLowerCase() === "post") {
      var multipart = (form.getAttribute("enctype") || "").toLowerCase() === "multipart/form-data";
      navigate(action, { method: "POST", body: multipart ? data : new URLSearchParams(data), form: form });
    } else {
      var q = new URLSearchParams(data).toString();
      navigate(splitHash(action)[0].split("?")[0] + (q ? "?" + q : ""), { form: form });
    }
  });

  function hideHref(a) {
    var href = a.getAttribute("href");
    if (!isInternal(href)) return;
    if (a.hasAttribute("target") || a.hasAttribute("data-bs-toggle")) return;
    a.setAttribute("data-href", href);
    a.removeAttribute("href");
    if (!a.hasAttribute("role")) a.setAttribute("role", "link");
    if (!a.hasAttribute("tabindex")) a.setAttribute("tabindex", "0");
  }

  function linkOf(event) {
    var el = event.target;
    return el instanceof Element ? el.closest("a[data-href]") : null;
  }

  function go(a, newTab) {
    var href = a.getAttribute("data-href");
    if (!href) return;
    if (newTab) window.open(href, "_blank", "noopener");
    else if (isDownload(href)) window.location.assign(href);
    else if (a.classList.contains("back-link") || a.hasAttribute("data-back")) backTo(href);
    else navigate(href);
  }

  document.addEventListener("click", function (event) {
    var a = linkOf(event);
    if (!a || event.button !== 0) return;
    event.preventDefault();
    go(a, event.ctrlKey || event.metaKey || event.shiftKey);
  });

  document.addEventListener("mousedown", function (event) {
    if (event.button === 1 && linkOf(event)) event.preventDefault();
  });

  document.addEventListener("auxclick", function (event) {
    var a = linkOf(event);
    if (!a || event.button !== 1) return;
    event.preventDefault();
    go(a, true);
  });

  document.addEventListener("keydown", function (event) {
    if (event.key !== "Enter") return;
    var a = linkOf(event);
    if (!a) return;
    event.preventDefault();
    go(a, event.ctrlKey || event.metaKey);
  });

  function renderFiles(input) {
    var selector = input.getAttribute("data-file-list");
    var box = selector ? document.querySelector(selector) : null;
    if (!box) return;
    box.textContent = "";
    var files = Array.prototype.slice.call(input.files || []);
    files.forEach(function (f) {
      var chip = document.createElement("span");
      chip.className = "file-chip";
      var icon = document.createElement("i");
      icon.className = "bi bi-paperclip";
      var name = document.createElement("span");
      name.className = "file-chip-name";
      name.textContent = f.name;
      chip.appendChild(icon);
      chip.appendChild(name);
      box.appendChild(chip);
    });
    if (files.length > 0) {
      var clear = document.createElement("button");
      clear.type = "button";
      clear.className = "composer-clear";
      clear.textContent = "清除";
      clear.addEventListener("click", function () {
        input.value = "";
        renderFiles(input);
        input.focus();
      });
      box.appendChild(clear);
    }
    box.hidden = files.length === 0;
  }

  document.addEventListener("change", function (event) {
    var t = event.target;
    if (!(t instanceof HTMLInputElement) || t.type !== "file" || !t.hasAttribute("data-file-list")) return;
    renderFiles(t);
    var error = t.form ? t.form.querySelector(".composer-error") : null;
    if (error && t.files && t.files.length > 0) error.hidden = true;
  });

  function land(el) {
    if (!el.classList.contains("tl-comment")) return;
    if (el.classList.contains("is-landed")) {
      el.classList.remove("is-landed");
      void el.offsetWidth;
    }
    el.classList.add("is-landed");
  }

  document.addEventListener("animationend", function (event) {
    var t = event.target;
    if (event.animationName === "hub-landed" && t instanceof Element) t.classList.remove("is-landed");
  });

  function reveal(id, gentle) {
    if (!id) return false;
    var el = document.getElementById(decodeURIComponent(id));
    if (!el) return false;
    var box = el.closest("details");
    if (box && !box.open) box.open = true;
    land(el);
    if (gentle) {
      var rect = el.getBoundingClientRect();
      if (rect.top >= 0 && rect.bottom <= window.innerHeight) return true;
      el.scrollIntoView({ block: "nearest" });
      return true;
    }
    el.scrollIntoView();
    return true;
  }

  function foldLabel(btn, folded) {
    var text = folded ? "展开" : "收起";
    btn.setAttribute("aria-expanded", folded ? "false" : "true");
    btn.setAttribute("title", text);
    btn.setAttribute("aria-label", text + "这条补充信息");
  }

  function foldMotion(opening) {
    if (calm()) return { duration: 150, easing: "ease" };
    return opening ? { duration: 500, easing: EASE_SPRING } : { duration: 280, easing: EASE_IN_OUT };
  }

  function foldTo(box, open, apply, fade) {
    var run = box._hubFold;
    var from = box.getBoundingClientRect().height;
    var fadeFrom = open ? 0 : 1;
    if (run) {
      if (fade) fadeFrom = parseFloat(getComputedStyle(fade).opacity);
      box._hubFold = null;
      run.anims.forEach(function (a) { a.cancel(); });
    }
    box.classList.remove("is-morph", "is-closing");
    apply(open);
    var to = box.getBoundingClientRect().height;
    if (typeof box.animate !== "function" || Math.abs(to - from) < 1) return;
    if (!open) {
      apply(true);
      box.classList.add("is-closing");
    }
    box.classList.add("is-morph");
    var m = foldMotion(open);
    var anims = [box.animate([{ height: from + "px" }, { height: to + "px" }], { duration: m.duration, easing: m.easing, fill: "forwards" })];
    if (fade) anims.push(fade.animate([{ opacity: fadeFrom }, { opacity: open ? 1 : 0 }], { duration: Math.round(m.duration * (open ? 0.6 : 0.8)), easing: "ease", fill: "forwards" }));
    var mine = { anims: anims };
    box._hubFold = mine;
    anims[0].onfinish = function () {
      if (box._hubFold !== mine) return;
      box._hubFold = null;
      if (!open) apply(false);
      box.classList.remove("is-morph", "is-closing");
      anims.forEach(function (a) { a.cancel(); });
    };
  }

  document.addEventListener("click", function (event) {
    var t = event.target;
    var sum = t instanceof Element ? t.closest("summary") : null;
    var box = sum ? sum.parentElement : null;
    if (!box || box.tagName !== "DETAILS" || box.firstElementChild !== sum || !box.matches(".thread-more, .dl-older")) return;
    event.preventDefault();
    var open = box.classList.contains("is-closing") || !box.open;
    foldTo(box, open, function (o) { box.open = o; }, null);
  });

  function saveFold(btn) {
    if (btn.dataset.saving === "1") {
      btn.dataset.dirty = "1";
      return;
    }
    var box = btn.closest("[data-csrf]");
    var article = btn.closest(".tl-comment, .req-list");
    if (!box || !article) return;
    btn.dataset.saving = "1";
    btn.dataset.dirty = "";
    var data = new URLSearchParams();
    data.append("_csrf", box.getAttribute("data-csrf"));
    data.append("folded", btn.getAttribute("aria-expanded") === "false" ? "1" : "0");
    var finish = function (ok) {
      btn.dataset.saving = "";
      if (!ok) toast("折叠状态没保存上，请稍后再试");
      else if (btn.dataset.dirty === "1") saveFold(btn);
    };
    fetch(btn.getAttribute("data-comment-fold") || btn.getAttribute("data-items-fold"), {
      method: "POST",
      body: data,
      credentials: "same-origin",
      cache: "no-store",
      keepalive: true
    }).then(function (res) { finish(res.status === 204); }, function () { finish(false); });
  }

  document.addEventListener("click", function (event) {
    var t = event.target;
    var btn = t instanceof Element ? t.closest("[data-items-fold]") : null;
    var list = btn ? btn.closest(".req-list") : null;
    if (!list) return;
    var folded = btn.getAttribute("aria-expanded") !== "false";
    btn.setAttribute("aria-expanded", folded ? "false" : "true");
    btn.setAttribute("title", folded ? "展开" : "收起");
    foldTo(list, !folded, function (o) { list.classList.toggle("is-folded", !o); }, list.querySelector(".req-items"));
    saveFold(btn);
  });

  function setFold(article, folded) {
    var btn = article.querySelector("[data-comment-fold]");
    if (!btn || (btn.getAttribute("aria-expanded") === "false") === folded) return;
    foldLabel(btn, folded);
    foldTo(article, !folded, function (o) { article.classList.toggle("is-folded", !o); }, null);
    saveFold(btn);
  }

  function morph(box, apply, fade) {
    var run = box._hubFold;
    var from = box.getBoundingClientRect().height;
    if (run) {
      box._hubFold = null;
      run.anims.forEach(function (a) { a.cancel(); });
    }
    box.classList.remove("is-morph", "is-closing");
    apply();
    if (typeof box.animate !== "function") return;
    var to = box.getBoundingClientRect().height;
    var quiet = calm();
    var anims = [];
    if (Math.abs(to - from) >= 1) {
      box.classList.add("is-morph");
      anims.push(box.animate([{ height: from + "px" }, { height: to + "px" }], { duration: quiet ? 150 : 320, easing: quiet ? "ease" : EASE_OUT }));
    }
    if (fade) anims.push(fade.animate([{ opacity: 0 }, { opacity: 1 }], { duration: quiet ? 150 : 240, easing: "ease" }));
    if (!anims.length) return;
    var mine = { anims: anims };
    box._hubFold = mine;
    anims[0].onfinish = function () {
      if (box._hubFold !== mine) return;
      box._hubFold = null;
      box.classList.remove("is-morph");
      anims.forEach(function (a) { a.cancel(); });
    };
  }

  function toggleEdit(article, on) {
    var form = article.querySelector(".tl-edit-form");
    if (!form) return;
    var body = article.querySelector(".tl-comment-body");
    var tools = article.querySelector(".tl-tools");
    var pencil = article.querySelector("[data-comment-edit]");
    var btn = article.querySelector("[data-comment-fold]");
    var folded = !!btn && btn.getAttribute("aria-expanded") === "false";
    var swap = function () {
      if (!on) form.reset();
      form.hidden = !on;
      if (body) body.hidden = on;
      if (tools) tools.hidden = on;
    };
    if (folded) swap();
    else morph(article, swap, on ? form : body);
    if (on && folded) setFold(article, false);
    if (on) {
      var area = form.querySelector("textarea");
      if (area) {
        area.focus();
        area.setSelectionRange(area.value.length, area.value.length);
      }
    } else if (pencil) {
      pencil.focus();
    }
  }

  document.addEventListener("click", function (event) {
    var t = event.target;
    if (!(t instanceof Element)) return;
    var fold = t.closest("[data-comment-fold]");
    if (fold) {
      var folding = fold.closest(".tl-comment");
      if (folding) setFold(folding, fold.getAttribute("aria-expanded") !== "false");
      return;
    }
    var edit = t.closest("[data-comment-edit]");
    var btn = edit || t.closest("[data-comment-cancel]");
    var article = btn ? btn.closest(".tl-comment") : null;
    if (article) toggleEdit(article, !!edit);
  });

  document.addEventListener("keydown", function (event) {
    if (event.key !== "Escape") return;
    var t = event.target;
    if (!(t instanceof HTMLTextAreaElement) || !t.form || !t.form.classList.contains("tl-edit-form")) return;
    var article = t.closest(".tl-comment");
    if (!article) return;
    event.preventDefault();
    toggleEdit(article, false);
  });


  document.addEventListener("keydown", function (event) {
    if (event.key !== "Enter" || !(event.ctrlKey || event.metaKey)) return;
    var t = event.target;
    if (!(t instanceof HTMLTextAreaElement) || !t.classList.contains("composer-input")) return;
    event.preventDefault();
    if (t.form && t.form.requestSubmit) t.form.requestSubmit();
  });

  function itemRowOf(el) {
    return el instanceof Element ? el.closest("[data-item-row]") : null;
  }

  function itemInput(row) {
    return row ? row.querySelector('[name="item_text"]') : null;
  }

  function isItemText(el) {
    return el instanceof HTMLTextAreaElement && el.name === "item_text";
  }

  var itemMirror = null;
  var mirrorWidth = "";
  var mirrorEdge = 0;
  var mirrorLeast = 0;

  function openHeight(el) {
    var field = el.closest("[data-item-field]");
    if (!itemMirror || !itemMirror.isConnected) {
      itemMirror = document.createElement("textarea");
      itemMirror.className = "form-control form-control-sm req-edit-text req-edit-mirror";
      itemMirror.setAttribute("aria-hidden", "true");
      itemMirror.tabIndex = -1;
      document.body.appendChild(itemMirror);
      mirrorWidth = "";
    }
    var width = (field ? field.clientWidth : el.offsetWidth) + "px";
    if (width !== mirrorWidth) {
      itemMirror.style.width = width;
      var cs = getComputedStyle(itemMirror);
      mirrorEdge = parseFloat(cs.borderTopWidth) + parseFloat(cs.borderBottomWidth);
      itemMirror.value = "\n\n";
      mirrorLeast = itemMirror.scrollHeight + mirrorEdge;
      mirrorWidth = width;
    }
    itemMirror.value = el.value || " ";
    var need = itemMirror.scrollHeight + mirrorEdge;
    var most = Math.max(mirrorLeast, Math.round(window.innerHeight * 0.45));
    return Math.min(Math.max(need, mirrorLeast), most);
  }

  function markLong(el) {
    var field = el.closest("[data-item-field]");
    var row = itemRowOf(el);
    if (!field || !row) return;
    var open = row.classList.contains("is-open");
    field.classList.toggle("is-long", !open && el.scrollHeight > el.clientHeight + 1);
  }

  function openItem(el) {
    var row = itemRowOf(el);
    if (!row) return;
    var field = el.closest("[data-item-field]");
    if (field) field.classList.remove("is-long");
    row.classList.add("is-open");
    el.style.height = openHeight(el) + "px";
  }

  function stopGrow(row) {
    var run = row._hubGrow;
    if (!run) return;
    row._hubGrow = null;
    run.anims.forEach(function (a) { a.cancel(); });
    row.classList.remove("is-morph", "is-growing");
  }

  function closeItem(el) {
    var row = itemRowOf(el);
    if (!row || !row.classList.contains("is-open")) return;
    stopGrow(row);
    row.classList.remove("is-open", "is-sizing");
    el.style.height = "";
    el.scrollTop = 0;
    window.setTimeout(function () { if (el.isConnected) markLong(el); }, 700);
  }

  var itemPointerDown = false;
  var itemPendingClose = [];

  function flushItemCloses() {
    itemPointerDown = false;
    var list = itemPendingClose;
    itemPendingClose = [];
    list.forEach(function (el) { if (document.activeElement !== el) closeItem(el); });
  }

  document.addEventListener("pointerdown", function () { itemPointerDown = true; }, true);
  document.addEventListener("pointerup", function () { window.setTimeout(flushItemCloses, 0); }, true);
  document.addEventListener("pointercancel", function () { window.setTimeout(flushItemCloses, 0); }, true);

  document.addEventListener("focusin", function (event) {
    var t = event.target;
    if (!isItemText(t)) return;
    var row = itemRowOf(t);
    if (row && row._hubGrow) return;
    openItem(t);
  });

  document.addEventListener("focusout", function (event) {
    var t = event.target;
    if (!isItemText(t)) return;
    window.setTimeout(function () {
      if (document.activeElement === t) return;
      if (itemPointerDown) itemPendingClose.push(t);
      else closeItem(t);
    }, 0);
  });

  document.addEventListener("transitionend", function (event) {
    if (isItemText(event.target) && event.propertyName === "height") markLong(event.target);
  });

  var itemResizeTimer = 0;
  window.addEventListener("resize", function () {
    window.clearTimeout(itemResizeTimer);
    itemResizeTimer = window.setTimeout(function () {
      document.querySelectorAll('[data-item-field] [name="item_text"]').forEach(function (el) {
        var row = itemRowOf(el);
        if (row && row.classList.contains("is-open")) el.style.height = openHeight(el) + "px";
        else markLong(el);
      });
    }, 120);
  });

  function focusEnd(input) {
    if (!input) return;
    input.focus();
    var n = input.value.length;
    input.setSelectionRange(n, n);
  }

  var itemSeq = 0;

  function keyRow(row) {
    var ref = row.querySelector('input[name="item_ref"]');
    if (!ref || ref.value) return;
    var key = "n" + (++itemSeq);
    ref.value = key;
    var file = row.querySelector("[data-att-input]");
    var label = row.querySelector("[data-att-label]");
    if (file) {
      file.id = "if_" + key;
      file.setAttribute("data-att-name", "item_files_" + key);
    }
    if (label) label.htmlFor = "if_" + key;
  }

  function rowHasFiles(row) {
    var file = row.querySelector("[data-att-input]");
    return !!row.querySelector("[data-att-saved]") || (!!file && pickedFiles(file).length > 0);
  }

  function checkItems(editor) {
    var bad = null;
    editor.querySelectorAll("[data-item-list] [data-item-row]").forEach(function (row) {
      var input = itemInput(row);
      if (!input) return;
      var flag = input.value.trim() === "" && rowHasFiles(row);
      input.classList.toggle("is-invalid", flag);
      if (flag) {
        input.setAttribute("aria-invalid", "true");
        input.setAttribute("aria-describedby", "f_items_err");
      } else {
        input.removeAttribute("aria-invalid");
        input.removeAttribute("aria-describedby");
      }
      if (flag && !bad) bad = input;
    });
    var msg = editor.querySelector("[data-items-error]");
    if (msg) msg.classList.toggle("d-block", !!bad);
    return bad;
  }

  function recheckItems(editor) {
    if (editor && editor.querySelector("[data-items-error].d-block")) checkItems(editor);
  }

  function addItemRow(editor, after, text) {
    var tpl = editor.querySelector("template[data-item-template]");
    var list = editor.querySelector("[data-item-list]");
    if (!tpl || !list) return null;
    var row = tpl.content.firstElementChild.cloneNode(true);
    keyRow(row);
    var input = itemInput(row);
    if (text) input.value = text;
    if (after && after.parentNode === list) list.insertBefore(row, after.nextSibling);
    else list.appendChild(row);
    if (text) markLong(input);
    return input;
  }

  function rowSibling(row, back) {
    var s = back ? row.previousElementSibling : row.nextElementSibling;
    while (s && !s.hasAttribute("data-item-row")) s = back ? s.previousElementSibling : s.nextElementSibling;
    return s;
  }

  function gapEdge(row) {
    var gap = parseFloat(getComputedStyle(row.parentNode).rowGap) || 0;
    if (!gap) return null;
    if (row.previousElementSibling) return { side: "marginTop", size: -gap + "px" };
    if (row.nextElementSibling) return { side: "marginBottom", size: -gap + "px" };
    return null;
  }

  function growIn(row) {
    var input = itemInput(row);
    if (!input) return;
    row.classList.add("is-growing");
    openItem(input);
    if (typeof row.animate !== "function") {
      row.classList.remove("is-growing");
      return;
    }
    var anims = [];
    if (calm()) {
      anims.push(row.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 150, easing: "ease" }));
    } else {
      var h = row.getBoundingClientRect().height;
      var th = input.getBoundingClientRect().height;
      var edge = gapEdge(row);
      var a = { height: "0px" };
      var b = { height: h + "px" };
      if (edge) {
        a[edge.side] = edge.size;
        b[edge.side] = "0px";
      }
      row.classList.add("is-morph");
      var timing = { duration: 500, easing: EASE_SPRING };
      anims.push(row.animate([a, b], timing));
      anims.push(input.animate([{ height: Math.max(0, th - h) + "px" }, { height: th + "px" }], timing));
      anims.push(row.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 180, easing: "ease" }));
    }
    var mine = { anims: anims };
    row._hubGrow = mine;
    anims[0].onfinish = function () {
      if (row._hubGrow !== mine) return;
      stopGrow(row);
      if (document.activeElement !== input) closeItem(input);
    };
  }

  function shrinkOut(row) {
    var from = row.getBoundingClientRect().height;
    stopGrow(row);
    row.removeAttribute("data-item-row");
    row.removeAttribute("data-att-scope");
    row.querySelectorAll("[name]").forEach(function (el) { el.removeAttribute("name"); });
    row.querySelectorAll("[id]").forEach(function (el) { el.removeAttribute("id"); });
    row.setAttribute("inert", "");
    row.setAttribute("aria-hidden", "true");
    row.classList.add("is-ghost");
    if (typeof row.animate !== "function" || calm()) {
      row.parentNode.removeChild(row);
      return;
    }
    var h = from + "px";
    var edge = gapEdge(row);
    var a = { height: h, opacity: 1, easing: "ease-out" };
    var b = { height: h, opacity: 0, offset: 0.4, easing: EASE_IN_OUT };
    var c = { height: "0px", opacity: 0 };
    if (edge) {
      a[edge.side] = "0px";
      b[edge.side] = "0px";
      c[edge.side] = edge.size;
    }
    var anim = row.animate([a, b, c], { duration: 340, fill: "forwards" });
    anim.onfinish = function () { if (row.parentNode) row.parentNode.removeChild(row); };
  }

  function removeItemRow(row, back) {
    var editor = row.closest("[data-items-editor]");
    var list = row.parentNode;
    var prev = rowSibling(row, true);
    var next = rowSibling(row, false);
    if (prev || next) {
      shrinkOut(row);
    } else {
      list.removeChild(row);
      if (editor) addItemRow(editor, null, "");
    }
    var target = back ? (prev || next) : (next || prev);
    if (target) {
      focusEnd(itemInput(target));
    } else if (editor) {
      var add = editor.querySelector("[data-item-add]");
      if (add) add.focus();
    }
    recheckItems(editor);
  }

  document.addEventListener("click", function (event) {
    var t = event.target;
    if (!(t instanceof Element)) return;
    var add = t.closest("[data-item-add]");
    if (add) {
      var editor = add.closest("[data-items-editor]");
      var fresh = editor ? addItemRow(editor, null, "") : null;
      if (fresh) {
        growIn(itemRowOf(fresh));
        focusEnd(fresh);
      }
      return;
    }
    var del = t.closest("[data-item-del]");
    var row = del ? itemRowOf(del) : null;
    if (row) removeItemRow(row, false);
  });

  document.addEventListener("keydown", function (event) {
    var t = event.target;
    if (!isItemText(t)) return;
    if (event.isComposing || event.keyCode === 229) return;
    var row = itemRowOf(t);
    var editor = row ? row.closest("[data-items-editor]") : null;
    if (!editor) return;
    if (event.key === "Backspace" && t.value === "" && !event.repeat && !rowHasFiles(row) && (rowSibling(row, true) || rowSibling(row, false))) {
      event.preventDefault();
      removeItemRow(row, true);
    } else if ((event.key === "ArrowUp" || event.key === "ArrowDown") && !event.shiftKey && !event.altKey && !event.ctrlKey && !event.metaKey) {
      var up = event.key === "ArrowUp";
      var at = up ? 0 : t.value.length;
      if (t.selectionStart !== at || t.selectionEnd !== at) return;
      var sib = rowSibling(row, up);
      if (sib) {
        event.preventDefault();
        focusEnd(itemInput(sib));
      }
    }
  });

  document.addEventListener("input", function (event) {
    var t = event.target;
    if (!isItemText(t)) return;
    var row = itemRowOf(t);
    if (row && row.classList.contains("is-open")) {
      row.classList.add("is-sizing");
      window.clearTimeout(t._hubSizing);
      t._hubSizing = window.setTimeout(function () { row.classList.remove("is-sizing"); }, 220);
      t.style.height = openHeight(t) + "px";
    }
    recheckItems(t.closest("[data-items-editor]"));
  });

  var canSetFiles = (function () {
    try {
      return typeof DataTransfer === "function" && !!new DataTransfer().files;
    } catch (e) {
      return false;
    }
  })();

  function sizeText(n) {
    if (n < 1024) return n + " B";
    if (n < 1048576) return (n / 1024).toFixed(1) + " KB";
    if (n < 1073741824) return (n / 1048576).toFixed(1) + " MB";
    return (n / 1073741824).toFixed(2) + " GB";
  }

  function attScope(el) {
    return el instanceof Element ? el.closest("[data-att-scope]") : null;
  }

  function pickedFiles(input) {
    return input._hubFiles || [];
  }

  function syncAttName(input) {
    var name = input.getAttribute("data-att-name");
    if (name && pickedFiles(input).length) input.name = name;
    else input.removeAttribute("name");
  }

  function sameFile(a, b) {
    return a.name === b.name && a.size === b.size && a.lastModified === b.lastModified;
  }

  function newChip(f, i) {
    var chip = document.createElement("span");
    chip.className = "file-chip is-new";
    chip.setAttribute("data-att-new", String(i));
    var body = document.createElement("span");
    body.className = "file-chip-link";
    var icon = document.createElement("i");
    icon.className = "bi bi-paperclip";
    icon.setAttribute("aria-hidden", "true");
    var name = document.createElement("span");
    name.className = "file-chip-name";
    name.textContent = f.name;
    var size = document.createElement("span");
    size.className = "file-chip-size";
    size.textContent = sizeText(f.size);
    body.appendChild(icon);
    body.appendChild(name);
    body.appendChild(size);
    var del = document.createElement("button");
    del.type = "button";
    del.className = "file-chip-del";
    del.title = "移除";
    del.setAttribute("aria-label", "移除附件：" + f.name);
    del.setAttribute("data-att-unpick", String(i));
    var x = document.createElement("i");
    x.className = "bi bi-x-lg";
    x.setAttribute("aria-hidden", "true");
    del.appendChild(x);
    chip.appendChild(body);
    chip.appendChild(del);
    return chip;
  }

  function drawPicked(input) {
    var scope = attScope(input);
    var list = scope ? scope.querySelector("[data-att-list]") : null;
    if (!list) return;
    list.querySelectorAll("[data-att-new]").forEach(function (c) { c.parentNode.removeChild(c); });
    pickedFiles(input).forEach(function (f, i) { list.appendChild(newChip(f, i)); });
    list.hidden = !list.querySelector(".file-chip");
  }

  function setPicked(input, files) {
    input._hubFiles = files;
    if (canSetFiles) {
      var dt = new DataTransfer();
      files.forEach(function (f) { dt.items.add(f); });
      input.files = dt.files;
    } else if (!files.length) {
      input.value = "";
    }
    syncAttName(input);
    drawPicked(input);
  }

  function pickFiles(input) {
    var max = parseInt(input.getAttribute("data-max-bytes"), 10) || 0;
    var fresh = Array.prototype.slice.call(input.files || []);
    var keep = canSetFiles ? pickedFiles(input).slice() : [];
    var big = [];
    fresh.forEach(function (f) {
      if (max && f.size > max) big.push(f.name);
      else if (f.size > 0 && !keep.some(function (g) { return sameFile(g, f); })) keep.push(f);
    });
    if (big.length) {
      toast("「" + big.join("」「") + "」超过单个 " + sizeText(max) + " 的上限，没有加进来");
      if (!canSetFiles) keep = [];
    }
    setPicked(input, keep);
  }

  function focusChip(list, index, fallback) {
    var dels = list ? list.querySelectorAll(".file-chip-del") : [];
    var target = dels.length ? dels[Math.min(Math.max(index, 0), dels.length - 1)] : fallback;
    if (target) target.focus();
  }

  document.addEventListener("change", function (event) {
    var t = event.target;
    if (!(t instanceof HTMLInputElement) || t.type !== "file" || !t.hasAttribute("data-att-input")) return;
    pickFiles(t);
    recheckItems(t.closest("[data-items-editor]"));
  });

  document.addEventListener("click", function (event) {
    var t = event.target;
    if (!(t instanceof Element)) return;
    var btn = t.closest("[data-att-unpick], [data-att-drop]");
    var scope = btn ? attScope(btn) : null;
    if (!scope) return;
    var list = scope.querySelector("[data-att-list]");
    var input = scope.querySelector("[data-att-input]");
    var index = list ? Array.prototype.indexOf.call(list.querySelectorAll(".file-chip-del"), btn) : 0;
    if (btn.hasAttribute("data-att-unpick")) {
      var at = parseInt(btn.getAttribute("data-att-unpick"), 10);
      if (input) setPicked(input, canSetFiles ? pickedFiles(input).filter(function (f, j) { return j !== at; }) : []);
    } else {
      var chip = btn.closest("[data-att-saved]");
      if (!chip) return;
      var gone = document.createElement("input");
      gone.type = "hidden";
      gone.name = "att_remove";
      gone.value = chip.getAttribute("data-att-saved");
      scope.appendChild(gone);
      chip.parentNode.removeChild(chip);
      if (list) list.hidden = !list.querySelector(".file-chip");
    }
    focusChip(list, index, input);
    recheckItems(scope.closest("[data-items-editor]"));
  });

  function markItem(input) {
    var row = input.closest(".req-item");
    if (row) row.classList.toggle("is-checked", input.checked);
  }

  function saveItem(input) {
    if (input.dataset.saving === "1") {
      input.dataset.dirty = "1";
      return;
    }
    var box = input.closest("[data-csrf]");
    if (!box) return;
    var want = input.checked;
    input.dataset.saving = "1";
    input.dataset.dirty = "";
    var data = new URLSearchParams();
    data.append("_csrf", box.getAttribute("data-csrf"));
    data.append("value", want ? "1" : "0");
    var finish = function (ok) {
      input.dataset.saving = "";
      if (!ok) {
        input.checked = input.dataset.saved === "1";
        markItem(input);
        toast("勾选没保存上，请稍后再试");
        return;
      }
      input.dataset.saved = want ? "1" : "0";
      if (input.dataset.dirty === "1" && input.checked !== want) saveItem(input);
    };
    fetch(input.getAttribute("data-item-toggle"), {
      method: "POST",
      body: data,
      credentials: "same-origin",
      cache: "no-store",
      keepalive: true
    }).then(function (res) { finish(res.status === 204); }, function () { finish(false); });
  }

  document.addEventListener("change", function (event) {
    var t = event.target;
    if (!(t instanceof HTMLInputElement) || !t.hasAttribute("data-item-toggle")) return;
    markItem(t);
    saveItem(t);
  });

  function autoHide(el) {
    var ms = parseInt(el.getAttribute("data-autohide"), 10);
    if (!ms || el.dataset.autohideOn === "1") return;
    el.dataset.autohideOn = "1";
    var timer = 0;
    var close = function () {
      if (!el.isConnected) return;
      if (window.bootstrap && window.bootstrap.Alert) window.bootstrap.Alert.getOrCreateInstance(el).close();
      else if (el.parentNode) el.parentNode.removeChild(el);
    };
    var start = function (delay) {
      window.clearTimeout(timer);
      timer = window.setTimeout(close, delay);
    };
    var stop = function () { window.clearTimeout(timer); };
    el.addEventListener("mouseenter", stop);
    el.addEventListener("focusin", stop);
    el.addEventListener("mouseleave", function () { start(1500); });
    el.addEventListener("focusout", function () { start(1500); });
    start(ms);
  }

  function init() {
    document.querySelectorAll("a[href]").forEach(hideHref);
    document.querySelectorAll("[data-autohide]").forEach(autoHide);
    document.querySelectorAll("[data-att-input]").forEach(syncAttName);
    document.querySelectorAll('[data-item-field] [name="item_text"]').forEach(markLong);
    if (isItemText(document.activeElement)) openItem(document.activeElement);
  }

  function boot() {
    init();
    var st = history.state;
    if (isHubState(st)) {
      adopt(st);
      var y = savedScroll(st);
      items[st.idx] = { path: st.path, scroll: y };
      saveItems();
      settle(y, location.hash.slice(1), 0);
      return;
    }
    var tid = newTid();
    pruneTrails(tid);
    cur = { hub: 1, tid: tid, idx: 0, path: locPath() };
    items = [{ path: cur.path, scroll: 0 }];
    history.replaceState(cur, "", location.href);
    saveItems();
    if (location.hash) reveal(location.hash.slice(1));
  }

  window.addEventListener("pageshow", function (event) {
    if (event.persisted) root.classList.remove("hub-loading");
  });

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
