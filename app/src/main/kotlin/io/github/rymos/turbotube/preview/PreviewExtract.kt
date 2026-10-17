package io.github.rymos.turbotube.preview

/**
 * Reads the tiles of YouTube's TV start page and hands them to the app.
 *
 * The page paints its shelves with thumbnails from i.ytimg.com, and each thumbnail's address
 * carries the video's id — that is the cheapest thing to read, and it survives changes to the
 * interface YouTube ships every few weeks. If the start page shows no pictures (still loading,
 * empty account), the same tiles are taken from the page's own start data.
 */
object PreviewExtract {
    /** Name the page calls when it has tiles. */
    const val BRIDGE = "TurbotubePreview"

    val SCRIPT = """
        (function () {
          var api = window.TurbotubePreview;
          if (!api) return;
          var MAX = 8;
          var found = [];
          var seen = {};
          function add(id, title) {
            if (!id || seen[id] || found.length >= MAX) return;
            seen[id] = true;
            var t = String(title || '').replace(/\s+/g, ' ').trim();
            if (!t) t = 'YouTube';
            found.push({ id: id, title: t.slice(0, 120) });
          }
          function label(el) {
            var node = el;
            for (var depth = 0; depth < 7 && node; depth++, node = node.parentElement) {
              var attr = node.getAttribute && node.getAttribute('aria-label');
              if (attr && attr.trim().length > 1 && attr.length < 160) return attr;
              var text = (node.textContent || '').trim();
              if (text.length > 1 && text.length < 160) return text;
            }
            return el.alt || '';
          }
          function fromImage(img) {
            var src = img.currentSrc || img.src || '';
            var match = /\/vi\/([\w-]{11})\//.exec(src);
            if (match) add(match[1], label(img));
          }
          var images = document.images;
          for (var i = 0; i < images.length; i++) fromImage(images[i]);
          function title(node) {
            var found = '';
            (function look(o, depth) {
              if (!o || typeof o !== 'object' || depth > 6 || found) return;
              if (o.title && typeof o.title === 'object') {
                if (typeof o.title.simpleText === 'string' && o.title.simpleText.trim()) {
                  found = o.title.simpleText;
                  return;
                }
                if (o.title.runs && o.title.runs[0] && typeof o.title.runs[0].text === 'string') {
                  found = o.title.runs[0].text;
                  return;
                }
              }
              if (typeof o.simpleText === 'string' && o.simpleText.trim().length > 1) {
                found = o.simpleText;
                return;
              }
              for (var key in o) {
                try { look(o[key], depth + 1); } catch (e) {}
              }
            })(node, 0);
            return found;
          }
          if (found.length < 3 && window.ytInitialData) {
            var chain = [];
            (function walk(node, depth) {
              if (!node || typeof node !== 'object' || depth > 16 || found.length >= MAX) return;
              if (typeof node.videoId === 'string' && /^[\w-]{11}$/.test(node.videoId)) {
                // The title sits next to the video id, one to four levels up (tile, shelf item)
                var name = '';
                for (var up = chain.length - 1; up >= 0 && up >= chain.length - 4 && !name; up--) {
                  name = title(chain[up]);
                }
                add(node.videoId, name);
              }
              chain.push(node);
              for (var key in node) {
                try { walk(node[key], depth + 1); } catch (e) {}
              }
              chain.pop();
            })(window.ytInitialData, 0);
          }
          if (found.length) {
            try { api.publish(JSON.stringify(found)); } catch (e) {}
          }
        })();
    """.trimIndent()
}
