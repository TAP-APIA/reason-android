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
          // Native-app feel: no blue tap highlight, and no focus ring on mouse/touch focus
          // (keyboard focus via :focus-visible keeps its outline).
          (function addStyle() {
            var parent = document.head || document.documentElement;
            if (!parent) { document.addEventListener('DOMContentLoaded', addStyle, { once: true }); return; }
            var st = document.createElement('style');
            st.id = '__reason-android-style';
            st.textContent = '*,*::before,*::after{-webkit-tap-highlight-color:transparent!important}' +
              ':focus:not(:focus-visible){outline:none!important}';
            parent.appendChild(st);
          })();
          // Remote computer control: the page reads only key codes from its hidden keyboard textarea,
          // but Android soft keyboards send text as input/composition events with an
          // "Unidentified" key. Turn that text into the key presses the page understands.
          (function keyboardBridge() {
            var SEL = 'textarea[data-keyboard-input="true"]';
            var realKeyAt = 0;
            var sent = ''; // composing text already typed on the remote side
            function isKbd(t) { return !!(t && t.matches && t.matches(SEL) && !t.disabled); }
            function codeFor(ch) {
              if (/^[a-z]$/i.test(ch)) return 'Key' + ch.toUpperCase();
              if (/^[0-9]$/.test(ch)) return 'Digit' + ch;
              if (ch === ' ') return 'Space';
              return '';
            }
            function press(t, key, code) {
              var o = { key: key, code: code || '', bubbles: true, cancelable: true, composed: true };
              t.dispatchEvent(new KeyboardEvent('keydown', o));
              t.dispatchEvent(new KeyboardEvent('keyup', o));
            }
            function typeText(t, s) {
              Array.from(s).forEach(function (ch) {
                if (ch === '\n') press(t, 'Enter', 'Enter'); else press(t, ch, codeFor(ch));
              });
            }
            // Bring the remote side in line with the keyboard's composing text: erase what changed, type the rest.
            function sync(t, cur) {
              var a = Array.from(sent), b = Array.from(cur || ''), i = 0;
              while (i < a.length && i < b.length && a[i] === b[i]) i++;
              for (var k = a.length; k > i; k--) press(t, 'Backspace', 'Backspace');
              typeText(t, b.slice(i).join(''));
              sent = b.join('');
            }
            function clear(t) { setTimeout(function () { if (!sent) t.value = ''; }, 0); }
            document.addEventListener('keydown', function (e) {
              if (!e.isTrusted || !isKbd(e.target)) return;
              if (e.key && e.key !== 'Unidentified' && e.key !== 'Process' && e.keyCode !== 229) realKeyAt = Date.now();
            }, true);
            document.addEventListener('compositionstart', function (e) {
              if (isKbd(e.target)) sent = '';
            }, true);
            document.addEventListener('beforeinput', function (e) {
              var t = e.target;
              if (!e.isTrusted || !isKbd(t) || e.isComposing || e.inputType === 'insertCompositionText') return;
              if (Date.now() - realKeyAt < 50) return; // already delivered as a real key press
              var ty = e.inputType;
              if (ty === 'insertText' || ty === 'insertReplacementText') {
                var d = e.data || (e.dataTransfer && e.dataTransfer.getData('text/plain')) || '';
                if (d) typeText(t, d);
              }
              else if (ty === 'insertLineBreak' || ty === 'insertParagraph') press(t, 'Enter', 'Enter');
              else if (ty.indexOf('deleteContentBackward') === 0 || ty.indexOf('deleteWordBackward') === 0) press(t, 'Backspace', 'Backspace');
              else if (ty.indexOf('deleteContentForward') === 0 || ty.indexOf('deleteWordForward') === 0) press(t, 'Delete', 'Delete');
            }, true);
            // Gboard and most Android keyboards compose every word; type it as it changes, not only at the end.
            document.addEventListener('input', function (e) {
              var t = e.target;
              if (!isKbd(t) || !(e.isComposing || e.inputType === 'insertCompositionText')) return;
              sync(t, t.value);
            }, true);
            document.addEventListener('compositionend', function (e) {
              var t = e.target;
              // No isTrusted check: Chrome can deliver IME composition events as untrusted.
              if (!isKbd(t)) return;
              sync(t, e.data != null ? e.data : t.value);
              sent = '';
              clear(t);
            }, true);
            // Tapping the remote screen focuses the hidden textarea, which does not raise the soft keyboard.
            document.addEventListener('touchend', function (e) {
              if (!e.isTrusted || !(e.target instanceof Element) || e.target.tagName !== 'CANVAS') return;
              setTimeout(function () {
                var a = document.activeElement;
                var B = window.${BlobBridge.NAME};
                if (isKbd(a) && B && B.showKeyboard) B.showKeyboard();
              }, 0);
            }, true);
          })();
          // The app shell is a fixed-height layout; when the keyboard opens and closes Chromium can leave the
          // whole document scrolled up a little, which tucks the floating header under the top edge.
          (function pinShell() {
            function fix() {
              if (window.scrollY > 0 && document.querySelector('.app-shell')) window.scrollTo(0, 0);
            }
            window.addEventListener('scroll', fix, { passive: true });
            window.addEventListener('resize', function () { setTimeout(fix, 50); });
            if (window.visualViewport) window.visualViewport.addEventListener('resize', function () { setTimeout(fix, 50); });
          })();
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

    /**
     * Run on the Android back button. Closes what is open on the page (fullscreen, a dialog, the side
     * panel with the remote computer) before navigating back, because the site opens those without a
     * history entry. Returns "handled" when it closed something.
     */
    val BACK = """
        (function () {
          function visible(el) {
            if (!el || !el.getClientRects().length) return false;
            var s = getComputedStyle(el);
            return s.visibility !== 'hidden' && s.display !== 'none';
          }
          if (document.fullscreenElement) { document.exitFullscreen().catch(function () {}); return 'handled'; }
          var a = document.activeElement;
          if (a && a.matches && a.matches('textarea[data-keyboard-input="true"]')) a.blur();
          var dialogs = Array.prototype.filter.call(
            document.querySelectorAll('dialog[open], [role="dialog"], [role="alertdialog"], [aria-modal="true"]'), visible);
          if (dialogs.length) {
            var d = dialogs[dialogs.length - 1];
            var o = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
            (d.contains(document.activeElement) ? document.activeElement : d).dispatchEvent(new KeyboardEvent('keydown', o));
            document.dispatchEvent(new KeyboardEvent('keyup', o));
            return 'handled';
          }
          var sels = ['.reason-session-panel [aria-label="Back"]', 'button[aria-label="Hide side panel"]',
            'button[aria-label="Show chat"]', 'button[aria-label="Close panel"]'];
          for (var i = 0; i < sels.length; i++) {
            var b = Array.prototype.filter.call(document.querySelectorAll(sels[i]), visible)[0];
            if (b) { b.click(); return 'handled'; }
          }
          return 'none';
        })()
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
