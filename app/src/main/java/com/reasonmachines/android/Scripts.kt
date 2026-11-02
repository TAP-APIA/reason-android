package com.reasonmachines.android

import org.json.JSONObject

object Scripts {
    /**
     * Runs at document start on Reason pages. Android WebView has no Notification API,
     * so this provides one backed by native notifications.
     */
    val SHIM = """
        (function () {
          if (window.__reasonAndroid) return;
          window.__reasonAndroid = true;
          var B = window.${BlobBridge.NAME};
          if (!B) return;
          var waiters = [];
          window.__reasonAndroidPermission = function (state) {
            NativeNotification.permission = state;
            var w = waiters; waiters = [];
            w.forEach(function (fn) { try { fn(state); } catch (e) {} });
          };
          function NativeNotification(title, options) {
            options = options || {};
            this.title = title;
            this.body = options.body || '';
            this.tag = options.tag || '';
            this.data = options.data;
            this.onclick = null; this.onclose = null; this.onerror = null; this.onshow = null;
            var url = (options.data && options.data.url) || location.href;
            if (NativeNotification.permission === 'granted') {
              B.showNotification(String(title), String(this.body), String(this.tag), String(url));
            }
          }
          NativeNotification.prototype.close = function () { if (this.tag) B.closeNotification(String(this.tag)); };
          NativeNotification.prototype.addEventListener = function () {};
          NativeNotification.prototype.removeEventListener = function () {};
          NativeNotification.permission = B.notificationPermission();
          NativeNotification.requestPermission = function (cb) {
            return new Promise(function (resolve) {
              waiters.push(function (s) { if (cb) cb(s); resolve(s); });
              B.requestNotificationPermission();
            });
          };
          Object.defineProperty(NativeNotification, 'maxActions', { value: 0 });
          try { window.Notification = NativeNotification; } catch (e) {}
        })();
    """.trimIndent()

    fun blobDownload(url: String, contentDisposition: String?, mimeType: String?): String {
        val u = JSONObject.quote(url)
        val cd = JSONObject.quote(contentDisposition ?: "")
        val mt = JSONObject.quote(mimeType ?: "")
        return """
            (function () {
              var B = window.${BlobBridge.NAME};
              var u = $u;
              var name = '';
              try {
                var a = document.querySelector('a[href="' + u + '"]');
                if (a && a.download) name = a.download;
              } catch (e) {}
              fetch(u).then(function (r) { return r.blob(); }).then(function (blob) {
                var reader = new FileReader();
                reader.onloadend = function () {
                  B.saveBlob(String(reader.result), name, $cd, blob.type || $mt);
                };
                reader.readAsDataURL(blob);
              }).catch(function (e) { B.downloadFailed(String(e)); });
            })();
        """.trimIndent()
    }
}
