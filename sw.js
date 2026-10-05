/* Android Guru Agent 官网 Service Worker
   策略：
   - 导航请求：网络优先（保证发布后能拿到新版），离线时回退缓存
   - 同源静态资源：缓存优先，未命中则取网络并写入缓存
   - 跨域（如 GitHub badges / 字体）：不接管，浏览器默认行为
   发版时 bump CACHE_VER 即可让旧缓存全部失效。 */
"use strict";
var CACHE_VER = "aga-site-v3-games";
var CORE = [
  "./",
  "./index.html",
  "./404.html",
  "./og-image.png",
  "./icon-192.png",
  "./icon-512.png",
  "./apple-touch-icon.png",
  "./manifest.webmanifest"
];

self.addEventListener("install", function (e) {
  e.waitUntil(
    caches.open(CACHE_VER).then(function (c) {
      return Promise.all(CORE.map(function (u) {
        return c.add(new Request(u, { cache: "reload" })).catch(function () { /* 单项失败不阻塞安装 */ });
      }));
    }).then(function () {
      return self.skipWaiting();
    })
  );
});

self.addEventListener("activate", function (e) {
  e.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(keys.filter(function (k) { return k !== CACHE_VER; })
        .map(function (k) { return caches.delete(k); }));
    }).then(function () {
      return self.clients.claim();
    })
  );
});

self.addEventListener("fetch", function (e) {
  var req = e.request;
  if (req.method !== "GET") return;
  var url = new URL(req.url);
  if (url.origin !== self.location.origin) return; /* 跨域交给浏览器 */

  if (req.mode === "navigate") {
    /* 导航：网络优先，离线回退（先回退同路径缓存，再回退首页） */
    e.respondWith(
      fetch(req).then(function (res) {
        var copy = res.clone();
        caches.open(CACHE_VER).then(function (c) { c.put(req, copy); });
        return res;
      }).catch(function () {
        return caches.match(req, { ignoreSearch: true }).then(function (hit) {
          return hit || caches.match("./index.html");
        });
      })
    );
    return;
  }

  /* 静态资源：缓存优先 */
  e.respondWith(
    caches.match(req).then(function (hit) {
      if (hit) return hit;
      return fetch(req).then(function (res) {
        if (res && res.status === 200 && res.type === "basic") {
          var copy = res.clone();
          caches.open(CACHE_VER).then(function (c) { c.put(req, copy); });
        }
        return res;
      });
    })
  );
});
